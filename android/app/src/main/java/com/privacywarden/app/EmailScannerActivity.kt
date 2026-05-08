package com.privacywarden.app

import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.privacywarden.app.defense.PhishingDetector
import com.privacywarden.app.defense.WardenNotificationListener
import com.privacywarden.app.ui.BarChartView
import com.privacywarden.app.ui.SparklineView
import com.privacywarden.app.vpn.WardenState
import com.privacywarden.app.vpn.WardenState.Pillar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dark-red Email Scanner — same look + sliding carousel as the home screen.
 *
 *   - Top bar with back chevron + title
 *   - Sliding carousel of recent email scans (one per page, auto-advances)
 *   - Live notification-listener status + grant CTA
 *   - Stats row + sparkline + score-distribution bar chart
 *   - Manual paste scan
 *   - Bottom nav (Messages tab active)
 */
class EmailScannerActivity : ComponentActivity() {

    // ── palette (must match MainActivity) ─────────────────────────
    // Blue = chrome / safe, Red = threat signals only.
    private val BG          = 0xFF08090C.toInt()
    private val BG_HERO     = 0xFF0E1A2E.toInt()     // navy hero wash
    private val CARD        = 0xFF101420.toInt()
    private val CARD_BORDER = 0xFF1C2438.toInt()
    private val SOFT_BADGE  = 0xFF11213D.toInt()
    private val TEXT_HI     = 0xFFF2F5F7.toInt()
    private val TEXT_MID    = 0xFF9AA3B2.toInt()
    private val TEXT_LO     = 0xFF5F6A7D.toInt()
    private val ACCENT      = 0xFFEF4444.toInt()     // red-500 (phishing)
    private val BRAND       = 0xFF3B82F6.toInt()     // blue-500 (chrome)
    private val AMBER       = 0xFFF59E0B.toInt()
    private val GREEN       = 0xFF10B981.toInt()
    private val INFO_BLUE   = 0xFF60A5FA.toInt()

    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
    private val dateFmt = SimpleDateFormat("MMM d", Locale.US)

    private lateinit var listenerStatusDot: View
    private lateinit var listenerStatusLabel: TextView
    private lateinit var grantBtn: Button

    private lateinit var totalScannedView: TextView
    private lateinit var phishingCountView: TextView
    private lateinit var cleanCountView: TextView
    private lateinit var avgScoreView: TextView

    private lateinit var sparkline: SparklineView
    private lateinit var bar: BarChartView

    private lateinit var carouselScroll: HorizontalScrollView
    private lateinit var carouselContent: LinearLayout
    private lateinit var carouselDots: LinearLayout
    private var carouselSize = 0
    private var carouselIndex = 0
    private var carouselCardWidthPx = 0
    private var lastSnapshotKey = ""

    private lateinit var resultsHolder: LinearLayout
    private lateinit var pasteInput: EditText

    // ── lifecycle ───────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION") window.statusBarColor = BG
        @Suppress("DEPRECATION") window.navigationBarColor = CARD
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        try {
            setContentView(buildView())
        } catch (t: Throwable) {
            setContentView(buildCrashScreen(t))
        }
    }

    override fun onResume() {
        super.onResume()
        // If notification access is already granted, retroactively scan every
        // email currently sitting on the status bar so opening this screen
        // shows real scores immediately — no need to wait for new mail.
        runCatching {
            val added = WardenNotificationListener.scanActiveInbox()
            if (added > 0) toast("Scanned $added emails from your inbox")
        }
        ticker.run()
        carouselAutoplay.run()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(carouselAutoplay)
    }

    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1500) }
    }

    private val carouselAutoplay = object : Runnable {
        override fun run() {
            if (carouselSize > 1 && carouselCardWidthPx > 0) {
                carouselIndex = (carouselIndex + 1) % carouselSize
                carouselScroll.smoothScrollTo(carouselIndex * carouselCardWidthPx, 0)
                renderDots(carouselSize, carouselIndex)
            }
            handler.postDelayed(this, 4500)
        }
    }

    // ── refresh ────────────────────────────────────────────────────────────

    private fun refresh() {
        val granted = isNotificationListenerEnabled()
        listenerStatusDot.background = circle(if (granted) GREEN else AMBER)
        listenerStatusLabel.text = if (granted) "Live · monitoring email notifications"
        else "Tap below to enable live email monitoring"
        grantBtn.visibility = if (granted) View.GONE else View.VISIBLE

        val all = WardenState.emailScans.toList()
        totalScannedView.text = all.size.toString()
        val phishing = all.count { it.isPhishing }
        phishingCountView.text = phishing.toString()
        cleanCountView.text = (all.size - phishing).toString()
        avgScoreView.text = if (all.isEmpty()) "—" else (all.sumOf { it.score } / all.size).toString()

        val recent = all.take(20).reversed()
        sparkline.values = IntArray(recent.size) { recent[it].score }

        val buckets = IntArray(5)
        for (s in all) {
            val idx = (s.score / 20).coerceAtMost(4)
            buckets[idx]++
        }
        bar.data = listOf(
            "0-20" to buckets[0],
            "21-40" to buckets[1],
            "41-60" to buckets[2],
            "61-80" to buckets[3],
            "81-100" to buckets[4],
        )

        // Carousel of most recent scans (only re-render if data changed).
        val carouselSource = all.take(8)
        val key = carouselSource.joinToString("|") { "${it.ts}-${it.score}" }
        if (key != lastSnapshotKey) {
            lastSnapshotKey = key
            renderCarousel(carouselSource)
        }

        // Recent results list
        resultsHolder.removeAllViews()
        if (all.isEmpty()) resultsHolder.addView(emptyState())
        else for (s in all.take(20)) resultsHolder.addView(resultRow(s))
    }

    // ── carousel ───────────────────────────────────────────────────────────

    private fun renderCarousel(items: List<WardenState.EmailScan>) {
        carouselContent.removeAllViews()
        carouselSize = items.size
        if (items.isEmpty()) {
            // Fallback intro card so the carousel never feels empty.
            val intro = buildEmailCard()
            bindEmptyCarouselCard(intro)
            val w = resources.displayMetrics.widthPixels - dp(40)
            carouselCardWidthPx = w
            intro.layoutParams = LinearLayout.LayoutParams(w, dp(220))
            carouselContent.addView(intro)
            carouselSize = 1
            renderDots(1, 0)
            return
        }
        val screenW = resources.displayMetrics.widthPixels
        val cardW = screenW - dp(40)
        carouselCardWidthPx = cardW
        for ((i, scan) in items.withIndex()) {
            val card = buildEmailCard()
            bindCarouselCard(card, scan)
            val lp = LinearLayout.LayoutParams(cardW, dp(220))
            lp.marginEnd = if (i < items.size - 1) dp(12) else 0
            card.layoutParams = lp
            carouselContent.addView(card)
        }
        if (carouselIndex >= items.size) carouselIndex = 0
        carouselScroll.post { carouselScroll.scrollTo(carouselIndex * cardW, 0) }
        renderDots(items.size, carouselIndex)
    }

    private fun snapToNearest() {
        if (carouselCardWidthPx <= 0 || carouselSize == 0) return
        val raw = carouselScroll.scrollX.toFloat() / carouselCardWidthPx
        val target = raw.toInt().coerceIn(0, carouselSize - 1)
        carouselIndex = target
        carouselScroll.smoothScrollTo(target * carouselCardWidthPx, 0)
        renderDots(carouselSize, target)
    }

    private fun renderDots(count: Int, current: Int) {
        carouselDots.removeAllViews()
        if (count <= 1) return
        for (i in 0 until count) {
            val active = i == current
            carouselDots.addView(View(this).apply {
                background = roundedRect(if (active) ACCENT else 0xFF3A2024.toInt(), dp(4))
                val w = if (active) dp(20) else dp(6)
                val lp = LinearLayout.LayoutParams(w, dp(6))
                lp.setMargins(dp(3), 0, dp(3), 0)
                layoutParams = lp
            })
        }
    }

    private fun buildEmailCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(CARD, dp(20)).also { it.setStroke(1, CARD_BORDER) }
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
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
        top.addView(TextView(this).apply {
            tag = "type"
            text = "MESSAGE ANALYSIS"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.14f
            setPadding(dp(10), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        top.addView(TextView(this).apply {
            tag = "score"
            text = "—"
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(top)
        card.addView(View(this).apply {
            tag = "stripe"
            background = roundedRect(ACCENT, dp(2))
            val lp = LinearLayout.LayoutParams(dp(36), dp(3))
            lp.setMargins(0, dp(14), 0, 0)
            layoutParams = lp
        })
        card.addView(TextView(this).apply {
            tag = "title"
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = -0.01f
            setPadding(0, dp(8), 0, 0)
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 2
            setLineSpacing(0f, 1.1f)
        })
        card.addView(TextView(this).apply {
            tag = "body"
            setTextColor(TEXT_MID)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(0f, 1.3f)
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 3
        })
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
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(footer)
        return card
    }

    private fun bindCarouselCard(card: View, scan: WardenState.EmailScan) {
        val sevColor = when {
            scan.isPhishing && scan.score >= 70 -> ACCENT
            scan.score >= 40 -> AMBER
            else -> GREEN
        }
        card.findViewWithTag<View>("stripe")?.background = roundedRect(sevColor, dp(2))
        val iconHost = card.findViewWithTag<FrameLayout>("iconHost")
        iconHost?.removeAllViews()
        iconHost?.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_mail)
            imageTintList = android.content.res.ColorStateList.valueOf(sevColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        })
        card.findViewWithTag<TextView>("type")?.let {
            it.text = "MESSAGE ANALYSIS · ${scan.source.uppercase()}"
            it.setTextColor(sevColor)
        }
        card.findViewWithTag<TextView>("score")?.let {
            it.text = "${scan.score}/100"
            it.setTextColor(sevColor)
        }
        card.findViewWithTag<TextView>("title")?.text =
            scan.sender.ifBlank { "(unknown sender)" }
        card.findViewWithTag<TextView>("body")?.text = buildString {
            append(scan.subject.ifBlank { "(no subject)" })
            if (scan.reasons.isNotEmpty()) append("\n• ${scan.reasons.first()}")
        }
        card.findViewWithTag<TextView>("ts")?.text =
            "${dateFmt.format(Date(scan.ts))} · ${timeFmt.format(Date(scan.ts))}"
        card.findViewWithTag<TextView>("cta")?.text =
            if (scan.isPhishing) "PHISHING · review now ›" else "Clean · view details ›"
        card.setOnClickListener { showEmailDetailDialog(scan) }
    }

    /**
     * Full-screen scoring breakdown for a scanned email. Shows sender, subject,
     * verdict, final score, and every reason the detector flagged it for. All
     * on-device; no network fetch.
     */
    private fun showEmailDetailDialog(scan: WardenState.EmailScan) {
        val sevColor = when {
            scan.isPhishing && scan.score >= 70 -> ACCENT
            scan.score >= 40 -> AMBER
            else -> GREEN
        }
        val verdict = when {
            scan.isPhishing && scan.score >= 70 -> "HIGH RISK PHISHING"
            scan.isPhishing -> "SUSPICIOUS"
            scan.score >= 40 -> "WATCH"
            else -> "CLEAN"
        }
        val msg = buildString {
            append("Verdict: "); append(verdict); append('\n')
            append("Score:   "); append(scan.score); append(" / 100\n")
            append("Source:  "); append(scan.source); append('\n')
            append("When:    ")
            append(dateFmt.format(Date(scan.ts))); append(" · ")
            append(timeFmt.format(Date(scan.ts))); append("\n\n")
            append("FROM\n"); append(scan.sender.ifBlank { "(unknown sender)" }); append("\n\n")
            append("SUBJECT\n"); append(scan.subject.ifBlank { "(no subject)" }); append("\n\n")
            if (scan.reasons.isNotEmpty()) {
                append("WHY THIS SCORE\n")
                for (r in scan.reasons) { append("• "); append(r); append('\n') }
            } else {
                append("No phishing signals detected.\n")
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("$verdict · ${scan.score}/100")
            .setMessage(msg)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun bindEmptyCarouselCard(card: View) {
        card.findViewWithTag<View>("stripe")?.background = roundedRect(INFO_BLUE, dp(2))
        val iconHost = card.findViewWithTag<FrameLayout>("iconHost")
        iconHost?.removeAllViews()
        iconHost?.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_mail)
            imageTintList = android.content.res.ColorStateList.valueOf(INFO_BLUE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        })
        card.findViewWithTag<TextView>("type")?.let {
            it.text = "MESSAGE SCANNER"; it.setTextColor(INFO_BLUE)
        }
        card.findViewWithTag<TextView>("score")?.let {
            it.text = "—"; it.setTextColor(INFO_BLUE)
        }
        card.findViewWithTag<TextView>("title")?.text = "Live email + chat phishing analysis"
        card.findViewWithTag<TextView>("body")?.text =
            "Scores Gmail, Outlook, WhatsApp, Telegram & Signal notifications on-device the moment they arrive. Tap below to enable."
        card.findViewWithTag<TextView>("ts")?.text = "Awaiting first scan"
        card.findViewWithTag<TextView>("cta")?.text = "Enable monitoring ›"
        card.setOnClickListener { openNotificationListenerSettings() }
    }

    // ── view tree ──────────────────────────────────────────────────────────

    private fun buildView(): View {
        val frame = FrameLayout(this).apply { setBackgroundColor(BG) }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(96))
        }

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = verticalGradient(BG_HERO, BG)
            setPadding(dp(20), dp(40), dp(20), dp(20))
        }
        hero.addView(buildTopBar())
        hero.addView(buildGreeting())
        content.addView(hero)

        val below = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        below.addView(buildCarousel())
        below.addView(buildListenerCard())
        below.addView(buildStatsCard())
        below.addView(buildPasteCard())
        below.addView(buildResultsCard())
        content.addView(below)

        scroll.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        frame.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frame.addView(buildBottomNav(), FrameLayout.LayoutParams(MATCH_PARENT, dp(72), Gravity.BOTTOM))
        return frame
    }

    private fun buildTopBar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Back button as icon badge
        val back = iconBadge(R.drawable.ic_arrow_left, BRAND, SOFT_BADGE, dp(40)).apply {
            isClickable = true; isFocusable = true
            setOnClickListener { finish() }
        }
        row.addView(back)
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setPadding(dp(12), 0, 0, 0)
        }
        title.addView(TextView(this).apply {
            text = "Message Scanner"
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = -0.01f
        })
        title.addView(TextView(this).apply {
            text = "On-device phishing analysis"
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
        row.addView(title)
        // Message icon (decorative)
        row.addView(iconBadge(R.drawable.ic_message, BRAND, SOFT_BADGE, dp(40)))
        return row
    }

    private fun buildGreeting(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(28), 0, dp(20))
        }
        box.addView(TextView(this).apply {
            text = "Every message, scored."
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = -0.02f
            setLineSpacing(0f, 1.1f)
        })
        box.addView(TextView(this).apply {
            text = "Reads Gmail, Outlook, WhatsApp, Telegram, Signal, Messenger, Instagram and SMS notifications and runs the on-device phishing detector. Nothing leaves your phone."
            setTextColor(TEXT_MID)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, 0)
            setLineSpacing(0f, 1.3f)
        })
        return box
    }

    private fun buildCarousel(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.setMargins(0, dp(8), 0, dp(4))
            layoutParams = lp
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        header.addView(TextView(this).apply {
            text = "RECENT ANALYSIS"
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

        carouselContent = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        carouselScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> handler.removeCallbacks(carouselAutoplay)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.performClick()
                        snapToNearest()
                        handler.postDelayed(carouselAutoplay, 4500)
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

    private fun buildListenerCard(): View {
        val card = card()
        card.orientation = LinearLayout.VERTICAL

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listenerStatusDot = View(this).apply {
            background = circle(AMBER)
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
        }
        listenerStatusLabel = TextView(this).apply {
            text = "Tap below to enable live message monitoring"
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), 0, 0, 0)
        }
        statusRow.addView(listenerStatusDot)
        statusRow.addView(listenerStatusLabel)
        card.addView(statusRow)

        card.addView(TextView(this).apply {
            text = "Privacy Warden reads your message notifications via Android's Notification Access and scores each on-device. From the moment monitoring is enabled, every new message across Gmail, Outlook, WhatsApp, Telegram, Signal, Messenger, Instagram, Discord, Slack, Teams and SMS is scored live.\n\nNote: Android does not let third-party apps read previously-dismissed messages. Tap SCAN NOW to score every message currently in your status bar. For messages you've already swiped away, only the on-device scores from past sessions remain."
            setTextColor(TEXT_MID)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(8), 0, dp(12))
        })

        grantBtn = Button(this).apply {
            text = "ENABLE MESSAGE MONITORING"
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = pillBg(BRAND, 12)
            stateListAnimator = null
            isAllCaps = false
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, dp(46))
            layoutParams = lp
            setOnClickListener { openNotificationListenerSettings() }
        }
        card.addView(grantBtn)

        Button(this).apply {
            text = "SCAN NOW"
            setTextColor(TEXT_HI)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = pillBg(SOFT_BADGE, 12).also { it.setStroke(1, CARD_BORDER) }
            stateListAnimator = null
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, dp(42))
            lp.setMargins(0, dp(8), 0, 0)
            layoutParams = lp
            setOnClickListener {
                val added = WardenNotificationListener.scanActiveInbox()
                if (added > 0) toast("Scanned $added message(s)")
                else toast("No new messages to score. Grant Notification access first if you haven't.")
            }
        }.also { card.addView(it) }
        return card
    }

    private fun buildStatsCard(): View {
        val card = card().apply { orientation = LinearLayout.VERTICAL }
        card.addView(TextView(this).apply {
            text = "Score overview"
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        })
        val statRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }
        totalScannedView = statValueLarge()
        phishingCountView = statValueLarge()
        cleanCountView = statValueLarge()
        avgScoreView = statValueLarge()
        statRow.addView(statBlock("SCANNED", totalScannedView, INFO_BLUE))
        statRow.addView(statBlock("PHISHING", phishingCountView, ACCENT))
        statRow.addView(statBlock("CLEAN", cleanCountView, GREEN))
        statRow.addView(statBlock("AVG", avgScoreView, AMBER))
        card.addView(statRow)

        card.addView(TextView(this).apply {
            text = "RECENT SCORES"
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            letterSpacing = 0.14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(4))
        })
        sparkline = SparklineView(this).apply {
            lineColor = ACCENT
            fillColor = 0x33EF4444.toInt()
        }
        card.addView(sparkline, LinearLayout.LayoutParams(MATCH_PARENT, dp(80)))

        card.addView(TextView(this).apply {
            text = "SCORE DISTRIBUTION"
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            letterSpacing = 0.14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(4))
        })
        bar = BarChartView(this).apply { barColor = ACCENT }
        card.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, dp(140)))
        return card
    }

    private fun statBlock(label: String, valueView: TextView, accent: Int): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(View(this).apply {
            background = roundedRect(accent, dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(2))
        })
        valueView.setTextColor(TEXT_HI)
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        valueView.typeface = Typeface.DEFAULT_BOLD
        valueView.gravity = Gravity.CENTER
        valueView.setPadding(0, dp(4), 0, 0)
        col.addView(valueView)
        col.addView(TextView(this).apply {
            text = label
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            letterSpacing = 0.10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        })
        return col
    }

    private fun statValueLarge() = TextView(this).apply { text = "0" }

    private fun buildPasteCard(): View {
        val card = card().apply { orientation = LinearLayout.VERTICAL }
        card.addView(TextView(this).apply {
            text = "Manual scan"
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = "Paste an email body, SMS, or link to score it now."
            setTextColor(TEXT_MID)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, dp(8))
        })
        pasteInput = EditText(this).apply {
            hint = "Paste here…"
            setHintTextColor(TEXT_LO)
            setTextColor(TEXT_HI)
            minLines = 4
            maxLines = 8
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(0xFF1A0E10.toInt(), dp(10)).also { it.setStroke(1, CARD_BORDER) }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        card.addView(pasteInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        Button(this).apply {
            text = "SCAN PASTED CONTENT"
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = pillBg(ACCENT, 12)
            stateListAnimator = null
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, dp(46))
            lp.setMargins(0, dp(10), 0, 0)
            layoutParams = lp
            setOnClickListener { onPasteScan() }
        }.also { card.addView(it) }
        return card
    }

    private fun onPasteScan() {
        val text = pasteInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) { toast("Nothing to scan"); return }
        val v = PhishingDetector.analyze(text)
        WardenState.recordEmailScan(
            WardenState.EmailScan(
                sender = "(pasted)",
                subject = text.take(80).replace("\n", " "),
                score = v.score, isPhishing = v.isPhishing,
                reasons = v.reasons, urls = v.matchedUrls, source = "Paste",
            )
        )
        if (v.isPhishing) {
            WardenState.commsBlocked.incrementAndGet()
            WardenState.commsLast.set("Paste phishing · score ${v.score}")
            WardenState.bumpBreakdown(Pillar.COMMS, "Paste")
            WardenState.pushEvent(WardenState.TimelineEvent(
                pillar = Pillar.COMMS,
                title = "Phishing detected (manual)",
                detail = v.reasons.firstOrNull().orEmpty()
            ))
        }
        pasteInput.setText("")
        refresh()
        toast(if (v.isPhishing) "Phishing detected · score ${v.score}" else "Clean · score ${v.score}")
    }

    private fun buildResultsCard(): View {
        val card = card().apply { orientation = LinearLayout.VERTICAL }
        card.addView(TextView(this).apply {
            text = "Recent scans"
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(4))
        })
        resultsHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(resultsHolder)
        return card
    }

    private fun emptyState(): View = TextView(this).apply {
        text = "No messages scanned yet. Enable message monitoring above, or paste a message in the Manual Scan card."
        setTextColor(TEXT_LO)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setLineSpacing(0f, 1.3f)
        setPadding(0, dp(12), 0, dp(12))
    }

    private fun resultRow(s: WardenState.EmailScan): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val severity = when {
            s.score >= 70 -> ACCENT
            s.score >= 40 -> AMBER
            else -> GREEN
        }
        row.addView(View(this).apply {
            background = roundedRect(severity, dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(3), dp(46)).apply { marginEnd = dp(12) }
        })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@EmailScannerActivity).apply {
                text = s.sender.ifBlank { "(unknown)" }
                setTextColor(TEXT_HI)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(TextView(this@EmailScannerActivity).apply {
                text = "${s.score}"
                setTextColor(severity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), 0, 0, 0)
            })
        })
        col.addView(TextView(this).apply {
            text = s.subject.ifBlank { "(no subject)" }
            setTextColor(TEXT_MID)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
            setPadding(0, dp(2), 0, 0)
        })
        col.addView(TextView(this).apply {
            text = "${timeFmt.format(Date(s.ts))}  ·  ${s.source}" +
                if (s.reasons.isNotEmpty()) "  ·  ${s.reasons.first()}" else ""
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(col)
        return row
    }

    // ── bottom nav ─────────────────────────────────────────────────────────
    private fun buildBottomNav(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedTop(CARD, dp(20)).also { it.setStroke(1, CARD_BORDER) }
            elevation = dp(8).toFloat()
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        bar.addView(navItem(R.drawable.ic_home, "Home", false) { goHome() })
        bar.addView(navItem(R.drawable.ic_message, "Messages", true) { /* already here */ })
        bar.addView(navItem(R.drawable.ic_mail, "SMS", false) { goPillar(Pillar.COMMS) })
        bar.addView(navItem(R.drawable.ic_apps, "Apps", false) { goPillar(Pillar.APPS) })
        bar.addView(navItem(R.drawable.ic_globe, "Network", false) { goPillar(Pillar.NETWORK) })
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

    private fun goHome() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun goPillar(p: Pillar) {
        startActivity(Intent(this, PillarDashboardActivity::class.java)
            .putExtra(PillarDashboardActivity.EXTRA_PILLAR, p.name)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    // ── helpers ────────────────────────────────────────────────────────────
    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, WardenNotificationListener::class.java).flattenToString()
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(":").any { it == cn }
    }

    private fun openNotificationListenerSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }.onFailure { toast("Could not open settings") }
    }

    private fun openGmail() {
        val pkg = "com.google.android.gm"
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(launch) }
        } else runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
            text = "Message Scanner — startup error"; setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = sw.toString(); setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); typeface = Typeface.MONOSPACE
            setPadding(0, dp(12), 0, 0); setTextIsSelectable(true)
        })
        scroll.addView(box)
        return scroll
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        background = roundedRect(CARD, dp(16)).also { it.setStroke(1, CARD_BORDER) }
        setPadding(dp(16), dp(16), dp(16), dp(16))
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        lp.setMargins(0, dp(8), 0, dp(8))
        layoutParams = lp
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

    private fun pillBg(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(color)
        }

    private fun verticalGradient(top: Int, bottom: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))

    private fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
