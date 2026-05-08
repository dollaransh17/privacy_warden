package com.privacywarden.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.privacywarden.app.MainActivity
import com.privacywarden.app.R
import com.privacywarden.app.defense.SensorAccessWatcher
import com.privacywarden.app.net.TankClient
import com.privacywarden.app.net.FlowEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant

/**
 * Real on-device DNS-interception VPN service.
 *
 * We tell Android our DNS server is `10.7.0.1` and route only that subnet
 * through the tunnel. Every DNS query the device makes hits our TUN; we parse
 * the question, attribute it to the owning app via
 * [android.net.ConnectivityManager.getConnectionOwnerUid] (API 29+), then:
 *
 *   * If [RuleStore.shouldBlock] matches → we synthesise an NXDOMAIN reply
 *     and write it back into the TUN. The app's resolver fails the lookup and
 *     the underlying TCP/UDP connection is never made — real, on-device
 *     blocking. We also bump the NETWORK pillar's blocked counter and timeline.
 *   * Otherwise → we forward the query to a real upstream resolver via a
 *     `protect()`-bypassed socket and write the response back into the TUN so
 *     name resolution still works.
 *
 * All non-DNS traffic stays on the kernel's normal network path so the phone's
 * internet is unaffected.
 */
class WardenVpnService : VpnService() {

    private var pfd: ParcelFileDescriptor? = null
    private var tank: TankClient? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loop: Job? = null
    private val appResolver by lazy { AppResolver(this) }
    @Volatile private var lastApp: AppResolver.App? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "stop requested")
            teardown()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("Privacy Warden — protecting your device"))
        acquireWakeLock()
        startVpn()
        // Best-effort: register the mic/camera watcher whenever the foreground
        // service starts. If permissions aren't granted yet, this is a no-op
        // and the user can flip it on later from the Sensor Access card.
        runCatching { SensorAccessWatcher.start(this) }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PrivacyWarden::DemoLoop"
        ).apply {
            setReferenceCounted(false)
            acquire(/* timeout */ 24L * 60L * 60L * 1000L)  // 24h cap; renewed on each start
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun teardown() {
        loop?.cancel()
        loop = null
        runCatching { tank?.close() }
        tank = null
        runCatching { pfd?.close() }
        pfd = null
        runCatching { SensorAccessWatcher.stop(this) }
        releaseWakeLock()
        WardenState.running.set(false)
        WardenState.tankConnected.set(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun deviceId(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    private fun startVpn() {
        // REAL DNS-INTERCEPTION VPN:
        // We tell Android our DNS server is 10.7.0.1 (inside the tunnel) and
        // route only that subnet through us. Every DNS query the device makes
        // hits our TUN; we parse the real domain, forward to a real upstream
        // resolver via a protect()-bypassed socket, and write the response back
        // so apps' name resolution still works. All non-DNS traffic stays on
        // the normal kernel path so phone internet is unaffected.
        val builder = Builder()
            .setSession("Privacy Warden")
            .addAddress("10.7.0.2", 24)
            .addRoute("10.7.0.0", 24)
            .addDnsServer("10.7.0.1")
            .setMtu(1500)

        // Belt-and-braces: also exclude our own app so the Tank WebSocket
        // bypasses anything we do bind.
        runCatching { builder.addDisallowedApplication(packageName) }
            .onFailure { Log.w(TAG, "addDisallowedApplication failed: ${it.message}") }

        pfd = builder.establish()

        tank = TankClient(
            deviceId = deviceId(),
            protect = { sock -> protect(sock) },
            onRule = { rule ->
                RuleStore.apply(rule)
                WardenState.rulesApplied.incrementAndGet()
                WardenState.lastRule.set("${rule.action} ${rule.domain}")
                Log.i(TAG, "rule applied: ${rule.action} ${rule.domain}")
            },
        ).also { it.connect() }

        // Fresh session → reset live counters (Tank keeps full history).
        WardenState.flowsObserved.set(0)
        WardenState.flowsSent.set(0)
        WardenState.rulesApplied.set(0)
        WardenState.lastSni.set(null)
        WardenState.lastRule.set(null)
        WardenState.running.set(true)
        WardenState.startedAt.set(System.currentTimeMillis())
        loop = scope.launch { runDnsLoop() }
        Log.i(TAG, "VPN tunnel established + tank client launched")
    }

    /**
     * Real DNS-driven inspection + blocking loop.
     *
     * Every DNS query made by any app on the device hits our TUN because we
     * registered 10.7.0.1 as the system DNS server. We:
     *   1) parse the real domain out of the question section,
     *   2) attribute it to the owning app via getConnectionOwnerUid,
     *   3) consult [RuleStore] — BLOCK rules synthesise an NXDOMAIN reply,
     *      ALLOW rules and unmatched queries get forwarded normally,
     *   4) forward the query to a real upstream resolver and write the
     *      response back into the TUN so DNS resolution still succeeds.
     */
    private suspend fun runDnsLoop() {
        val pfd = pfd ?: return
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val dns = DnsInterceptor(protect = { sock -> protect(sock) })
        val buf = ByteArray(8192)
        Log.i(TAG, "real DNS interception loop starting")
        try {
            while (loop?.isActive == true) {
                val n = try { input.read(buf) } catch (t: Throwable) { Log.w(TAG, "tun read: ${t.message}"); break }
                if (n <= 0) continue
                val parsed = dns.parse(buf, n) ?: continue   // ignore non-DNS

                // Real per-packet app attribution.
                val app = appResolver.resolve(buf, n)
                if (app != null) lastApp = app
                val pkg = app?.pkg ?: lastApp?.pkg ?: "unknown"
                val label = app?.label ?: lastApp?.label ?: "Unknown"

                WardenState.flowsObserved.incrementAndGet()
                WardenState.lastSni.set("$label → ${parsed.domain}")
                WardenState.bumpBreakdown(WardenState.Pillar.NETWORK, parsed.domain)
                tank?.sendFlow(
                    FlowEvent(
                        device_id = deviceId(),
                        app_package = pkg,
                        app_label = label,
                        sni = parsed.domain,
                        bytes_up = n,
                        ts = Instant.now().toString(),
                    )
                )

                // Per-app quarantine: drop every DNS query owned by a flagged
                // package. The app gets NXDOMAIN for every host it tries to
                // resolve → no network at all, even though it is still
                // installed and "running".
                if (WardenState.isQuarantined(pkg)) {
                    Log.i(TAG, "QUARANTINE drop $label → ${parsed.domain}")
                    WardenState.quarantineBlockedCount.incrementAndGet()
                    WardenState.networkBlocked.incrementAndGet()
                    WardenState.networkLast.set("Quarantined: $label cut off")
                    WardenState.pushEvent(
                        WardenState.TimelineEvent(
                            pillar = WardenState.Pillar.APPS,
                            title = "Quarantined app blocked",
                            detail = "$label tried ${parsed.domain}",
                        )
                    )
                    val nx = dns.buildBlockedResponse(parsed)
                    if (nx != null) synchronized(output) { output.write(nx) }
                    continue
                }

                // Real blocking: if RuleStore says NO, synthesise NXDOMAIN.
                if (RuleStore.shouldBlock(parsed.domain)) {
                    Log.i(TAG, "BLOCK  $label → ${parsed.domain}")
                    WardenState.networkBlocked.incrementAndGet()
                    WardenState.networkLast.set("Blocked ${parsed.domain} for $label")
                    WardenState.pushEvent(
                        WardenState.TimelineEvent(
                            pillar = WardenState.Pillar.NETWORK,
                            title = "Tracker blocked",
                            detail = "${parsed.domain} requested by $label",
                        )
                    )
                    val nx = dns.buildBlockedResponse(parsed)
                    if (nx != null) synchronized(output) { output.write(nx) }
                    continue
                }

                Log.d(TAG, "DNS    $label → ${parsed.domain}")
                // Forward to upstream and write response back so the app's
                // resolution actually completes (no broken internet).
                scope.launch {
                    runCatching {
                        val resp = dns.forwardAndPackage(parsed) ?: return@launch
                        synchronized(output) { output.write(resp) }
                    }.onFailure { Log.w(TAG, "dns forward write: ${it.message}") }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "dns loop ended: ${t.message}")
        }
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        runCatching { pfd?.close() }
        runCatching { SensorAccessWatcher.stop(this) }
        releaseWakeLock()
        WardenState.running.set(false)
        WardenState.tankConnected.set(false)
        super.onDestroy()
        Log.i(TAG, "service destroyed")
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "warden-vpn"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Privacy Warden", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, channelId)
            .setContentTitle("Privacy Warden")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "WardenVpnService"
        private const val NOTIF_ID = 4711
        const val ACTION_STOP = "com.privacywarden.app.action.STOP"
    }
}
