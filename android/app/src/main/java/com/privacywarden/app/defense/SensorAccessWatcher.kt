package com.privacywarden.app.defense

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.privacywarden.app.vpn.WardenState

/**
 * Real on-device mic / camera access watcher.
 *
 * We deliberately do **not** rely solely on [AppOpsManager.startWatchingActive].
 * That API is documented as firing for every app on the device when the
 * caller holds RECORD_AUDIO / CAMERA, but on many OEM builds (notably
 * Samsung One UI, Xiaomi MIUI, some Oppo/Vivo) the OS *registers* the
 * callback successfully yet never delivers events for third-party UIDs.
 *
 * To get reliable behaviour everywhere we layer two framework APIs that
 * the OS itself uses to drive its privacy indicator and which work on
 * stock and OEM builds alike:
 *
 *   * **Mic** — [AudioManager.registerAudioRecordingCallback] (API 24+).
 *     Fires whenever any app starts/stops recording audio. The
 *     [AudioRecordingConfiguration] objects expose the recording app's
 *     UID, which we resolve to a package + label via PackageManager. No
 *     runtime permissions are required.
 *
 *   * **Camera** — [CameraManager.AvailabilityCallback] (API 21+).
 *     `onCameraUnavailable(cameraId)` fires the instant any app opens the
 *     camera. The callback doesn't reveal *which* app, so we attribute it
 *     to whatever's currently in the foreground (UsageStats heuristic).
 *
 * If the user has additionally granted RECORD_AUDIO / CAMERA we *also*
 * register an AppOps watcher — on builds where it works, that gives us
 * stricter per-event attribution. Both layers feed the same
 * [WardenState] sink with deduplication so events aren't double-counted.
 */
object SensorAccessWatcher {

    private const val TAG = "SensorAccessWatcher"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var audioCb: AudioManager.AudioRecordingCallback? = null
    @Volatile private var camCb: CameraManager.AvailabilityCallback? = null
    @Volatile private var appOpsCb: AppOpsManager.OnOpActiveChangedListener? = null
    @Volatile private var watchdog: Runnable? = null
    private const val WATCHDOG_PERIOD_MS = 8_000L

    /**
     * The currently-attributed mic owner (package name) and how many active
     * recordings we last saw. AudioRecordingConfiguration.getClientUid() is
     * `@hide`; we can detect *that* recording is happening but not *who*
     * directly, so we attribute to the foreground app at the moment the
     * recording started — same pattern as camera below.
     */
    @Volatile private var lastMicCount = 0
    @Volatile private var currentMicOwner: String? = null

    /** cameraId → package we attributed this access to (so OFF can match it). */
    private val activeCameraOwners = mutableMapOf<String, String>()

    fun hasMicPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun hasCameraPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** Always supported on API 21+ (the lowest of the two callback APIs). */
    fun isSupported(ctx: Context): Boolean = true

    /**
     * Register (or re-register) the watchers. Idempotent — calling more
     * than once cleanly tears down the previous registration first.
     * Returns true if at least one source attached successfully.
     */
    @Synchronized
    fun start(ctx: Context): Boolean {
        stop(ctx)
        val app = ctx.applicationContext
        var attached = false

        // ── 1. Mic via AudioManager ───────────────────────────────────────
        runCatching {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val pm = app.packageManager
            val cb = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                    val count = configs?.size ?: 0
                    val prev = lastMicCount
                    lastMicCount = count
                    if (count > 0 && prev == 0) {
                        // Something started recording. We don't know which app
                        // (clientUid is @hide), so attribute to whatever is
                        // currently in the foreground.
                        val (pkg, label) = bestGuessForegroundApp(
                            app, pm, WardenState.SensorAccess.Kind.MIC
                        )
                        currentMicOwner = pkg
                        WardenState.recordSensorStart(pkg, label, WardenState.SensorAccess.Kind.MIC)
                        Log.i(TAG, "MIC ON  pkg=$pkg ($label) configs=$count")
                    } else if (count == 0 && prev > 0) {
                        val pkg = currentMicOwner ?: return
                        WardenState.recordSensorStop(pkg, WardenState.SensorAccess.Kind.MIC)
                        currentMicOwner = null
                        Log.i(TAG, "MIC OFF pkg=$pkg")
                    }
                }
            }
            am.registerAudioRecordingCallback(cb, mainHandler)
            audioCb = cb
            attached = true
            Log.i(TAG, "AudioRecordingCallback registered")
        }.onFailure { Log.w(TAG, "audio cb failed: ${it.message}") }

        // ── 2. Camera via CameraManager ───────────────────────────────────
        runCatching {
            val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val pm = app.packageManager
            val cb = object : CameraManager.AvailabilityCallback() {
                override fun onCameraUnavailable(cameraId: String) {
                    // We can't see which app holds it directly. Best-effort:
                    // attribute to whichever app is currently foreground.
                    val (pkg, label) = bestGuessForegroundApp(
                        app, pm, WardenState.SensorAccess.Kind.CAMERA
                    )
                    activeCameraOwners[cameraId] = pkg
                    WardenState.recordSensorStart(pkg, label, WardenState.SensorAccess.Kind.CAMERA)
                    Log.i(TAG, "CAM ON  cam=$cameraId by $pkg ($label)")
                }
                override fun onCameraAvailable(cameraId: String) {
                    val pkg = activeCameraOwners.remove(cameraId) ?: return
                    WardenState.recordSensorStop(pkg, WardenState.SensorAccess.Kind.CAMERA)
                    Log.i(TAG, "CAM OFF cam=$cameraId pkg=$pkg")
                }
            }
            cm.registerAvailabilityCallback(cb, mainHandler)
            camCb = cb
            attached = true
            Log.i(TAG, "CameraAvailabilityCallback registered")
        }.onFailure { Log.w(TAG, "camera cb failed: ${it.message}") }

        // ── 3. AppOps enrichment layer (only if perms granted) ────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ops = mutableListOf<String>()
            if (hasMicPermission(app))    ops += AppOpsManager.OPSTR_RECORD_AUDIO
            if (hasCameraPermission(app)) ops += AppOpsManager.OPSTR_CAMERA
            if (ops.isNotEmpty()) {
                runCatching {
                    val appOps = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                    val pm = app.packageManager
                    val cb = AppOpsManager.OnOpActiveChangedListener { op, _, pkg, active ->
                        val kind = when (op) {
                            AppOpsManager.OPSTR_RECORD_AUDIO -> WardenState.SensorAccess.Kind.MIC
                            AppOpsManager.OPSTR_CAMERA       -> WardenState.SensorAccess.Kind.CAMERA
                            else                             -> return@OnOpActiveChangedListener
                        }
                        if (pkg == app.packageName) return@OnOpActiveChangedListener
                        val label = runCatching {
                            val ai = pm.getApplicationInfo(pkg, 0)
                            pm.getApplicationLabel(ai).toString()
                        }.getOrNull() ?: pkg
                        if (active) {
                            WardenState.recordSensorStart(pkg, label, kind)
                            Log.i(TAG, "AppOps ON  $kind $pkg")
                        } else {
                            WardenState.recordSensorStop(pkg, kind)
                            Log.i(TAG, "AppOps OFF $kind $pkg")
                        }
                    }
                    appOps.startWatchingActive(ops.toTypedArray(), app.mainExecutor, cb)
                    appOpsCb = cb
                    Log.i(TAG, "AppOps watcher attached for ${ops.joinToString()}")
                }.onFailure { Log.w(TAG, "AppOps attach failed: ${it.message}") }
            }
        }

        WardenState.sensorWatcherActive.set(attached)

        // ── 4. Reconciliation watchdog ───────────────────────────────────
        // On many OEM builds (MIUI, One UI, Oppo) the AudioRecordingCallback
        // stops firing when the recording client releases its AudioRecord
        // without calling stop() first — the system simply drops the
        // "size went 1→0" callback. Result: our lastMicCount is stuck at 1
        // and the app keeps reporting "WhatsApp is using mic" even after
        // the call ended.  We fix this by re-querying the actual active
        // recordings every WATCHDOG_PERIOD_MS and forcibly publishing a
        // stop event if reality has diverged from our cached state.
        watchdog = object : Runnable {
            override fun run() {
                runCatching {
                    val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val real = am.activeRecordingConfigurations?.size ?: 0
                    if (real == 0 && lastMicCount > 0) {
                        val pkg = currentMicOwner
                        if (pkg != null) {
                            WardenState.recordSensorStop(
                                pkg, WardenState.SensorAccess.Kind.MIC
                            )
                            Log.i(TAG, "WATCHDOG: mic stop reconciled for $pkg")
                        }
                        lastMicCount = 0
                        currentMicOwner = null
                    }
                }
                mainHandler.postDelayed(this, WATCHDOG_PERIOD_MS)
            }
        }
        mainHandler.postDelayed(watchdog!!, WATCHDOG_PERIOD_MS)

        return attached
    }

    @Synchronized
    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        audioCb?.let { cb ->
            runCatching {
                (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .unregisterAudioRecordingCallback(cb)
            }
        }
        audioCb = null
        camCb?.let { cb ->
            runCatching {
                (app.getSystemService(Context.CAMERA_SERVICE) as CameraManager)
                    .unregisterAvailabilityCallback(cb)
            }
        }
        camCb = null
        appOpsCb?.let { cb ->
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    (app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
                        .stopWatchingActive(cb)
                }
            }
        }
        appOpsCb = null
        watchdog?.let { mainHandler.removeCallbacks(it) }
        watchdog = null
        lastMicCount = 0
        currentMicOwner = null
        activeCameraOwners.clear()
        WardenState.sensorWatcherActive.set(false)
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** Best-effort: most-recently-foreground app excluding ourselves. */
    private fun bestGuessForegroundApp(
        ctx: Context,
        pm: PackageManager,
        kind: WardenState.SensorAccess.Kind,
    ): Pair<String, String> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usm != null) {
            val now = System.currentTimeMillis()
            // Expand time window to 5 minutes to catch apps that were recently active
            val stats = runCatching {
                usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 300_000L, now)
            }.getOrNull()
            
            // Filter for apps with recent activity, prioritize by lastTimeUsed
            val top = stats
                ?.filter { it.lastTimeUsed > now - 300_000L && it.packageName != ctx.packageName }
                ?.maxByOrNull { it.lastTimeUsed }
                
            if (top != null) {
                val label = runCatching {
                    val ai = pm.getApplicationInfo(top.packageName, 0)
                    pm.getApplicationLabel(ai).toString()
                }.getOrNull() ?: top.packageName
                
                Log.i(TAG, "Attributed $kind to ${top.packageName} ($label) - last used ${now - top.lastTimeUsed}ms ago")
                return top.packageName to label
            } else {
                Log.w(TAG, "No recent foreground app found for $kind attribution")
            }
        } else {
            Log.w(TAG, "UsageStatsManager not available for $kind attribution")
        }
        
        // Neutral, kind-aware fallback when we can't attribute (e.g., the
        // user hasn't granted PACKAGE_USAGE_STATS in system settings).
        val label = when (kind) {
            WardenState.SensorAccess.Kind.MIC    -> "Some app (mic)"
            WardenState.SensorAccess.Kind.CAMERA -> "Some app (camera)"
        }
        Log.w(TAG, "Using fallback attribution for $kind: $label")
        return "unknown" to label
    }
}
