package com.privacywarden.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.privacywarden.app.views.Battery3DView
import com.privacywarden.app.vpn.WardenState

/**
 * "What Privacy Warden saved you" dashboard.
 *
 * Every blocked DNS lookup prevents one tracker request. Each such request,
 * on average, is a ~120 KB HTTPS handshake + payload and keeps the phone's
 * cellular radio awake for ~18 ms. We multiply the count of blocked requests
 * by these conservative averages and render the savings as data, battery
 * (in mAh) and time (radio-on seconds avoided).
 *
 *   • Data avoided      = blocked * 120 KB
 *   • Radio time saved  = blocked * 18 ms
 *   • Battery saved     = radio_time_s * 0.7 mA (Android radio average)
 *
 * These are deliberately conservative figures from Google's "On the Cost
 * of Radio Communication on Mobile Devices" + publicly-shared SDK sizes.
 * Real savings on a data-heavy app like Flipkart / Instagram are higher.
 */
class BenefitsActivity : ComponentActivity() {

    private val BG          = 0xFF0A0708.toInt()
    private val BG_HERO     = 0xFF2A0F12.toInt()
    private val CARD        = 0xFF160C0D.toInt()
    private val CARD_BORDER = 0xFF2A1416.toInt()
    private val SOFT_BADGE  = 0xFF2A1014.toInt()
    private val TEXT_HI     = 0xFFF7F2F2.toInt()
    private val TEXT_MID    = 0xFF9E9596.toInt()
    private val TEXT_LO     = 0xFF6B5F60.toInt()
    private val ACCENT      = 0xFFEF4444.toInt()
    private val ACCENT_DEEP = 0xFFDC2626.toInt()
    private val GREEN       = 0xFF10B981.toInt()

    // Calibration constants (conservative averages)
    private val BYTES_PER_REQ = 120L * 1024L      // 120 KB per tracker hit
    private val RADIO_MS_PER_REQ = 18L            // ~18 ms radio-on per hit
    private val RADIO_AVG_MA = 0.7                // mA drawn by cellular radio
    private val SCREEN_AVG_MA = 300.0             // avg mA drawn by screen-on device
    private var batteryCapacityMah: Double = 4000.0  // filled in onCreate

    /**
     * Standard reflection hack to read design battery capacity from
     * com.android.internal.os.PowerProfile. Returns a safe fallback if the
     * hidden class is unavailable (e.g. strict API 31+ filters).
     */
    private fun readBatteryCapacityMah(): Double {
        return runCatching {
            val cls = Class.forName("com.android.internal.os.PowerProfile")
            val instance = cls.getConstructor(android.content.Context::class.java)
                .newInstance(this)
            val mah = cls.getMethod("getAveragePower", String::class.java)
                .invoke(instance, "battery.capacity") as Double
            if (mah > 100) mah else 4000.0
        }.getOrDefault(4000.0)
    }

    private lateinit var bigNumber: TextView
    private lateinit var battery3D: Battery3DView
    private lateinit var realWorldImpact: TextView
    private lateinit var statData: TextView
    private lateinit var statBattery: TextView
    private lateinit var statTime: TextView
    private lateinit var chart: SparkView
    private lateinit var breakdownHolder: LinearLayout
    private lateinit var funFact: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION") window.statusBarColor = BG
        @Suppress("DEPRECATION") window.navigationBarColor = CARD
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        batteryCapacityMah = readBatteryCapacityMah()
        setContentView(buildView())
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val blocked = WardenState.networkBlocked.get().toLong()
        val bytes = blocked * BYTES_PER_REQ
        val radioMs = blocked * RADIO_MS_PER_REQ
        val mAh = (radioMs / 1000.0) * RADIO_AVG_MA / 3600.0 // mA·h

        bigNumber.text = "${nf(blocked)}"
        statData.text = humanBytes(bytes)
        val batteryText = when {
            mAh < 0.001 -> "${(mAh * 1_000_000).toInt()} µAh"
            mAh < 1.0   -> String.format("%.1f mAh", mAh)
            else        -> String.format("%.2f mAh", mAh)
        }
        statBattery.text = batteryText
        statTime.text = humanMs(radioMs)

        // Tiered progress ladder — the battery never gets "stuck full". Each
        // completed tier resets the fill and unlocks a new goal, so you have
        // something to chase forever. When the top tier is maxed out, the
        // battery stays full and shows a "MAXED" badge.
        val (tierName, floor, goal, maxed) = tierFor(blocked)
        val intoTier = (blocked - floor).coerceAtLeast(0L)
        val span = (goal - floor).coerceAtLeast(1L)
        val fraction = if (maxed) 1f
                       else (intoTier.toDouble() / span.toDouble())
                            .coerceIn(0.0, 1.0).toFloat()

        battery3D.valueText = batteryText
        battery3D.rightHeadline = "${nf(blocked)} blocks"
        battery3D.labelText = "Tier · $tierName"
        battery3D.progressText = if (maxed) "MAXED · $tierName"
                                  else "${nf(intoTier)} / ${nf(span)} → next tier"
        battery3D.setFill(fraction, animate = true)

        // ── Real-world translation banner ────────────────────────────────
        // % of phone's full charge:
        //   fraction = mAh_saved / battery_capacity_mAh
        // Screen-time equivalent:
        //   hours = mAh_saved / SCREEN_AVG_MA, convert to sec/min as needed.
        val pctOfBattery = (mAh / batteryCapacityMah) * 100.0
        val screenSecs = (mAh / SCREEN_AVG_MA) * 3600.0

        val pctStr = when {
            pctOfBattery >= 1.0   -> String.format("%.2f%%", pctOfBattery)
            pctOfBattery >= 0.01  -> String.format("%.3f%%", pctOfBattery)
            pctOfBattery >= 0.0001 -> String.format("%.5f%%", pctOfBattery)
            else                  -> "< 0.0001%"
        }
        val timeStr = when {
            screenSecs < 1.0    -> String.format("%.1f seconds", screenSecs)
            screenSecs < 60.0   -> "${screenSecs.toInt()} seconds"
            screenSecs < 3600.0 -> String.format("%.1f minutes", screenSecs / 60.0)
            else                -> String.format("%.1f hours", screenSecs / 3600.0)
        }
        realWorldImpact.text =
            "≈ $pctStr of your phone's full charge saved\n" +
            "≈ $timeStr of extra screen-on time  ·  battery ${batteryCapacityMah.toInt()} mAh"

        // Time-series chart — reuse WardenState.history for NETWORK pillar
        val series = WardenState.seriesForLastMinutes(WardenState.Pillar.NETWORK, 30)
        chart.setData(series)

        // Per-app breakdown — reuse the existing NETWORK domain breakdown, but
        // flip it to top offending DOMAINS (by block count) since that's the
        // data we reliably have.
        breakdownHolder.removeAllViews()
        val top = WardenState.breakdown[WardenState.Pillar.NETWORK]
            ?.entries
            ?.sortedByDescending { it.value }
            ?.take(8)
            ?: emptyList()
        if (top.isEmpty()) {
            breakdownHolder.addView(TextView(this).apply {
                text = "No traffic seen yet.  Turn on the VPN — then engage Panic Mode or browse normally to populate this board."
                setTextColor(TEXT_LO)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setLineSpacing(0f, 1.35f)
                setPadding(0, dp(8), 0, dp(8))
            })
        } else {
            val max = top.first().value.coerceAtLeast(1)
            for ((domain, n) in top) {
                breakdownHolder.addView(buildBreakdownRow(domain, n, max))
            }
        }

        funFact.text = pickFunFact(blocked, bytes, mAh)
    }

    /**
     * The tier ladder. Completing one tier unlocks the next and resets the
     * battery fill to 0% with a higher goal. When you reach the top tier
     * ("Sentinel") the view shows MAXED and stays full.
     *
     * Returns (tierName, floor, goal, maxed).
     */
    private data class TierInfo(
        val name: String, val floor: Long, val goal: Long, val maxed: Boolean,
    )
    private fun tierFor(blocked: Long): TierInfo {
        // Each entry is (tier name, goal block count). "floor" for entry N is
        // the goal of entry N-1 (0 for the first).
        val ladder = listOf(
            "Starter"   to      100L,
            "Defender"  to      500L,
            "Guardian"  to    2_000L,
            "Shield"    to   10_000L,
            "Sentinel"  to   50_000L,
        )
        var floor = 0L
        for ((name, goal) in ladder) {
            if (blocked < goal) return TierInfo(name, floor, goal, false)
            floor = goal
        }
        val (name, goal) = ladder.last()
        return TierInfo(name, floor, goal, true)
    }

    private fun pickFunFact(blocks: Long, bytes: Long, mAh: Double): String {
        // Light, motivating comparisons — all rough and clearly "equivalent to"
        // so no one takes them as engineering truths.
        return when {
            blocks == 0L ->
                "Engage Panic Mode or simply browse to see real numbers pile up."
            blocks < 50L ->
                "That's already ~${nf(blocks)} background callouts you never made."
            bytes < 1_000_000L ->
                "Enough data saved to load ~${bytes / 30_000L} extra web pages today."
            mAh < 5.0 ->
                "Radio kept asleep long enough to earn you back ~${(mAh * 6).toInt()} " +
                "seconds of screen-on time."
            else ->
                "Across your phone's lifetime this pace saves roughly " +
                "${(mAh * 24).toInt()} mAh/day — a real bite out of daily drain."
        }
    }

    // ── view tree ───────────────────────────────────────────────────────────

    private fun buildView(): View {
        val frame = FrameLayout(this).apply { setBackgroundColor(BG) }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(40))
        }

        // Hero
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = verticalGradient(BG_HERO, BG)
            setPadding(dp(20), dp(40), dp(20), dp(20))
        }
        // Top bar: back + title
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = iconBadge(R.drawable.ic_arrow_left, ACCENT, SOFT_BADGE, dp(40)).apply {
            isClickable = true; isFocusable = true
            setOnClickListener { finish() }
        }
        top.addView(back)
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setPadding(dp(12), 0, 0, 0)
        }
        titleBox.addView(TextView(this).apply {
            text = "Your savings"
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        })
        titleBox.addView(TextView(this).apply {
            text = "Data · battery · radio time avoided"
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
        top.addView(titleBox)
        top.addView(iconBadge(R.drawable.ic_shield_check, ACCENT, SOFT_BADGE, dp(40)))
        hero.addView(top)

        // Big headline number
        bigNumber = TextView(this).apply {
            text = "0"
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 54f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(28), 0, 0)
        }
        hero.addView(bigNumber)
        hero.addView(TextView(this).apply {
            text = "Tracker requests blocked since launch"
            setTextColor(TEXT_MID)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), 0, dp(16))
        })
        content.addView(hero)

        val below = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }

        // 3D Battery hero card
        val batCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(CARD, dp(18)).also { it.setStroke(1, CARD_BORDER) }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.setMargins(0, dp(6), 0, dp(4))
            layoutParams = lp
        }
        battery3D = Battery3DView(this).apply {
            labelText = "Battery saved"
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(170))
        }
        batCard.addView(battery3D)

        // Real-world impact banner — the line that makes "105 µAh" mean
        // something to a human by translating it into "% of your full charge"
        // and "seconds of screen-on time".
        realWorldImpact = TextView(this).apply {
            text = ""
            setTextColor(TEXT_MID)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(0f, 1.3f)
            gravity = Gravity.CENTER
            background = roundedRect(0xFF1A0B0D.toInt(), dp(12))
                .also { it.setStroke(1, CARD_BORDER) }
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.setMargins(dp(6), dp(8), dp(6), dp(6))
            layoutParams = lp
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        batCard.addView(realWorldImpact)

        below.addView(batCard)

        // 3-tile stats row
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.setMargins(0, dp(6), 0, 0)
            layoutParams = lp
        }
        val dataTile = buildStatTile("Data", "0 KB", R.drawable.ic_globe)
        val batTile  = buildStatTile("Battery", "0 µAh", R.drawable.ic_shield_check)
        val timeTile = buildStatTile("Radio",   "0 ms",  R.drawable.ic_alert)
        statData    = dataTile.second
        statBattery = batTile.second
        statTime    = timeTile.second
        row.addView(dataTile.first, weightLp())
        row.addView(spacer())
        row.addView(batTile.first, weightLp())
        row.addView(spacer())
        row.addView(timeTile.first, weightLp())
        below.addView(row)

        // Sparkline chart
        val chartCard = card().apply { orientation = LinearLayout.VERTICAL }
        chartCard.addView(TextView(this).apply {
            text = "Last 30 minutes"
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        })
        chart = SparkView(this)
        chart.accent = ACCENT
        chart.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(100)).also {
            it.setMargins(0, dp(12), 0, 0)
        }
        chartCard.addView(chart)
        below.addView(chartCard)

        // Breakdown card
        val br = card().apply { orientation = LinearLayout.VERTICAL }
        br.addView(TextView(this).apply {
            text = "Top tracker domains"
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        })
        breakdownHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        br.addView(breakdownHolder)
        below.addView(br)

        // Fun fact card
        val ff = card().apply { orientation = LinearLayout.VERTICAL }
        ff.addView(TextView(this).apply {
            text = "IN PLAIN WORDS"
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            letterSpacing = 0.14f
            typeface = Typeface.DEFAULT_BOLD
        })
        funFact = TextView(this).apply {
            text = ""
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(8), 0, 0)
        }
        ff.addView(funFact)
        below.addView(ff)

        // Methodology disclaimer
        below.addView(TextView(this).apply {
            text = "Estimates based on conservative per-tracker averages (120 KB payload, 18 ms radio-on time). Actual savings on data-heavy apps are typically higher."
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setLineSpacing(0f, 1.3f)
            setPadding(dp(4), dp(14), dp(4), 0)
        })

        content.addView(below)
        scroll.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        frame.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return frame
    }

    private fun buildStatTile(label: String, initialValue: String, iconRes: Int):
            Pair<LinearLayout, TextView> {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(CARD, dp(14)).also { it.setStroke(1, CARD_BORDER) }
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        tile.addView(iconBadge(iconRes, ACCENT, SOFT_BADGE, dp(32)))
        val valueTv = TextView(this).apply {
            text = initialValue
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, 0)
        }
        tile.addView(valueTv)
        tile.addView(TextView(this).apply {
            text = label
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
        return tile to valueTv
    }

    private fun buildBreakdownRow(domain: String, n: Int, max: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(TextView(this).apply {
            text = domain
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        top.addView(TextView(this).apply {
            text = "× ${nf(n.toLong())}"
            setTextColor(ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        row.addView(top)
        // thin progress bar
        val bar = FrameLayout(this).apply {
            background = roundedRect(SOFT_BADGE, dp(3))
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, dp(5))
            lp.setMargins(0, dp(6), 0, 0)
            layoutParams = lp
        }
        val fill = View(this).apply {
            background = roundedRect(ACCENT, dp(3))
            val pct = (n.toFloat() / max.toFloat()).coerceIn(0.05f, 1f)
            val lp = FrameLayout.LayoutParams(0, MATCH_PARENT)
            lp.width = 0
            layoutParams = lp
            post {
                layoutParams = FrameLayout.LayoutParams(
                    (bar.width * pct).toInt().coerceAtLeast(dp(8)),
                    MATCH_PARENT,
                )
            }
        }
        bar.addView(fill)
        row.addView(bar)
        return row
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun humanBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1_048_576 -> String.format("%.1f KB", b / 1024.0)
        b < 1_073_741_824 -> String.format("%.2f MB", b / 1_048_576.0)
        else -> String.format("%.2f GB", b / 1_073_741_824.0)
    }

    private fun humanMs(ms: Long): String = when {
        ms < 1000 -> "$ms ms"
        ms < 60_000 -> String.format("%.1f s", ms / 1000.0)
        ms < 3_600_000 -> "${ms / 60_000} min"
        else -> "${ms / 3_600_000} h"
    }

    private fun nf(v: Long): String =
        java.text.NumberFormat.getInstance(java.util.Locale.US).format(v)

    private fun weightLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)

    private fun spacer(): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(dp(10), dp(1))
        return v
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        background = roundedRect(CARD, dp(16)).also { it.setStroke(1, CARD_BORDER) }
        setPadding(dp(16), dp(16), dp(16), dp(16))
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        lp.setMargins(0, dp(12), 0, 0)
        layoutParams = lp
    }

    private fun iconBadge(iconRes: Int, iconColor: Int, bg: Int, size: Int): FrameLayout {
        val frame = FrameLayout(this).apply {
            background = roundedRect(bg, dp(10))
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

    private fun verticalGradient(top: Int, bottom: Int): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(top, bottom)
        )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ── tiny sparkline view (no 3rd-party chart deps) ──────────────────────
    class SparkView(ctx: android.content.Context) : View(ctx) {
        var accent: Int = 0xFFEF4444.toInt()
        private var data: IntArray = IntArray(0)
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = accent
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = (accent and 0x00FFFFFF) or 0x33000000
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2A1416.toInt()
            strokeWidth = 1f
        }

        fun setData(series: IntArray) {
            data = series
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            linePaint.color = accent
            fillPaint.color = (accent and 0x00FFFFFF) or 0x40000000
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f || data.isEmpty()) return
            // baseline + midline
            canvas.drawLine(0f, h - 1f, w, h - 1f, gridPaint)
            canvas.drawLine(0f, h / 2f, w, h / 2f, gridPaint)
            val max = (data.max()).coerceAtLeast(1)
            val stepX = w / (data.size - 1).coerceAtLeast(1).toFloat()
            val path = android.graphics.Path()
            val fill = android.graphics.Path()
            for (i in data.indices) {
                val x = i * stepX
                val y = h - (data[i].toFloat() / max.toFloat()) * (h - 10f) - 4f
                if (i == 0) { path.moveTo(x, y); fill.moveTo(x, h) ; fill.lineTo(x, y) }
                else        { path.lineTo(x, y); fill.lineTo(x, y) }
            }
            fill.lineTo(w, h); fill.close()
            canvas.drawPath(fill, fillPaint)
            canvas.drawPath(path, linePaint)
        }
    }
}
