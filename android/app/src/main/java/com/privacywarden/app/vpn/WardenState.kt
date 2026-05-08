package com.privacywarden.app.vpn

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide live state shared between the VpnService, defense modules and UI.
 *
 * Each defense pillar has its own counter + last-seen event so the multi-pillar
 * home screen can render rich status without polling individual modules.
 */
object WardenState {
    // ── tunnel + tank state ─────────────────────────────────────────────────
    val running = AtomicBoolean(false)
    val tankConnected = AtomicBoolean(false)
    val flowsObserved = AtomicInteger(0)
    val flowsSent = AtomicInteger(0)
    val rulesApplied = AtomicInteger(0)
    val lastSni = AtomicReference<String?>(null)
    val lastRule = AtomicReference<String?>(null)
    val startedAt = AtomicLong(0L)

    // ── pillar-level counters (one per defense pillar) ──────────────────────
    val networkBlocked = AtomicInteger(0)        // trackers blocked
    val commsBlocked = AtomicInteger(0)          // scam calls/SMS blocked
    val moneyBlocked = AtomicInteger(0)          // UPI fraud / suspicious payments
    val appsBlocked = AtomicInteger(0)           // stalkerware / risky apps caught
    val identityBlocked = AtomicInteger(0)       // OTP guarded / breach alerts
    val physicalBlocked = AtomicInteger(0)       // panic / theft events

    val networkLast = AtomicReference<String?>(null)
    val commsLast = AtomicReference<String?>(null)
    val moneyLast = AtomicReference<String?>(null)
    val appsLast = AtomicReference<String?>(null)
    val identityLast = AtomicReference<String?>(null)
    val physicalLast = AtomicReference<String?>(null)

    /**
     * Recent timeline of defense events shown on the home screen.
     * Newest first; capped at 50 entries.
     */
    val timeline: ConcurrentLinkedDeque<TimelineEvent> = ConcurrentLinkedDeque()

    fun pushEvent(e: TimelineEvent) {
        timeline.addFirst(e)
        while (timeline.size > 50) timeline.pollLast()
        // bump the per-minute history bucket for charts
        val bucket = (e.ts / 60_000L)
        history.getOrPut(e.pillar) { ConcurrentHashMap() }
            .compute(bucket) { _, v -> (v ?: 0) + 1 }
    }

    /** category-name → count, used by pie / bar charts on dashboards. */
    val breakdown: ConcurrentHashMap<Pillar, ConcurrentHashMap<String, Int>> = ConcurrentHashMap()

    /** per-pillar minute-bucket history for sparkline / area charts. */
    val history: ConcurrentHashMap<Pillar, ConcurrentHashMap<Long, Int>> = ConcurrentHashMap()

    fun bumpBreakdown(pillar: Pillar, category: String, by: Int = 1) {
        breakdown.getOrPut(pillar) { ConcurrentHashMap() }
            .compute(category) { _, v -> (v ?: 0) + by }
    }

    /** Returns counts for the last [minutes] minutes, oldest → newest. */
    fun seriesForLastMinutes(pillar: Pillar, minutes: Int = 30): IntArray {
        val now = System.currentTimeMillis() / 60_000L
        val map = history[pillar] ?: return IntArray(minutes)
        return IntArray(minutes) { i ->
            val b = now - (minutes - 1 - i)
            map[b] ?: 0
        }
    }

    data class TimelineEvent(
        val pillar: Pillar,
        val title: String,
        val detail: String,
        val ts: Long = System.currentTimeMillis(),
    )

    // ── email scanner ───────────────────────────────────────────────────────
    /** Single email/notification scanned by the email scanner. */
    data class EmailScan(
        val sender: String,
        val subject: String,
        val score: Int,
        val isPhishing: Boolean,
        val reasons: List<String>,
        val urls: List<String>,
        val source: String,            // "Gmail notification" | "Paste" | "Share"
        val ts: Long = System.currentTimeMillis(),
    )

    /** Newest first, capped at 100. */
    val emailScans: ConcurrentLinkedDeque<EmailScan> = ConcurrentLinkedDeque()
    val emailListenerActive = AtomicBoolean(false)

    fun recordEmailScan(s: EmailScan) {
        emailScans.addFirst(s)
        while (emailScans.size > 100) emailScans.pollLast()
    }

    fun emailScoreSeries(buckets: Int = 10): IntArray {
        // Simple bar chart of recent scan scores (most-recent-first → reverse)
        val list = emailScans.toList().take(buckets).reversed()
        return IntArray(buckets) { i ->
            list.getOrNull(i)?.score ?: 0
        }
    }

    // ── live mic / camera access timeline ───────────────────────────────────
    /**
     * One observed activation of the mic or camera by some app on the device.
     * `endTs == 0` while the access is still ongoing; the watcher closes it
     * out once `OnOpActiveChangedListener` reports `active == false`.
     */
    data class SensorAccess(
        val pkg: String,
        val label: String,
        val kind: Kind,
        val startTs: Long,
        @Volatile var endTs: Long = 0L,
    ) {
        enum class Kind { MIC, CAMERA }
        val active: Boolean get() = endTs == 0L
        val durationMs: Long
            get() = (if (endTs > 0L) endTs else System.currentTimeMillis()) - startTs
    }

    /** Newest first, capped at 200 entries. */
    val sensorAccesses: ConcurrentLinkedDeque<SensorAccess> = ConcurrentLinkedDeque()

    /** True iff the AppOps active-watcher is currently registered with the OS. */
    val sensorWatcherActive = AtomicBoolean(false)

    // ── panic mode ──────────────────────────────────────────────────────────
    /**
     * When true, [RuleStore.shouldBlock] also consults [PanicBlocklist] and
     * returns true for any tracker / analytics / ad domain on that list.
     * Effect: every background phone-home is dropped at the DNS layer
     * (synthetic NXDOMAIN), while legitimate first-party app traffic passes
     * through untouched.
     */
    val panicMode = AtomicBoolean(false)
    val panicSince = AtomicLong(0L)
    val panicBlockedCount = AtomicInteger(0)

    // ── per-app quarantine ──────────────────────────────────────────────────
    /**
     * Packages flagged by the user as suspicious (e.g., from the unsafe-app
     * picker). The VPN drops every DNS lookup made by these UIDs, so the app
     * gets no network at all. The UI also offers a one-tap deep-link to the
     * App Info screen so the user can Force Stop or Uninstall.
     */
    val quarantinedPackages: java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()
    val quarantineBlockedCount = AtomicInteger(0)

    fun quarantine(pkg: String) { quarantinedPackages.add(pkg) }
    fun unquarantine(pkg: String) { quarantinedPackages.remove(pkg) }
    fun isQuarantined(pkg: String?): Boolean = pkg != null && quarantinedPackages.contains(pkg)

    fun recordSensorStart(pkg: String, label: String, kind: SensorAccess.Kind) {
        // Dedupe: skip if we already have an active record for this (pkg, kind).
        if (sensorAccesses.any { it.pkg == pkg && it.kind == kind && it.active }) return
        sensorAccesses.addFirst(SensorAccess(pkg, label, kind, System.currentTimeMillis()))
        while (sensorAccesses.size > 200) sensorAccesses.pollLast()
        // Also publish a timeline event under APPS so the existing dashboards
        // surface mic/camera activations alongside other app-behaviour signals.
        pushEvent(
            TimelineEvent(
                pillar = Pillar.APPS,
                title = "${if (kind == SensorAccess.Kind.MIC) "Mic" else "Camera"} accessed",
                detail = "$label · just now",
            )
        )
        bumpBreakdown(Pillar.APPS, label)
    }

    fun recordSensorStop(pkg: String, kind: SensorAccess.Kind) {
        val match = sensorAccesses.firstOrNull { it.pkg == pkg && it.kind == kind && it.active }
            ?: return
        match.endTs = System.currentTimeMillis()
    }

    enum class Pillar(val label: String, val emoji: String, val accent: Long) {
        NETWORK("Network",  "\uD83C\uDF10", 0xFF2563EB),
        COMMS("Comms",      "\uD83D\uDCDE", 0xFF7C3AED),
        MONEY("Money",      "\uD83D\uDCB8", 0xFF059669),
        APPS("Apps",        "\uD83D\uDCF1", 0xFFDB2777),
        IDENTITY("Identity","\uD83D\uDD12", 0xFFD97706),
        PHYSICAL("Physical","\uD83D\uDEA8", 0xFFDC2626),
    }
}
