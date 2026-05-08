package com.privacywarden.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.core.content.ContextCompat
import com.privacywarden.app.defense.OtpScanner
import com.privacywarden.app.defense.PhishingDetector
import com.privacywarden.app.defense.SmsScanner
import com.privacywarden.app.defense.StalkerwareScanner
import com.privacywarden.app.defense.UpiScanner
import com.privacywarden.app.ui.BarChartView
import com.privacywarden.app.ui.PieChartView
import com.privacywarden.app.ui.SparklineView
import com.privacywarden.app.util.WardenPrefs
import com.privacywarden.app.vpn.WardenState
import com.privacywarden.app.vpn.WardenState.Pillar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-pillar deep-dive dashboard — dark-red theme, matching home + email scanner.
 *
 *   - Top bar: back chevron + pillar title
 *   - Greeting hero with red gradient
 *   - Sliding carousel of recent events for this pillar (auto-advancing)
 *   - Big counter card
 *   - 30-min sparkline
 *   - Pie + bar breakdown charts (retinted to red palette)
 *   - Pillar-specific real-action buttons
 *   - Filtered timeline of just this pillar's events
 *   - Bottom nav with the matching tab highlighted
 */
class PillarDashboardActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PILLAR = "pillar"
    }

    // ── palette (blue chrome, red reserved for threats) ─────────────────
    private val BG          = 0xFF08090C.toInt()
    private val BG_HERO     = 0xFF0E1A2E.toInt()     // navy hero wash
    private val CARD        = 0xFF101420.toInt()
    private val CARD_BORDER = 0xFF1C2438.toInt()
    private val SOFT_BADGE  = 0xFF11213D.toInt()
    private val TEXT_HI     = 0xFFF2F5F7.toInt()
    private val TEXT_MID    = 0xFF9AA3B2.toInt()
    private val TEXT_LO     = 0xFF5F6A7D.toInt()
    private val ACCENT      = 0xFFEF4444.toInt()     // red-500 (threats)
    private val BRAND       = 0xFF3B82F6.toInt()     // blue-500 (chrome)
    private val AMBER       = 0xFFF59E0B.toInt()
    private val GREEN       = 0xFF10B981.toInt()
    private val INFO_BLUE   = 0xFF60A5FA.toInt()

    private lateinit var pillar: Pillar
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
    private val dateFmt = SimpleDateFormat("MMM d", Locale.US)

    // Live refs
    private lateinit var counterValue: TextView
    private lateinit var counterSubtitle: TextView
    private lateinit var sparkline: SparklineView
    private lateinit var pie: PieChartView
    private lateinit var bar: BarChartView
    private lateinit var actionsHolder: LinearLayout
    private lateinit var timelineHolder: LinearLayout
    private lateinit var statusLabel: TextView

    // Carousel
    private lateinit var carouselScroll: HorizontalScrollView
    private lateinit var carouselContent: LinearLayout
    private lateinit var carouselDots: LinearLayout
    private var carouselSize = 0
    private var carouselIndex = 0
    private var carouselCardWidthPx = 0
    private var lastSnapshotKey = ""

    // ── lifecycle ───────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION") window.statusBarColor = BG
        @Suppress("DEPRECATION") window.navigationBarColor = CARD
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val name = intent.getStringExtra(EXTRA_PILLAR) ?: Pillar.NETWORK.name
        pillar = runCatching { Pillar.valueOf(name) }.getOrDefault(Pillar.NETWORK)
        try {
            setContentView(buildView())
        } catch (t: Throwable) {
            setContentView(buildCrashScreen(t))
        }
    }

    override fun onResume() {
        super.onResume()
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
        val (count, last) = readPillar()
        counterValue.text = count.toString()
        counterSubtitle.text = last ?: "No events yet"

        sparkline.values = WardenState.seriesForLastMinutes(pillar, 30)
        sparkline.lineColor = ACCENT
        sparkline.fillColor = 0x33EF4444.toInt()

        val map = WardenState.breakdown[pillar]?.toMap() ?: emptyMap()
        val palette = listOf(
            ACCENT,
            shift(ACCENT, 0.7f),
            shift(ACCENT, 0.45f),
            shift(ACCENT, 0.25f),
            0xFF6B5F60.toInt(),
        )
        val sorted = map.entries.sortedByDescending { it.value }
        pie.data = sorted.take(5).mapIndexed { i, e ->
            Triple(trim(e.key, 14), e.value, palette[i % palette.size])
        }
        bar.data = sorted.take(6).map { trim(it.key, 22) to it.value }
        bar.barColor = ACCENT

        if (pillar == Pillar.COMMS) {
            statusLabel.text = "Scanning: ${commsPhoneLabel()}"
            rebuildActions()
        }

        // Carousel: recent events filtered to this pillar
        val items = WardenState.timeline.filter { it.pillar == pillar }.take(8)
        val key = items.joinToString("|") { "${it.ts}-${it.title.hashCode()}" }
        if (key != lastSnapshotKey) {
            lastSnapshotKey = key
            renderCarousel(items)
        }

        renderTimeline()
    }

    private fun readPillar(): Pair<Int, String?> = when (pillar) {
        Pillar.NETWORK  -> WardenState.flowsObserved.get() to WardenState.lastSni.get()
        Pillar.COMMS    -> WardenState.commsBlocked.get() to WardenState.commsLast.get()
        Pillar.MONEY    -> WardenState.moneyBlocked.get() to WardenState.moneyLast.get()
        Pillar.APPS     -> WardenState.appsBlocked.get() to WardenState.appsLast.get()
        Pillar.IDENTITY -> WardenState.identityBlocked.get() to WardenState.identityLast.get()
        Pillar.PHYSICAL -> WardenState.physicalBlocked.get() to WardenState.physicalLast.get()
    }

    // ── carousel ───────────────────────────────────────────────────────────
    private fun renderCarousel(items: List<WardenState.TimelineEvent>) {
        carouselContent.removeAllViews()
        carouselSize = items.size
        val screenW = resources.displayMetrics.widthPixels
        val cardW = screenW - dp(40)
        carouselCardWidthPx = cardW

        if (items.isEmpty()) {
            val intro = buildEventCard()
            bindIntroCard(intro)
            intro.layoutParams = LinearLayout.LayoutParams(cardW, dp(220))
            carouselContent.addView(intro)
            carouselSize = 1
            renderDots(1, 0)
            return
        }
        for ((i, e) in items.withIndex()) {
            val card = buildEventCard()
            bindEventCard(card, e)
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

    private fun buildEventCard(): LinearLayout {
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.14f
            setPadding(dp(10), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        top.addView(TextView(this).apply {
            tag = "tag"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(TEXT_LO)
            typeface = Typeface.MONOSPACE
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
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(footer)
        return card
    }

    private fun bindEventCard(card: View, e: WardenState.TimelineEvent) {
        val sevColor = when {
            e.title.contains("phish", true) || e.title.contains("stalker", true) ||
            e.title.contains("detected", true) -> ACCENT
            e.title.contains("clean", true) -> GREEN
            else -> AMBER
        }
        card.findViewWithTag<View>("stripe")?.background = roundedRect(sevColor, dp(2))
        val iconHost = card.findViewWithTag<FrameLayout>("iconHost")
        iconHost?.removeAllViews()
        iconHost?.addView(ImageView(this).apply {
            setImageResource(iconResFor(pillar))
            imageTintList = android.content.res.ColorStateList.valueOf(sevColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        })
        card.findViewWithTag<TextView>("type")?.let {
            it.text = "${pillar.label.uppercase()} EVENT"
            it.setTextColor(sevColor)
        }
        card.findViewWithTag<TextView>("tag")?.text = ""
        card.findViewWithTag<TextView>("title")?.text = e.title
        card.findViewWithTag<TextView>("body")?.text = e.detail
        card.findViewWithTag<TextView>("ts")?.text =
            "${dateFmt.format(Date(e.ts))} · ${timeFmt.format(Date(e.ts))}"
        card.findViewWithTag<TextView>("cta")?.text = "View timeline ›"
        card.setOnClickListener { /* future: open detail */ }
    }

    private fun bindIntroCard(card: View) {
        card.findViewWithTag<View>("stripe")?.background = roundedRect(INFO_BLUE, dp(2))
        val iconHost = card.findViewWithTag<FrameLayout>("iconHost")
        iconHost?.removeAllViews()
        iconHost?.addView(ImageView(this).apply {
            setImageResource(iconResFor(pillar))
            imageTintList = android.content.res.ColorStateList.valueOf(INFO_BLUE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        })
        card.findViewWithTag<TextView>("type")?.let {
            it.text = pillar.label.uppercase() + " · READY"
            it.setTextColor(INFO_BLUE)
        }
        card.findViewWithTag<TextView>("title")?.text = headlineFor(pillar)
        card.findViewWithTag<TextView>("body")?.text = actionsBlurbFor(pillar)
        card.findViewWithTag<TextView>("ts")?.text = "No events yet"
        card.findViewWithTag<TextView>("cta")?.text =
            if (actionsFor(pillar).isNotEmpty()) "Run a scan ›" else "—"
    }

    private fun iconResFor(p: Pillar): Int = when (p) {
        Pillar.NETWORK  -> R.drawable.ic_globe
        Pillar.COMMS    -> R.drawable.ic_message
        Pillar.MONEY    -> R.drawable.ic_money
        Pillar.APPS     -> R.drawable.ic_apps
        Pillar.IDENTITY -> R.drawable.ic_lock
        Pillar.PHYSICAL -> R.drawable.ic_alert
    }

    // ── timeline (textual list below the cards) ─────────────────────────────
    private fun renderTimeline() {
        timelineHolder.removeAllViews()
        val events = WardenState.timeline.filter { it.pillar == pillar }.take(12)
        if (events.isEmpty()) {
            timelineHolder.addView(TextView(this).apply {
                text = "No events captured yet for this pillar."
                setTextColor(TEXT_LO)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(8), 0, dp(8))
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
        row.addView(iconBadge(iconResFor(pillar), ACCENT, SOFT_BADGE, dp(36)))
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
            text = "${timeFmt.format(Date(e.ts))} · ${pillar.label}"
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(col)
        return row
    }

    // ──────────────────────────────────────────────────────────────────────
    // VIEW
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
        below.addView(buildHero())
        below.addView(buildTrendCard())
        below.addView(buildBreakdownCard())
        below.addView(buildOffendersCard())
        below.addView(buildActionsCard())
        below.addView(buildTimelineCard())
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
        row.addView(iconBadge(R.drawable.ic_arrow_left, ACCENT, SOFT_BADGE, dp(40)).apply {
            isClickable = true; isFocusable = true
            setOnClickListener { finish() }
        })
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setPadding(dp(12), 0, 0, 0)
        }
        title.addView(TextView(this).apply {
            text = "${pillar.label} Defense"
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = -0.01f
        })
        statusLabel = TextView(this).apply {
            text = if (pillar == Pillar.COMMS) "Scanning: ${commsPhoneLabel()}"
            else "Live · pillar dashboard"
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        title.addView(statusLabel)
        row.addView(title)
        // Pillar's themed icon to the right
        row.addView(iconBadge(iconResFor(pillar), ACCENT, SOFT_BADGE, dp(40)))
        return row
    }

    private fun buildGreeting(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(28), 0, dp(20))
        }
        box.addView(TextView(this).apply {
            text = headlineFor(pillar)
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = -0.02f
            setLineSpacing(0f, 1.1f)
        })
        box.addView(TextView(this).apply {
            text = actionsBlurbFor(pillar)
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
            text = "RECENT EVENTS"
            setTextColor(ACCENT)
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

    private fun buildHero(): View {
        val box = card().apply { orientation = LinearLayout.VERTICAL }
        box.addView(sectionHeader(headlineFor(pillar)))
        counterValue = TextView(this).apply {
            text = "0"
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, 0)
        }
        box.addView(counterValue)
        counterSubtitle = TextView(this).apply {
            text = "—"
            setTextColor(TEXT_MID)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(2), 0, 0)
        }
        box.addView(counterSubtitle)
        return box
    }

    private fun buildTrendCard(): View {
        val card = card().apply { orientation = LinearLayout.VERTICAL }
        card.addView(sectionHeader("LAST 30 MIN · ACTIVITY"))
        sparkline = SparklineView(this).apply {
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, dp(120))
            lp.setMargins(0, dp(8), 0, 0)
            layoutParams = lp
        }
        card.addView(sparkline)
        return card
    }

    private fun buildBreakdownCard(): View {
        val card = card().apply { orientation = LinearLayout.VERTICAL }
        card.addView(sectionHeader("BREAKDOWN"))
        pie = PieChartView(this).apply {
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, dp(180))
            lp.setMargins(0, dp(8), 0, 0)
            layoutParams = lp
        }
        card.addView(pie)
        return card
    }

    private fun buildOffendersCard(): View {
        val card = card().apply { orientation = LinearLayout.VERTICAL }
        card.addView(sectionHeader(offendersTitleFor(pillar)))
        bar = BarChartView(this).apply {
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, dp(220))
            lp.setMargins(0, dp(8), 0, 0)
            layoutParams = lp
        }
        card.addView(bar)
        return card
    }

    private fun buildActionsCard(): View {
        val card = card().apply { orientation = LinearLayout.VERTICAL }
        card.addView(sectionHeader("ACTIONS"))
        card.addView(TextView(this).apply {
            text = actionsBlurbFor(pillar)
            setTextColor(TEXT_MID)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, dp(10))
        })
        actionsHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(actionsHolder)
        rebuildActions()
        return card
    }

    private fun rebuildActions() {
        actionsHolder.removeAllViews()
        val acts = actionsFor(pillar)
        if (acts.isEmpty()) {
            actionsHolder.addView(TextView(this).apply {
                text = "No manual actions yet — this pillar runs continuously in the background."
                setTextColor(TEXT_LO)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setLineSpacing(0f, 1.3f)
                setPadding(0, dp(6), 0, dp(6))
            })
            return
        }
        for (act in acts) actionsHolder.addView(actionButton(act.first, act.second))
    }

    private fun buildTimelineCard(): View {
        val card = card().apply { orientation = LinearLayout.VERTICAL }
        card.addView(sectionHeader("RECENT EVENTS · ${pillar.label.uppercase()}"))
        timelineHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(timelineHolder)
        return card
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
        bar.addView(navItem(R.drawable.ic_message, "Messages", false) { goEmail() })
        bar.addView(navItem(R.drawable.ic_mail, "SMS", pillar == Pillar.COMMS) { goPillar(Pillar.COMMS) })
        bar.addView(navItem(R.drawable.ic_apps, "Apps", pillar == Pillar.APPS) { goPillar(Pillar.APPS) })
        bar.addView(navItem(R.drawable.ic_globe, "Network", pillar == Pillar.NETWORK) { goPillar(Pillar.NETWORK) })
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

    private fun goEmail() {
        startActivity(Intent(this, EmailScannerActivity::class.java))
        finish()
    }

    private fun goPillar(p: Pillar) {
        if (p == pillar) return
        startActivity(Intent(this, PillarDashboardActivity::class.java)
            .putExtra(EXTRA_PILLAR, p.name)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Per-pillar copy + actions
    // ──────────────────────────────────────────────────────────────────────

    private fun headlineFor(p: Pillar): String = when (p) {
        Pillar.NETWORK  -> "Trackers, observed."
        Pillar.COMMS    -> "Phishing, blocked."
        Pillar.MONEY    -> "Payment threats."
        Pillar.APPS     -> "Stalkerware findings."
        Pillar.IDENTITY -> "Identity events."
        Pillar.PHYSICAL -> "Physical defense."
    }

    private fun offendersTitleFor(p: Pillar): String = when (p) {
        Pillar.NETWORK  -> "TOP TRACKER DOMAINS"
        Pillar.COMMS    -> "TOP PHISHING SENDERS"
        Pillar.MONEY    -> "TOP SUSPICIOUS VPAs"
        Pillar.APPS     -> "TOP RISKY APPS"
        Pillar.IDENTITY -> "TOP IDENTITY SOURCES"
        Pillar.PHYSICAL -> "TOP TRIGGERS"
    }

    private fun actionsBlurbFor(p: Pillar): String = when (p) {
        Pillar.NETWORK  -> "Privacy Warden intercepts DNS on-device, captures the real domains your apps query, and synthesises NXDOMAIN replies for any tracker matched by a BLOCK rule — the connection is never made."
        Pillar.COMMS    -> "Sweep your real SMS inbox for phishing on-device, or paste any message/email/link to analyze it. Nothing leaves the phone."
        Pillar.MONEY    -> "Scan your real SMS inbox for UPI scam patterns: extract every VPA mentioned, score it against an offline impersonation blocklist, and flag handles sent inside phishing messages."
        Pillar.APPS     -> "Run a real on-device scan of every installed app's manifest, permissions, and stealth signals."
        Pillar.IDENTITY -> "Scan your real SMS inbox for OTPs and breach-notification messages. Burst windows of ≥3 OTPs from different senders within 5 minutes are reported as possible account-takeover attempts."
        Pillar.PHYSICAL -> "Coming next: volume-key panic gesture, snatch-detection via accelerometer, and travel-mode vault."
    }

    private fun actionsFor(p: Pillar): List<Pair<String, () -> Unit>> = when (p) {
        Pillar.NETWORK -> emptyList()
        Pillar.COMMS -> listOf(
            "Choose phone number to scan" to { showPhonePicker() },
            "Scan ${commsPhoneLabel()}" to { runSmsSweep() },
            "Paste a message & scan" to { showPasteAndScan() },
        )
        Pillar.MONEY -> listOf(
            "Scan SMS inbox for UPI scams" to { runUpiScan() },
        )
        Pillar.APPS -> listOf(
            "Run stalkerware scan" to { runStalkerScan() },
        )
        Pillar.IDENTITY -> listOf(
            "Scan SMS for OTP storms & breaches" to { runOtpScan() },
        )
        Pillar.PHYSICAL -> emptyList()
    }

    // ── real actions ───────────────────────────────────────────────────────
    private fun runSmsSweep() {
        val perm = Manifest.permission.READ_SMS
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), 91)
            toast("Grant SMS permission, then tap again")
            return
        }
        val sel = WardenPrefs.selectedPhone(this) ?: "ALL"
        statusLabel.text = "Scanning ${if (sel == "ALL") "all senders" else sel}…"
        Thread {
            val r = SmsScanner.scanAndPublish(this, senderFilter = sel)
            handler.post {
                statusLabel.text = if (r.flagged.isEmpty())
                    "Clean · ${r.totalScanned} scanned from ${if (sel == "ALL") "all senders" else sel}"
                else
                    "${r.flagged.size} phishing SMS flagged of ${r.totalScanned}"
                refresh()
            }
        }.start()
    }

    private fun commsPhoneLabel(): String {
        val s = WardenPrefs.selectedPhone(this)
        return when {
            s.isNullOrEmpty() || s == "ALL" -> "ALL SENDERS"
            else -> s.take(18)
        }
    }

    private fun showPhonePicker() {
        val perm = Manifest.permission.READ_SMS
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), 91)
            toast("Grant SMS permission, then tap again")
            return
        }
        val senders = SmsScanner.listSenders(this).take(50)
        if (senders.isEmpty()) { toast("No SMS in inbox"); return }
        val labels = listOf("ALL SENDERS  ·  scan everything") +
            senders.map { "${it.first}    (${it.second} msgs)" }
        val keys = listOf("ALL") + senders.map { it.first }
        val current = WardenPrefs.selectedPhone(this) ?: "ALL"
        val checked = keys.indexOf(current).let { if (it < 0) 0 else it }
        AlertDialog.Builder(this)
            .setTitle("Pick a phone number to scan")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                WardenPrefs.setSelectedPhone(this, keys[which])
                d.dismiss()
                statusLabel.text = "Now scanning ${if (keys[which] == "ALL") "all senders" else keys[which]}"
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPasteAndScan() {
        val input = EditText(this).apply {
            hint = "Paste SMS, email or link to scan…"
            setHintTextColor(TEXT_LO)
            setTextColor(TEXT_HI)
            minLines = 4
            maxLines = 10
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(0xFF1A0E10.toInt(), dp(10)).also { it.setStroke(1, CARD_BORDER) }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
            addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("Scan a message")
            .setView(container)
            .setPositiveButton("Scan") { d, _ ->
                val text = input.text?.toString().orEmpty().trim()
                if (text.isEmpty()) { toast("Nothing to scan"); d.dismiss(); return@setPositiveButton }
                val v = PhishingDetector.analyzeAndPublish(text, source = "Paste")
                AlertDialog.Builder(this)
                    .setTitle(if (v.isPhishing) "Phishing detected" else "Looks safe")
                    .setMessage(buildString {
                        append("Score: ${v.score}/100\n\n")
                        if (v.reasons.isNotEmpty()) {
                            append("Reasons:\n"); v.reasons.forEach { append(" • $it\n") }
                        }
                        if (v.matchedUrls.isNotEmpty()) {
                            append("\nURLs:\n"); v.matchedUrls.forEach { append(" • $it\n") }
                        }
                    })
                    .setPositiveButton("OK", null)
                    .show()
                d.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runStalkerScan() {
        toast("Scanning every installed app — may take a few seconds…")
        statusLabel.text = "Scanning installed apps…"
        Thread {
            val results = StalkerwareScanner.scanAndPublish(this)
            for (r in results.take(10)) WardenState.bumpBreakdown(Pillar.APPS, r.label)
            handler.post {
                statusLabel.text = if (results.isEmpty())
                    "Clean · no stalkerware patterns found"
                else
                    "${results.size} flagged · top ${results.first().label}"
                showStalkerResults(results)
                refresh()
            }
        }.start()
    }

    /** Summary dialog shown after a stalkerware scan finishes. */
    private fun showStalkerResults(results: List<com.privacywarden.app.defense.StalkerwareScanner.Finding>) {
        val msg = buildString {
            if (results.isEmpty()) {
                append("Scan complete.\n\n")
                append("No stalkerware fingerprints, surveillance-permission clusters, or stealth signals detected across your installed apps.\n\n")
                append("Your device appears clean.")
            } else {
                append("Scan complete — ")
                append(results.size); append(" app(s) flagged.\n\n")
                for ((i, f) in results.withIndex()) {
                    append(i + 1); append(". "); append(f.label)
                    append("  ·  risk "); append(f.score); append("/100\n")
                    append("   ("); append(f.pkg); append(")\n")
                    if (f.hidden) append("   Hidden: no launcher icon\n")
                    for (r in f.reasons.take(3)) {
                        append("   • "); append(r); append('\n')
                    }
                    append('\n')
                    if (i >= 9) { append("…and "); append(results.size - 10); append(" more\n"); break }
                }
                append("Tap QUARANTINE A SPECIFIC APP on the home screen to cut their network, then uninstall via Android Settings.")
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (results.isEmpty()) "Device clean" else "${results.size} flagged apps")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun runUpiScan() {
        val perm = Manifest.permission.READ_SMS
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), 92)
            toast("Grant SMS permission, then tap again")
            return
        }
        statusLabel.text = "Scanning SMS inbox for UPI scams…"
        Thread {
            val r = UpiScanner.scanAndPublish(this)
            handler.post {
                statusLabel.text = if (r.flagged.isEmpty())
                    "Clean · ${r.totalSmsScanned} SMS · ${r.totalVpasFound} VPAs seen"
                else
                    "${r.flagged.size} suspicious VPAs · top ${r.flagged.first().vpa}"
                refresh()
            }
        }.start()
    }

    private fun runOtpScan() {
        val perm = Manifest.permission.READ_SMS
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), 93)
            toast("Grant SMS permission, then tap again")
            return
        }
        statusLabel.text = "Scanning SMS for OTPs and breach keywords…"
        Thread {
            val r = OtpScanner.scanAndPublish(this)
            handler.post {
                statusLabel.text = when {
                    r.burstCount > 0  -> "OTP-storm × ${r.burstCount} · review timeline"
                    r.breachHits.isNotEmpty() -> "${r.breachHits.size} breach SMS detected"
                    r.otps.isNotEmpty()      -> "${r.otps.size} OTPs seen · inbox clean"
                    else                     -> "Clean · ${r.totalScanned} SMS scanned"
                }
                refresh()
            }
        }.start()
    }

    // ── helpers ────────────────────────────────────────────────────────────
    private fun actionButton(label: String, onTap: () -> Unit): View {
        return Button(this).apply {
            text = label
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = pillButton(SOFT_BADGE).also { it.setStroke(1, ACCENT) }
            stateListAnimator = null
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, dp(46))
            lp.setMargins(0, dp(4), 0, dp(4))
            layoutParams = lp
            setOnClickListener { onTap(); refresh() }
        }
    }

    private fun sectionHeader(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(TEXT_LO)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        letterSpacing = 0.12f
        typeface = Typeface.DEFAULT_BOLD
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

    private fun roundedRect(c: Int, r: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = r.toFloat(); setColor(c)
    }
    private fun roundedTop(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            val r = radius.toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(color)
        }
    private fun pillButton(c: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(10).toFloat(); setColor(c)
    }
    private fun verticalGradient(top: Int, bottom: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))

    private fun buildCrashScreen(t: Throwable): View {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(20))
        }
        box.addView(TextView(this).apply {
            text = "${pillar.label} Dashboard — startup error"; setTextColor(ACCENT)
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun trim(s: String, n: Int): String = if (s.length <= n) s else s.take(n - 1) + "…"

    private fun shift(color: Int, factor: Float): Int {
        val a = (color shr 24) and 0xFF
        val r = ((color shr 16) and 0xFF) * factor + (1 - factor) * 30
        val g = ((color shr 8) and 0xFF) * factor + (1 - factor) * 30
        val b = (color and 0xFF) * factor + (1 - factor) * 30
        return Color.argb(a, r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
    }
}
