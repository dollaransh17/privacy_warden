package com.privacywarden.app

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.privacywarden.app.defense.EmailHook
import com.privacywarden.app.defense.SensorAccessWatcher
import com.privacywarden.app.vpn.WardenState
import com.privacywarden.app.vpn.WardenState.Pillar
import com.privacywarden.app.vpn.WardenVpnService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Dark-red home for Privacy Warden.
 *
 *   - Top bar: shield brand + power chip
 *   - Greeting hero with red gradient wash
 *   - Auto-advancing sliding carousel of recent analyses (one per page)
 *   - Category pills (horizontally scrollable)
 *   - Privacy Score card (red gradient)
 *   - Stat tiles row
 *   - Quick scan list with dark icon badges
 *   - Recent activity timeline
 *   - Bottom nav
 */
class MainActivity : ComponentActivity() {

    // ── palette ────────────────────────────────────────────────────────────
    // Strategy: BRAND (blue) is the calm, trustworthy chrome colour — used for
    // the hero wash, navigation, brand icons, FAB, and anything that is NOT a
    // warning. ACCENT (red) is reserved for genuine danger signals: panic
    // mode, phishing flags, stalkerware alerts, low privacy scores. This keeps
    // red meaningful instead of ambient.
    private val BG          = 0xFF08090C.toInt()     // near-black, faint blue tint
    private val BG_HERO     = 0xFF0E1A2E.toInt()     // navy hero wash (was dark red)
    private val CARD        = 0xFF101420.toInt()     // blue-charcoal card
    private val CARD_BORDER = 0xFF1C2438.toInt()
    private val SOFT_BADGE  = 0xFF11213D.toInt()     // blue soft badge
    private val SOFT_DANGER = 0xFF2A1014.toInt()     // red soft badge (for threat icons)
    private val TEXT_HI     = 0xFFF2F5F7.toInt()
    private val TEXT_MID    = 0xFF9AA3B2.toInt()
    private val TEXT_LO     = 0xFF5F6A7D.toInt()
    // Danger reds (reserved for threats)
    private val ACCENT      = 0xFFEF4444.toInt()     // red-500
    private val ACCENT_DEEP = 0xFFDC2626.toInt()     // red-600
    private val ACCENT_DARK = 0xFF7F1D1D.toInt()     // red-900
    // Brand blues (chrome, nav, FAB, safe-state indicators)
    private val BRAND       = 0xFF3B82F6.toInt()     // blue-500
    private val BRAND_DEEP  = 0xFF2563EB.toInt()     // blue-600
    private val BRAND_DARK  = 0xFF1E3A8A.toInt()     // blue-900
    private val AMBER       = 0xFFF59E0B.toInt()
    private val GREEN       = 0xFF10B981.toInt()
    private val INFO_BLUE   = 0xFF60A5FA.toInt()

    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
    private val dateFmt = SimpleDateFormat("MMM d", Locale.US)

    // ── live view refs ─────────────────────────────────────────────────────
    private lateinit var greetingTitle: TextView
    private lateinit var greetingSub: TextView
    private lateinit var powerToggle: TextView

    private lateinit var carouselScroll: HorizontalScrollView
    private lateinit var carouselContent: LinearLayout
    private lateinit var carouselDots: LinearLayout
    private var carouselItems: List<AnalysisItem> = emptyList()
    private var carouselIndex: Int = 0
    private var carouselCardWidthPx: Int = 0
    private var lastSnapshotKey: String = ""

    private lateinit var scoreNumber: TextView
    private lateinit var scoreSub: TextView

    private lateinit var statPhishingValue: TextView
    private lateinit var statTrackersValue: TextView
    private lateinit var statAppsValue: TextView

    private lateinit var timelineHolder: LinearLayout

    private lateinit var sensorStatusLabel: TextView
    private lateinit var sensorStatusDot: View
    private lateinit var sensorEnableBtn: Button
    private lateinit var sensorCounters: TextView
    private lateinit var sensorEventsHolder: LinearLayout

    private lateinit var statusBadgeText: TextView
    private lateinit var statusRowsHolder: LinearLayout

    private lateinit var panicCardRoot: LinearLayout
    private lateinit var panicTitle: TextView
    private lateinit var panicSubtitle: TextView
    private lateinit var panicBigButton: Button
    private lateinit var panicCounter: TextView
    private lateinit var panicShieldIcon: ImageView

    private val vpnLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) {
                startService(Intent(this, WardenVpnService::class.java))
            }
        }

    private val sensorPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // Whether or not the user granted, retry: even one of the two perms
            // is enough to start watching that sensor.
            SensorAccessWatcher.start(this)
            refresh()
        }

    // ── lifecycle ──────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark system bars — light icons over the near-black background.
        @Suppress("DEPRECATION")
        window.statusBarColor = BG
        @Suppress("DEPRECATION")
        window.navigationBarColor = CARD
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        // Diagnostic safety net: if anything in buildView() blows up, show the stack
        // on screen instead of an opaque "keeps stopping" dialog.
        try {
            setContentView(buildView())
        } catch (t: Throwable) {
            setContentView(buildCrashScreen(t))
            return
        }
        if (!WardenState.running.get() && VpnService.prepare(this) == null) {
            startService(Intent(this, WardenVpnService::class.java))
        }
        // Best-effort: if the user already granted mic/camera permission in a
        // previous session, register the AppOps watcher right away so the
        // sensor card is live by the time they scroll to it.
        runCatching { SensorAccessWatcher.start(this) }
        handleShareIntent(intent)
    }

    private fun buildCrashScreen(t: Throwable): View {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(20))
        }
        box.addView(TextView(this).apply {
            text = "Privacy Warden — startup error"
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = sw.toString()
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(12), 0, 0)
            setTextIsSelectable(true)
        })
        scroll.addView(box)
        return scroll
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val verdict = EmailHook.analyzeShared(text)
        AlertDialog.Builder(this)
            .setTitle(if (verdict.isPhishing) "Phishing detected" else "Looks safe")
            .setMessage(buildString {
                append("Score: ${verdict.score}/100\n\n")
                if (verdict.reasons.isNotEmpty()) {
                    append("Reasons:\n")
                    verdict.reasons.forEach { append(" • $it\n") }
                }
                if (verdict.matchedUrls.isNotEmpty()) {
                    append("\nURLs:\n")
                    verdict.matchedUrls.forEach { append(" • $it\n") }
                }
            })
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Belt-and-suspenders: if the user granted mic/camera perms in a prior
        // session or through system settings, make sure the AppOps watcher is
        // actually registered now that the activity is foregrounded.
        runCatching { SensorAccessWatcher.start(this) }
        ticker.run()
        carouselAutoplay.run()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(carouselAutoplay)
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1_000)
        }
    }

    /** Auto-advance the carousel every 4.5 seconds. */
    private val carouselAutoplay = object : Runnable {
        override fun run() {
            val n = carouselItems.size
            if (n > 1 && carouselCardWidthPx > 0) {
                carouselIndex = (carouselIndex + 1) % n
                carouselScroll.smoothScrollTo(carouselIndex * carouselCardWidthPx, 0)
                renderDots(n, carouselIndex)
            }
            handler.postDelayed(this, 4_500)
        }
    }

    // ── refresh ────────────────────────────────────────────────────────────

    private fun refresh() {
        val running = WardenState.running.get()
        val connected = WardenState.tankConnected.get()

        greetingTitle.text = if (running) "You're being watched." else "Defenses paused."
        greetingSub.text = when {
            !running -> "Tap the power chip to enable Privacy Warden."
            connected -> "All defenses live · Tank online"
            else -> "All defenses live · reconnecting…"
        }
        powerToggle.text = if (running) "ON" else "OFF"
        powerToggle.background = pillBg(if (running) ACCENT else 0xFF3A2024.toInt(), 18)

        val phishing = WardenState.commsBlocked.get()
        val trackers = WardenState.networkBlocked.get()
        val apps = WardenState.appsBlocked.get()
        statPhishingValue.text = phishing.toString()
        statTrackersValue.text = trackers.toString()
        statAppsValue.text = apps.toString()

        val score = computePrivacyScore(phishing, trackers, apps, running, connected)
        scoreNumber.text = "$score"
        scoreSub.text = scoreSubtitle(score, running)

        // Refresh carousel only if data has changed (don't fight the auto-scroll).
        val items = buildAnalysisItems()
        val key = items.joinToString("|") { it.id }
        if (key != lastSnapshotKey) {
            lastSnapshotKey = key
            renderCarousel(items)
        }

        renderTimeline()
        renderSensorAccess()
        renderSystemStatus()
        renderPanic()
    }

    private fun computePrivacyScore(
        phishing: Int, trackers: Int, apps: Int, running: Boolean, connected: Boolean
    ): Int {
        if (!running) return 50
        var s = 100
        s -= min(30, phishing * 3)
        s -= min(20, trackers / 5)
        s -= min(30, apps * 6)
        if (!connected) s -= 5
        return s.coerceIn(0, 100)
    }

    private fun scoreSubtitle(score: Int, running: Boolean): String = when {
        !running -> "Defenses paused — turn on to scan"
        score >= 85 -> "Excellent · all defenses healthy"
        score >= 65 -> "Good · minor threats detected"
        score >= 40 -> "Moderate · review flagged items"
        else -> "Critical · multiple active threats"
    }

    private fun renderTimeline() {
        timelineHolder.removeAllViews()
        val events = WardenState.timeline.toList().take(6)
        if (events.isEmpty()) {
            timelineHolder.addView(TextView(this).apply {
                text = "No events yet. Run an SMS sweep, stalkerware scan, or open the message scanner."
                setTextColor(TEXT_LO)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(8), 0, dp(8))
                setLineSpacing(0f, 1.3f)
            })
            return
        }
        for (e in events) timelineHolder.addView(timelineRow(e))
    }

    private fun timelineRow(e: WardenState.TimelineEvent): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val (icon, _) = pillarIconFor(e.pillar)
        row.addView(iconBadge(icon, ACCENT, SOFT_BADGE, dp(36)))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }
        col.addView(TextView(this).apply {
            text = e.title
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
        })
        col.addView(TextView(this).apply {
            text = e.detail
            setTextColor(TEXT_MID)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
            setPadding(0, dp(2), 0, 0)
        })
        col.addView(TextView(this).apply {
            text = "${timeFmt.format(Date(e.ts))}  ·  ${e.pillar.label}"
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(col)
        return row
    }

    private fun pillarIconFor(p: Pillar): Pair<Int, Int> = when (p) {
        Pillar.NETWORK  -> R.drawable.ic_globe   to SOFT_BADGE
        Pillar.COMMS    -> R.drawable.ic_message to SOFT_BADGE
        Pillar.MONEY    -> R.drawable.ic_money   to SOFT_BADGE
        Pillar.APPS     -> R.drawable.ic_apps    to SOFT_BADGE
        Pillar.IDENTITY -> R.drawable.ic_lock    to SOFT_BADGE
        Pillar.PHYSICAL -> R.drawable.ic_alert   to SOFT_BADGE
    }

    // ── primary action ─────────────────────────────────────────────────────
    private fun onPrimaryTap() {
        if (WardenState.running.get()) {
            startService(
                Intent(this, WardenVpnService::class.java)
                    .setAction(WardenVpnService.ACTION_STOP)
            )
            return
        }
        promptIgnoreBatteryOptimizations()
        val intent = VpnService.prepare(this)
        if (intent != null) vpnLauncher.launch(intent)
        else startService(Intent(this, WardenVpnService::class.java))
    }

    private fun promptIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // ANALYSIS CAROUSEL — one analysis per page, auto-slides every 4.5s
    // ──────────────────────────────────────────────────────────────────────

    /** A single analysis card shown in the carousel. */
    private data class AnalysisItem(
        val id: String,
        val type: String,           // "EMAIL" / "SMS" / "STALKERWARE" / "TRACKER" / "WELCOME"
        val title: String,
        val body: String,
        val score: Int,             // 0-100 (severity, higher = worse)
        val severity: Severity,
        val ts: Long,
        val cta: String = "Tap for details",
        val onTap: () -> Unit = {},
    ) {
        enum class Severity { CRITICAL, WARNING, OK, INFO }
    }

    private fun buildAnalysisItems(): List<AnalysisItem> {
        val out = mutableListOf<AnalysisItem>()
        val now = System.currentTimeMillis()

        // Most recent email scans (up to 3)
        WardenState.emailScans.toList().take(3).forEachIndexed { i, s ->
            out += AnalysisItem(
                id = "email-${s.ts}-$i",
                type = "EMAIL ANALYSIS",
                title = s.sender.take(48).ifBlank { "(unknown sender)" },
                body = if (s.reasons.isNotEmpty())
                    "${s.subject.take(60)}\n• ${s.reasons.first().take(80)}"
                else
                    s.subject.take(120),
                score = s.score,
                severity = when {
                    s.isPhishing && s.score >= 70 -> AnalysisItem.Severity.CRITICAL
                    s.score >= 40 -> AnalysisItem.Severity.WARNING
                    else -> AnalysisItem.Severity.OK
                },
                ts = s.ts,
                cta = "Open scanner",
                onTap = { openEmailScanner() }
            )
        }
        // Recent timeline events (up to 3)
        WardenState.timeline.toList().take(3).forEachIndexed { i, e ->
            out += AnalysisItem(
                id = "tl-${e.ts}-$i",
                type = "${e.pillar.label.uppercase()} EVENT",
                title = e.title,
                body = e.detail,
                score = if (e.title.contains("phish", true) || e.title.contains("stalker", true)) 85 else 30,
                severity = when {
                    e.title.contains("phish", true) -> AnalysisItem.Severity.CRITICAL
                    e.title.contains("clean", true) -> AnalysisItem.Severity.OK
                    else -> AnalysisItem.Severity.WARNING
                },
                ts = e.ts,
                cta = "Open ${e.pillar.label}",
                onTap = { openPillarDashboard(e.pillar) }
            )
        }

        // Always-present intro / status cards so the carousel never feels empty.
        if (out.size < 3) {
            out += AnalysisItem(
                id = "intro-email",
                type = "MESSAGE SCANNER",
                title = "Live email + chat phishing analysis",
                body = "Scores Gmail, Outlook, WhatsApp, Telegram & Signal notifications on-device the moment they arrive. Tap to enable.",
                score = 0,
                severity = AnalysisItem.Severity.INFO,
                ts = now,
                cta = "Open message scanner",
                onTap = { openEmailScanner() }
            )
            out += AnalysisItem(
                id = "intro-sms",
                type = "SMS SWEEP",
                title = "Scan every SMS in your inbox",
                body = "Reads your inbox once and scores each message for phishing keywords, suspicious links, and OTP-bait patterns.",
                score = 0,
                severity = AnalysisItem.Severity.INFO,
                ts = now,
                cta = "Open SMS sweep",
                onTap = { openPillarDashboard(Pillar.COMMS) }
            )
            out += AnalysisItem(
                id = "intro-apps",
                type = "STALKERWARE SCAN",
                title = "Inspect every installed app",
                body = "Detects spyware fingerprints, surveillance permissions, and apps with no launcher icon.",
                score = 0,
                severity = AnalysisItem.Severity.INFO,
                ts = now,
                cta = "Open app scan",
                onTap = { openPillarDashboard(Pillar.APPS) }
            )
        }
        return out.sortedByDescending { it.ts }.take(8)
    }

    /** Render the carousel content from a list of analysis items. */
    private fun renderCarousel(items: List<AnalysisItem>) {
        carouselItems = items
        carouselContent.removeAllViews()
        if (items.isEmpty()) {
            renderDots(0, 0)
            return
        }
        // Card width = screen width minus the 20dp side margins of its parent.
        val screenW = resources.displayMetrics.widthPixels
        val cardW = screenW - dp(40)
        carouselCardWidthPx = cardW
        for ((i, item) in items.withIndex()) {
            val card = buildAnalysisCard()
            bindAnalysisCard(card, item)
            val lp = LinearLayout.LayoutParams(cardW, dp(220))
            lp.marginEnd = if (i < items.size - 1) dp(12) else 0
            card.layoutParams = lp
            carouselContent.addView(card)
        }
        if (carouselIndex >= items.size) carouselIndex = 0
        carouselScroll.post { carouselScroll.scrollTo(carouselIndex * cardW, 0) }
        renderDots(items.size, carouselIndex)
    }

    private fun bindAnalysisCard(card: View, item: AnalysisItem) {
        val sevColor = when (item.severity) {
            AnalysisItem.Severity.CRITICAL -> ACCENT
            AnalysisItem.Severity.WARNING  -> AMBER
            AnalysisItem.Severity.OK       -> GREEN
            AnalysisItem.Severity.INFO     -> 0xFF60A5FA.toInt()
        }
        val typeLabel = card.findViewWithTag<TextView>("type")
        val titleView = card.findViewWithTag<TextView>("title")
        val bodyView  = card.findViewWithTag<TextView>("body")
        val scoreView = card.findViewWithTag<TextView>("score")
        val ctaView   = card.findViewWithTag<TextView>("cta")
        val tsView    = card.findViewWithTag<TextView>("ts")
        val stripe    = card.findViewWithTag<View>("stripe")
        val iconHost  = card.findViewWithTag<FrameLayout>("iconHost")

        stripe?.background = roundedRect(sevColor, dp(2))
        val iconRes = when {
            item.type.startsWith("EMAIL")       -> R.drawable.ic_mail
            item.type.contains("SMS")           -> R.drawable.ic_message
            item.type.contains("STALKER") || item.type.contains("APPS") -> R.drawable.ic_apps
            item.type.contains("NETWORK") || item.type.contains("TRACKER") -> R.drawable.ic_globe
            item.type.contains("MONEY")        -> R.drawable.ic_money
            item.type.contains("IDENTITY")     -> R.drawable.ic_lock
            item.type.contains("PHYSICAL")     -> R.drawable.ic_alert
            else                               -> R.drawable.ic_shield_check
        }
        iconHost?.removeAllViews()
        iconHost?.addView(ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(sevColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        })
        typeLabel?.text = item.type
        typeLabel?.setTextColor(sevColor)
        titleView?.text = item.title
        bodyView?.text = item.body
        scoreView?.text = if (item.score > 0) "${item.score}/100" else "—"
        scoreView?.setTextColor(sevColor)
        ctaView?.text = item.cta + "  ›"
        tsView?.text = "${dateFmt.format(Date(item.ts))} · ${timeFmt.format(Date(item.ts))}"
        card.setOnClickListener { item.onTap() }
    }

    /** The static layout of an analysis card — populated per-bind via tags. */
    private fun buildAnalysisCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(CARD, dp(20)).also { it.setStroke(1, CARD_BORDER) }
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        // Top row: icon badge + type label + score
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconHost = FrameLayout(this).apply {
            tag = "iconHost"
            background = roundedRect(SOFT_BADGE, dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        top.addView(iconHost)
        val typeLabel = TextView(this).apply {
            tag = "type"
            text = "ANALYSIS"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.14f
            setPadding(dp(10), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        top.addView(typeLabel)
        val scoreView = TextView(this).apply {
            tag = "score"
            text = "—"
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        }
        top.addView(scoreView)
        card.addView(top)

        // Severity stripe
        val stripe = View(this).apply {
            tag = "stripe"
            background = roundedRect(ACCENT, dp(2))
            val lp = LinearLayout.LayoutParams(dp(36), dp(3))
            lp.setMargins(0, dp(14), 0, 0)
            layoutParams = lp
        }
        card.addView(stripe)

        // Title
        card.addView(TextView(this).apply {
            tag = "title"
            text = "—"
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = -0.01f
            setPadding(0, dp(8), 0, 0)
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 2
            setLineSpacing(0f, 1.1f)
        })
        // Body
        card.addView(TextView(this).apply {
            tag = "body"
            text = "—"
            setTextColor(TEXT_MID)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(0f, 1.3f)
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 3
        })

        // Footer: timestamp + cta
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        footer.addView(TextView(this).apply {
            tag = "ts"
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        footer.addView(TextView(this).apply {
            tag = "cta"
            text = "Tap for details ›"
            setTextColor(BRAND)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(footer)
        return card
    }

    private fun renderDots(count: Int, current: Int) {
        carouselDots.removeAllViews()
        if (count <= 1) return
        for (i in 0 until count) {
            val active = i == current
            carouselDots.addView(View(this).apply {
                background = roundedRect(
                    if (active) ACCENT else 0xFF3A2024.toInt(),
                    dp(4)
                )
                val w = if (active) dp(20) else dp(6)
                val lp = LinearLayout.LayoutParams(w, dp(6))
                lp.setMargins(dp(3), 0, dp(3), 0)
                layoutParams = lp
            })
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // VIEW BUILDERS
    // ──────────────────────────────────────────────────────────────────────

    private fun buildView(): View {
        val frame = FrameLayout(this).apply { setBackgroundColor(BG) }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(96)) // space for nav
        }

        // Hero region with a subtle red gradient wash, then the rest of the body.
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = verticalGradient(BG_HERO, BG)
            setPadding(dp(20), dp(40), dp(20), dp(20))
        }
        hero.addView(buildTopBar())
        hero.addView(buildGreeting())
        hero.addView(buildCategoryPills())
        content.addView(hero)

        // Below the hero: padded normal content
        val below = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        below.addView(buildCarousel())
        below.addView(buildScoreCard())
        below.addView(buildPanicCard())
        below.addView(buildSystemStatusCard())
        below.addView(buildSensorAccessCard())
        below.addView(buildStatsRow())
        below.addView(buildQuickActions())
        below.addView(buildTimelineCard())
        content.addView(below)

        scroll.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        frame.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frame.addView(buildBottomNav(), FrameLayout.LayoutParams(MATCH_PARENT, dp(72), Gravity.BOTTOM))
        // Floating AI Assistant button — sits above the bottom nav, bottom-right.
        // Always available, regardless of how far the user has scrolled.
        frame.addView(buildAssistantFab())
        return frame
    }

    /**
     * Floating circular bot launcher pinned bottom-right above the nav bar.
     * Tap → opens [AssistantActivity]. Uses the brand blue with a subtle
     * outer glow ring + elevation so it reads as the primary call-to-action
     * without being alarming (the red Panic CTA handles the alarm tone).
     *
     * NOTE: the inner ImageView has NO imageTintList so the 3D gradient
     * fills baked into `ic_assistant.xml` render as intended. The FAB's
     * background circle is deliberately transparent — the gradient in the
     * vector drawable is the actual visual.
     */
    private fun buildAssistantFab(): View {
        val ring = FrameLayout(this).apply {
            // Soft blue halo behind the bot, gives it lift.
            background = circle(0x333B82F6)
            elevation = dp(10).toFloat()
        }
        val fab = ImageView(this).apply {
            setImageResource(R.drawable.ic_assistant)
            // No solid background, no tint: the vector drawable paints itself.
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dp(4)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            contentDescription = "Open AI Assistant"
            setOnClickListener { openAssistant() }
        }
        // Larger FAB — the 3D bot has enough detail to deserve the space.
        val ringSize = dp(72)
        val fabSize = dp(64)
        ring.layoutParams = FrameLayout.LayoutParams(
            ringSize, ringSize, Gravity.BOTTOM or Gravity.END,
        ).apply {
            rightMargin = dp(16)
            bottomMargin = dp(82) // 72dp nav + 10dp gap
        }
        ring.addView(fab, FrameLayout.LayoutParams(fabSize, fabSize, Gravity.CENTER))
        return ring
    }

    private fun buildTopBar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(iconBadge(R.drawable.ic_shield_check, BRAND, SOFT_BADGE, dp(40)))
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setPadding(dp(12), 0, 0, 0)
        }
        title.addView(TextView(this).apply {
            text = "Privacy Warden"
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = -0.01f
        })
        title.addView(TextView(this).apply {
            text = "On-device security"
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
        row.addView(title)

        powerToggle = TextView(this).apply {
            text = "ON"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = pillBg(BRAND, 18)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true; isFocusable = true
            setOnClickListener { onPrimaryTap() }
        }
        row.addView(powerToggle)
        val profile = iconBadge(R.drawable.ic_user, ACCENT, SOFT_BADGE, dp(40)).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(8)
        }
        row.addView(profile)
        return row
    }

    private fun buildGreeting(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(28), 0, dp(20))
        }
        greetingTitle = TextView(this).apply {
            text = "You're being watched."
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = -0.02f
            setLineSpacing(0f, 1.1f)
        }
        greetingSub = TextView(this).apply {
            text = "All defenses live · Tank online"
            setTextColor(TEXT_MID)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(8), 0, 0)
        }
        box.addView(greetingTitle)
        box.addView(greetingSub)
        return box
    }

    /** Horizontally-scrollable category pills (Calm-style). */
    private fun buildCategoryPills(): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(categoryPill(R.drawable.ic_message, "Messages", first = true) { openEmailScanner() })
        row.addView(categoryPill(R.drawable.ic_mail, "SMS") { openPillarDashboard(Pillar.COMMS) })
        row.addView(categoryPill(R.drawable.ic_apps, "Apps") { openPillarDashboard(Pillar.APPS) })
        row.addView(categoryPill(R.drawable.ic_globe, "Network") { openPillarDashboard(Pillar.NETWORK) })
        row.addView(categoryPill(R.drawable.ic_money, "Money") { openPillarDashboard(Pillar.MONEY) })
        row.addView(categoryPill(R.drawable.ic_lock, "Identity") { openPillarDashboard(Pillar.IDENTITY) })
        scroll.addView(row, ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        return scroll
    }

    private fun categoryPill(iconRes: Int, label: String, first: Boolean = false, onTap: () -> Unit): View {
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedRect(CARD, dp(20)).also { it.setStroke(1, CARD_BORDER) }
            setPadding(dp(12), dp(8), dp(14), dp(8))
            val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            lp.marginStart = if (first) 0 else dp(8)
            layoutParams = lp
            isClickable = true; isFocusable = true
            setOnClickListener { onTap() }
        }
        pill.addView(ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(BRAND)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        })
        pill.addView(TextView(this).apply {
            text = label
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
        })
        return pill
    }

    /** Sliding analysis carousel + dot indicators. */
    private fun buildCarousel(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.setMargins(0, dp(8), 0, dp(4))
            layoutParams = lp
        }
        // Header: "Live analysis" + small "auto" hint
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        header.addView(TextView(this).apply {
            text = "LIVE ANALYSIS"
            setTextColor(BRAND)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            letterSpacing = 0.18f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        header.addView(View(this).apply {
            background = roundedRect(ACCENT, dp(3))
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(6) }
        })
        header.addView(TextView(this).apply {
            text = "auto-sliding"
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        })
        box.addView(header)

        carouselContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        carouselScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // When the user pans we update the dot indicator and the snapshot index.
            setOnTouchListener { v, ev ->
                when (ev.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        handler.removeCallbacks(carouselAutoplay)
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        v.performClick()
                        snapToNearest()
                        handler.postDelayed(carouselAutoplay, 4_500)
                    }
                }
                false
            }
            addView(carouselContent, ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        box.addView(carouselScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        carouselDots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        box.addView(carouselDots)
        return box
    }

    /** After a user swipe, animate to the closest card so we keep alignment. */
    private fun snapToNearest() {
        if (carouselCardWidthPx <= 0 || carouselItems.isEmpty()) return
        val raw = carouselScroll.scrollX.toFloat() / carouselCardWidthPx
        val target = raw.toInt().coerceIn(0, carouselItems.size - 1)
        carouselIndex = target
        carouselScroll.smoothScrollTo(target * carouselCardWidthPx, 0)
        renderDots(carouselItems.size, target)
    }

    /** Privacy Score gradient card — blue brand gradient. Score number + ring
     *  still get coloured dynamically (green/amber/red) based on severity. */
    private fun buildScoreCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = gradientCard(BRAND_DEEP, BRAND_DARK, dp(20))
            setPadding(dp(20), dp(20), dp(20), dp(20))
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.setMargins(0, dp(12), 0, dp(8))
            layoutParams = lp
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_trending_up)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        })
        top.addView(TextView(this).apply {
            text = "Privacy Score"
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        card.addView(top)

        val numberRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(10), 0, 0)
        }
        scoreNumber = TextView(this).apply {
            text = "—"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = -0.03f
        }
        numberRow.addView(scoreNumber)
        numberRow.addView(TextView(this).apply {
            text = "/100"
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, 0, dp(8))
        })
        card.addView(numberRow)

        scoreSub = TextView(this).apply {
            text = "Computing…"
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(scoreSub)
        return card
    }

    private fun buildStatsRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.setMargins(0, dp(4), 0, dp(8))
            layoutParams = lp
        }
        statPhishingValue = TextView(this)
        statTrackersValue = TextView(this)
        statAppsValue = TextView(this)
        row.addView(statTile(R.drawable.ic_mail, "Phishing", statPhishingValue, leftMargin = 0))
        row.addView(statTile(R.drawable.ic_globe, "Trackers", statTrackersValue, leftMargin = dp(10)))
        row.addView(statTile(R.drawable.ic_apps, "Risky Apps", statAppsValue, leftMargin = dp(10)))
        return row
    }

    private fun statTile(iconRes: Int, label: String, valueView: TextView, leftMargin: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(CARD, dp(16)).also { it.setStroke(1, CARD_BORDER) }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            lp.setMargins(leftMargin, 0, 0, 0)
            layoutParams = lp
        }
        card.addView(iconBadge(iconRes, ACCENT, SOFT_BADGE, dp(34)))
        valueView.text = "0"
        valueView.setTextColor(TEXT_HI)
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        valueView.typeface = Typeface.DEFAULT_BOLD
        valueView.setPadding(0, dp(8), 0, 0)
        card.addView(valueView)
        card.addView(TextView(this).apply {
            text = label
            setTextColor(TEXT_MID)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(2), 0, 0)
        })
        return card
    }

    private fun buildQuickActions(): View {
        val card = card().apply { orientation = LinearLayout.VERTICAL }
        card.addView(TextView(this).apply {
            text = "Quick scans"
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        })
        card.addView(actionRow(R.drawable.ic_assistant, "AI Assistant",
            "Chat about your privacy · get a PDF report") { openAssistant() })
        card.addView(divider())
        card.addView(actionRow(R.drawable.ic_mail, "Message scanner",
            "Email + WhatsApp · Telegram · Signal phishing") { openEmailScanner() })
        card.addView(divider())
        card.addView(actionRow(R.drawable.ic_message, "SMS sweep",
            "Read & score every SMS in your inbox") { openPillarDashboard(Pillar.COMMS) })
        card.addView(divider())
        card.addView(actionRow(R.drawable.ic_apps, "Stalkerware scan",
            "Inspect every installed app for spyware") { openPillarDashboard(Pillar.APPS) })
        card.addView(divider())
        card.addView(actionRow(R.drawable.ic_globe, "Tracker firewall",
            "Block trackers at the DNS layer") { openPillarDashboard(Pillar.NETWORK) })
        card.addView(divider())
        card.addView(actionRow(R.drawable.ic_shield_check, "Your savings",
            "Data, battery & radio time saved") {
            startActivity(Intent(this, BenefitsActivity::class.java))
        })
        return card
    }

    private fun openAssistant() {
        startActivity(Intent(this, AssistantActivity::class.java))
    }

    private fun actionRow(iconRes: Int, title: String, body: String, onTap: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true; isFocusable = true
            setOnClickListener { onTap() }
        }
        row.addView(iconBadge(iconRes, ACCENT, SOFT_BADGE, dp(44)))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
            }
        }
        col.addView(TextView(this).apply {
            text = title
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(this).apply {
            text = body
            setTextColor(TEXT_MID)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(col)
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = android.content.res.ColorStateList.valueOf(TEXT_LO)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        })
        return row
    }

    /**
     * Compact system-health card: shows whether each protection layer is
     * actually doing its job. Surfaces "All systems healthy" prominently
     * when everything is wired up so the user knows the core is working.
     */
    private fun buildSystemStatusCard(): View {
        val card = card().apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "System status"
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        statusBadgeText = TextView(this).apply {
            text = "—"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = pillBg(GREEN, 10)
        }
        header.addView(statusBadgeText)
        card.addView(header)

        statusRowsHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        card.addView(statusRowsHolder)
        return card
    }

    private fun renderSystemStatus() {
        val checks = listOf(
            SystemCheck(
                "VPN firewall",
                "Tracker DNS inspection on every app",
                WardenState.running.get(),
            ),
            SystemCheck(
                "Tank backend",
                "On-device flow signing + ledger sync",
                WardenState.tankConnected.get(),
            ),
            SystemCheck(
                "Mic / camera watcher",
                "AudioManager + CameraManager callbacks",
                WardenState.sensorWatcherActive.get(),
            ),
            SystemCheck(
                "Message scanner",
                "Notification access for email + chat phishing scoring",
                isNotificationListenerEnabled(),
            ),
        )
        statusRowsHolder.removeAllViews()
        for (c in checks) statusRowsHolder.addView(systemCheckRow(c))
        val healthy = checks.count { it.ok }
        val total = checks.size
        statusBadgeText.text = when (healthy) {
            total -> "ALL HEALTHY"
            0 -> "OFFLINE"
            else -> "$healthy / $total OK"
        }
        val badgeColor = when {
            healthy == total -> GREEN
            healthy >= total - 1 -> AMBER
            else -> ACCENT
        }
        statusBadgeText.background = pillBg(badgeColor, 10)
    }

    private data class SystemCheck(val title: String, val detail: String, val ok: Boolean)

    private fun systemCheckRow(c: SystemCheck): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(View(this).apply {
            background = circle(if (c.ok) GREEN else AMBER)
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(12) }
        })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = c.title
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(this).apply {
            text = c.detail
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setLineSpacing(0f, 1.2f)
        })
        row.addView(col)
        row.addView(TextView(this).apply {
            text = if (c.ok) "OK" else "OFF"
            setTextColor(if (c.ok) GREEN else AMBER)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = roundedRect(SOFT_BADGE, dp(10))
        })
        return row
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = android.content.ComponentName(
            this,
            com.privacywarden.app.defense.WardenNotificationListener::class.java
        ).flattenToString()
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.split(":").any { it == cn }
    }

    /**
     * Live mic/camera access card. Watches every app on the device via
     * `AppOpsManager.startWatchingActive()` (API 30+) and renders a rolling
     * timeline of the last few activations. Requires CAMERA / RECORD_AUDIO
     * to be granted *to us* purely as the OS-mandated key for the watcher;
     * we never call any mic / camera APIs.
     */
    private fun buildSensorAccessCard(): View {
        val card = card().apply { orientation = LinearLayout.VERTICAL }

        // Header row: title + live dot + state label
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(iconBadge(R.drawable.ic_alert, ACCENT, SOFT_BADGE, dp(36)))
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setPadding(dp(12), 0, 0, 0)
            // Tapping the title force-restarts the watcher and reports back.
            // Useful as a diagnostic if the card seems stuck.
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val before = WardenState.sensorWatcherActive.get()
                val ok = SensorAccessWatcher.start(this@MainActivity)
                val msg = when {
                    !ok && !SensorAccessWatcher.hasMicPermission(this@MainActivity) &&
                        !SensorAccessWatcher.hasCameraPermission(this@MainActivity) ->
                        "Grant mic/camera access first (ENABLE button)"
                    ok && !before -> "Watcher attached · waiting for any app to use mic/camera"
                    ok            -> "Watcher re-attached · live"
                    else          -> "Could not attach watcher (OS may have rejected)"
                }
                toast(msg)
                refresh()
            }
        }
        titleBox.addView(TextView(this).apply {
            text = "Live mic & camera access"
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        })
        sensorStatusLabel = TextView(this).apply {
            text = "Tap to enable on-device watcher"
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        titleBox.addView(sensorStatusLabel)
        header.addView(titleBox)
        sensorStatusDot = View(this).apply {
            background = circle(AMBER)
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
        }
        header.addView(sensorStatusDot)
        card.addView(header)

        // Counters row (today's mic / camera totals)
        sensorCounters = TextView(this).apply {
            setTextColor(TEXT_MID)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(10), 0, dp(4))
        }
        card.addView(sensorCounters)

        // Optional: enrich per-event attribution by also granting RECORD_AUDIO
        // + CAMERA. Hidden once both are granted. Not required for the card
        // to work — the AudioManager + CameraManager callbacks fire without
        // any runtime permissions.
        sensorEnableBtn = Button(this).apply {
            text = "ENRICH ATTRIBUTION (OPTIONAL)"
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = pillBg(ACCENT, 12)
            stateListAnimator = null
            isAllCaps = false
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, dp(40))
            lp.setMargins(0, dp(10), 0, dp(2))
            layoutParams = lp
            setOnClickListener {
                sensorPermLauncher.launch(
                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                )
            }
        }
        card.addView(sensorEnableBtn)

        // Usage Stats permission for better attribution
        val usageStatsBtn = Button(this).apply {
            text = "IMPROVE APP DETECTION"
            setTextColor(TEXT_HI)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = pillBg(SOFT_BADGE, 12).also { it.setStroke(1, CARD_BORDER) }
            stateListAnimator = null
            isAllCaps = false
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, dp(40))
            lp.setMargins(0, dp(8), 0, dp(2))
            layoutParams = lp
            setOnClickListener {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                startActivity(intent)
                toast("Enable Privacy Warden in Usage Access settings for better app attribution")
            }
        }
        card.addView(usageStatsBtn)

        // Honest disclosure.
        card.addView(TextView(this).apply {
            text = "Detection uses AudioManager + CameraManager callbacks — no permissions needed. Privacy Warden never opens the mic or camera itself. Enable Usage Access above for precise app names."
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(8), 0, dp(4))
        })

        // Section header for the rolling event list.
        card.addView(TextView(this).apply {
            text = "RECENT ACTIVATIONS"
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            letterSpacing = 0.14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(4))
        })
        sensorEventsHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(sensorEventsHolder)
        return card
    }

    private fun renderSensorAccess() {
        // The watcher is layered: AudioManager + CameraManager callbacks need
        // no runtime permissions and work on every Android 5+ device. Try to
        // auto-attach if we somehow got dropped (e.g., process was killed).
        if (!WardenState.sensorWatcherActive.get()) {
            runCatching { SensorAccessWatcher.start(this) }
        }
        val active = WardenState.sensorWatcherActive.get()
        val canMic = SensorAccessWatcher.hasMicPermission(this)
        val canCam = SensorAccessWatcher.hasCameraPermission(this)

        // Permissions only buy us *additional* per-event AppOps attribution on
        // builds where it works. The card never *requires* them anymore.
        val (dotColor, label, btnVisible) = when {
            !active -> Triple(AMBER, "Watcher idle · tap title to retry", false)
            canMic && canCam -> Triple(GREEN, "Live · watching every app's mic & camera", false)
            else -> Triple(GREEN, "Live · watching mic & camera (enrich attribution: grant access)", true)
        }
        sensorStatusDot.background = circle(dotColor)
        sensorStatusLabel.text = label
        sensorEnableBtn.visibility = if (btnVisible) View.VISIBLE else View.GONE

        val all = WardenState.sensorAccesses.toList()
        val today = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        val todayList = all.filter { it.startTs >= today }
        val micCount = todayList.count { it.kind == WardenState.SensorAccess.Kind.MIC }
        val camCount = todayList.count { it.kind == WardenState.SensorAccess.Kind.CAMERA }
        sensorCounters.text = "Mic: $micCount  ·  Camera: $camCount  ·  last 24h"

        sensorEventsHolder.removeAllViews()
        if (all.isEmpty()) {
            sensorEventsHolder.addView(TextView(this).apply {
                text = if (active)
                    "No app has used the mic or camera since the watcher started. Take a video or open a call to test."
                else
                    "Once enabled, every mic / camera activation will appear here in real time."
                setTextColor(TEXT_LO)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setLineSpacing(0f, 1.3f)
                setPadding(0, dp(6), 0, dp(2))
            })
            return
        }
        for (e in all.take(6)) sensorEventsHolder.addView(sensorEventRow(e))
    }

    private fun sensorEventRow(e: WardenState.SensorAccess): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val isMic = e.kind == WardenState.SensorAccess.Kind.MIC
        val color = if (e.active) ACCENT else if (isMic) AMBER else INFO_BLUE
        // Left dot indicating live vs done
        row.addView(View(this).apply {
            background = circle(color)
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) }
        })
        // Small icon badge (mic or camera) in front of the label
        row.addView(ImageView(this).apply {
            setImageResource(if (isMic) R.drawable.ic_mic else R.drawable.ic_camera)
            imageTintList = android.content.res.ColorStateList.valueOf(color)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                .apply { marginEnd = dp(10) }
        })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = e.label
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxLines = 1
        })
        col.addView(TextView(this).apply {
            text = formatSensorTime(e)
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(col)
        // Right side: ON badge while active, duration pill when finished
        val tail = TextView(this).apply {
            text = if (e.active) "● ON" else formatDuration(e.durationMs)
            setTextColor(if (e.active) ACCENT else TEXT_MID)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = roundedRect(if (e.active) 0xFF2A0F12.toInt() else SOFT_BADGE, dp(10))
        }
        row.addView(tail)
        return row
    }

    private fun formatSensorTime(e: WardenState.SensorAccess): String {
        val start = timeFmt.format(java.util.Date(e.startTs))
        return if (e.active) "started $start · still active"
        else "$start → ${timeFmt.format(java.util.Date(e.endTs))}"
    }

    private fun formatDuration(ms: Long): String = when {
        ms < 1_000 -> "<1s"
        ms < 60_000 -> "${ms / 1000}s"
        ms < 3_600_000 -> "${ms / 60_000}m"
        else -> "${ms / 3_600_000}h"
    }

    private fun buildTimelineCard(): View {
        val card = card().apply { orientation = LinearLayout.VERTICAL }
        card.addView(TextView(this).apply {
            text = "Recent activity"
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(4))
        })
        timelineHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        card.addView(timelineHolder)
        return card
    }

    // ── bottom nav ──────────────────────────────────────────────────────────

    private fun buildBottomNav(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedTop(CARD, dp(20)).also { it.setStroke(1, CARD_BORDER) }
            elevation = dp(8).toFloat()
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        bar.addView(navItem(R.drawable.ic_home, "Home", true) { /* already home */ })
        bar.addView(navItem(R.drawable.ic_message, "Messages", false) { openEmailScanner() })
        bar.addView(navItem(R.drawable.ic_mail, "SMS", false) { openPillarDashboard(Pillar.COMMS) })
        bar.addView(navItem(R.drawable.ic_apps, "Apps", false) { openPillarDashboard(Pillar.APPS) })
        bar.addView(navItem(R.drawable.ic_globe, "Network", false) { openPillarDashboard(Pillar.NETWORK) })
        return bar
    }

    private fun navItem(iconRes: Int, label: String, active: Boolean, onTap: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
            isClickable = true; isFocusable = true
            setOnClickListener { onTap() }
        }
        val color = if (active) BRAND else TEXT_LO
        box.addView(ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(color)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        })
        box.addView(TextView(this).apply {
            text = label
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        })
        return box
    }

    // ── nav helpers ─────────────────────────────────────────────────────────
    private fun openEmailScanner() {
        startActivity(Intent(this, EmailScannerActivity::class.java))
    }

    private fun openPillarDashboard(p: Pillar) {
        startActivity(
            Intent(this, PillarDashboardActivity::class.java)
                .putExtra(PillarDashboardActivity.EXTRA_PILLAR, p.name)
        )
    }

    // ── primitive helpers ───────────────────────────────────────────────────
    private fun card(): LinearLayout = LinearLayout(this).apply {
        background = roundedRect(CARD, dp(16)).also { it.setStroke(1, CARD_BORDER) }
        setPadding(dp(16), dp(16), dp(16), dp(16))
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        lp.setMargins(0, dp(8), 0, dp(8))
        layoutParams = lp
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(CARD_BORDER)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1))
    }

    private fun iconBadge(iconRes: Int, iconColor: Int, bg: Int, size: Int): FrameLayout {
        val frame = FrameLayout(this).apply {
            background = roundedRect(bg, dp(12))
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        val iv = ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(iconColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        val pad = (size * 0.42f).toInt()
        iv.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER)
        iv.setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        frame.addView(iv)
        return frame
    }

    private fun roundedRect(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
        }

    private fun roundedTop(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            val r = radius.toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(color)
        }

    private fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun pillBg(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(color)
        }

    private fun gradientCard(start: Int, end: Int, radius: Int): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(start, end)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
        }

    private fun verticalGradient(top: Int, bottom: Int): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(top, bottom)
        )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    @Suppress("unused")
    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    // ──────────────────────────────────────────────────────────────────────
    // PANIC BUTTON
    //   When ON, RuleStore consults PanicBlocklist and synthesises NXDOMAIN
    //   for every known tracker / analytics / ad domain. First-party traffic
    //   for normal apps (banking, WhatsApp, Gmail, etc.) is unaffected.
    // ──────────────────────────────────────────────────────────────────────

    private fun buildPanicCard(): View {
        panicCardRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(CARD, dp(16)).also { it.setStroke(1, CARD_BORDER) }
            setPadding(dp(18), dp(16), dp(18), dp(16))
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.setMargins(0, dp(8), 0, dp(8))
            layoutParams = lp
        }

        // Header row: shield icon + title
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconHost = FrameLayout(this).apply {
            background = roundedRect(SOFT_BADGE, dp(12))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        panicShieldIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_shield_check)
            imageTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        iconHost.addView(panicShieldIcon)
        header.addView(iconHost)

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setPadding(dp(12), 0, 0, 0)
        }
        panicTitle = TextView(this).apply {
            text = "PANIC MODE"
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
        }
        panicSubtitle = TextView(this).apply {
            text = "Block every tracker. Keep apps working."
            setTextColor(TEXT_MID)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), 0, 0)
        }
        titleBox.addView(panicTitle)
        titleBox.addView(panicSubtitle)
        header.addView(titleBox)
        panicCardRoot.addView(header)

        // Big tap target
        panicBigButton = Button(this).apply {
            text = "ENGAGE PANIC MODE"
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = pillBg(ACCENT_DEEP, 14)
            stateListAnimator = null
            isAllCaps = false
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, dp(54))
            lp.setMargins(0, dp(14), 0, 0)
            layoutParams = lp
            setOnClickListener { onPanicTap() }
        }
        panicCardRoot.addView(panicBigButton)

        // Live blocked counter
        panicCounter = TextView(this).apply {
            text = "Tap to block trackers · banking & messaging keep working"
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        panicCardRoot.addView(panicCounter)

        // Per-app quarantine: surgical, "this one app is acting weird" action
        Button(this).apply {
            text = "QUARANTINE A SPECIFIC APP"
            setTextColor(TEXT_HI)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = pillBg(SOFT_BADGE, 12).also { it.setStroke(1, CARD_BORDER) }
            stateListAnimator = null
            isAllCaps = false
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, dp(42))
            lp.setMargins(0, dp(10), 0, 0)
            layoutParams = lp
            setOnClickListener {
                startActivity(Intent(this@MainActivity, QuarantineActivity::class.java))
            }
        }.also { panicCardRoot.addView(it) }

        return panicCardRoot
    }

    private fun onPanicTap() {
        val now = WardenState.panicMode.get()
        if (!now) {
            // ENGAGE: panic mode requires the VPN to be running so DNS is intercepted
            if (!WardenState.running.get()) {
                toast("Turn on Privacy Warden first (top-right ON)")
                onPrimaryTap()
                return
            }
            WardenState.panicMode.set(true)
            WardenState.panicSince.set(System.currentTimeMillis())
            WardenState.panicBlockedCount.set(0)
            WardenState.pushEvent(
                WardenState.TimelineEvent(
                    pillar = WardenState.Pillar.NETWORK,
                    title = "Panic mode engaged",
                    detail = "All known trackers, ads & analytics now blocked",
                )
            )
            toast("Panic mode ON — every tracker is now sinkholed")
        } else {
            WardenState.panicMode.set(false)
            val blocked = WardenState.panicBlockedCount.get()
            WardenState.pushEvent(
                WardenState.TimelineEvent(
                    pillar = WardenState.Pillar.NETWORK,
                    title = "Panic mode disengaged",
                    detail = "$blocked tracker request(s) blocked while active",
                )
            )
            toast("Panic mode OFF · $blocked trackers were blocked")
        }
        renderPanic()
    }

    private fun renderPanic() {
        val active = WardenState.panicMode.get()
        if (active) {
            // Loud, obvious "shields up" state
            panicCardRoot.background = roundedRect(0xFF2A0F12.toInt(), dp(16))
                .also { it.setStroke(dp(2), ACCENT) }
            panicTitle.text = "PANIC MODE · ACTIVE"
            panicTitle.setTextColor(ACCENT)
            panicShieldIcon.imageTintList =
                android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            panicShieldIcon.background = circle(ACCENT_DEEP)
            panicBigButton.text = "DISENGAGE"
            panicBigButton.background = pillBg(0xFF3A2024.toInt(), 14)
            panicBigButton.setTextColor(0xFFFFFFFF.toInt())
            val n = WardenState.panicBlockedCount.get()
            val secs = ((System.currentTimeMillis() - WardenState.panicSince.get()) / 1000L)
                .coerceAtLeast(0L)
            panicSubtitle.text = "Every known tracker is being sinkholed."
            panicCounter.text = "$n trackers blocked  ·  active for ${formatPanicDuration(secs)}"
            panicCounter.setTextColor(ACCENT)
        } else {
            panicCardRoot.background = roundedRect(CARD, dp(16))
                .also { it.setStroke(1, CARD_BORDER) }
            panicTitle.text = "PANIC MODE"
            panicTitle.setTextColor(TEXT_HI)
            panicShieldIcon.imageTintList =
                android.content.res.ColorStateList.valueOf(ACCENT)
            panicShieldIcon.background = null
            panicBigButton.text = "ENGAGE PANIC MODE"
            panicBigButton.background = pillBg(ACCENT_DEEP, 14)
            panicSubtitle.text = "Block every tracker. Keep apps working."
            panicCounter.text =
                "Tap to sinkhole all known trackers · banking & messaging keep working"
            panicCounter.setTextColor(TEXT_LO)
        }
    }

    private fun formatPanicDuration(secs: Long): String = when {
        secs < 60 -> "${secs}s"
        secs < 3600 -> "${secs / 60}m ${secs % 60}s"
        else -> "${secs / 3600}h ${(secs % 3600) / 60}m"
    }
}
