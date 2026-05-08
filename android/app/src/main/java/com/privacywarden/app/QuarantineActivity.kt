package com.privacywarden.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.privacywarden.app.vpn.WardenState

/**
 * "I think this app is acting weird" picker.
 *
 * Lists every user-installed app with a search bar. Tap one →
 *   1. Mark it as quarantined → VPN drops every DNS query for that UID
 *      from the next packet onwards (no network at all for that app).
 *   2. Open Android's "App Info" screen for that package so the user can
 *      hit Force Stop / Uninstall in one extra tap (third-party apps
 *      cannot programmatically force-stop another app on stock Android).
 *
 * A second tap on the same row un-quarantines it.
 */
class QuarantineActivity : ComponentActivity() {

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

    private lateinit var listHolder: LinearLayout
    private lateinit var search: EditText
    private lateinit var summary: TextView
    private var allApps: List<AppRow> = emptyList()

    private data class AppRow(val pkg: String, val label: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION") window.statusBarColor = BG
        @Suppress("DEPRECATION") window.navigationBarColor = CARD
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContentView(buildView())
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        renderList(currentFilter())
        renderSummary()
    }

    private fun loadApps() {
        // Show a placeholder immediately so the screen paints before the
        // (potentially slow) package-manager scan completes.
        listHolder.removeAllViews()
        listHolder.addView(TextView(this).apply {
            text = "Loading installed apps…"
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(30), 0, dp(30))
        })
        // Do the heavy work off the main thread; getInstalledApplications()
        // + getApplicationLabel() on 200+ apps can easily take >5s and ANR
        // on older devices.
        Thread {
            val pm = packageManager
            val apps = runCatching {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .asSequence()
                    .filter { ai ->
                        if (ai.packageName == packageName) return@filter false
                        val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        val isUpdated = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                        !isSystem || isUpdated
                    }
                    .map { ai ->
                        val label = runCatching { pm.getApplicationLabel(ai).toString() }
                            .getOrDefault(ai.packageName)
                        AppRow(ai.packageName, label)
                    }
                    .sortedBy { it.label.lowercase() }
                    .toList()
            }.getOrDefault(emptyList())
            runOnUiThread {
                allApps = apps
                renderList(currentFilter())
                renderSummary()
            }
        }.start()
    }

    private fun currentFilter(): String = search.text?.toString().orEmpty().trim().lowercase()

    private fun renderSummary() {
        val n = WardenState.quarantinedPackages.size
        val blocked = WardenState.quarantineBlockedCount.get()
        summary.text = if (n == 0) {
            "Tap any app you don't trust right now. We'll cut its network and open Force-Stop for you."
        } else {
            "$n app(s) quarantined  ·  $blocked DNS request(s) blocked"
        }
    }

    private fun renderList(query: String) {
        listHolder.removeAllViews()
        if (allApps.isEmpty()) {
            listHolder.addView(TextView(this).apply {
                text = "Loading installed apps…"
                setTextColor(TEXT_LO)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(0, dp(30), 0, dp(30))
            })
            return
        }
        val matched = allApps.filter {
            query.isEmpty() || it.label.lowercase().contains(query) ||
                it.pkg.lowercase().contains(query)
        }
        if (matched.isEmpty()) {
            listHolder.addView(TextView(this).apply {
                text = "No apps match \"$query\""
                setTextColor(TEXT_LO)
                setPadding(0, dp(20), 0, dp(20))
                gravity = Gravity.CENTER
            })
            return
        }
        // Cap to first 60 rows when not searching — laying out 200+ rows with
        // icon drawables on the main thread on older devices can ANR.
        val cap = if (query.isEmpty()) 60 else matched.size
        val visible = matched.take(cap)
        for (row in visible) listHolder.addView(buildAppRow(row))
        if (matched.size > visible.size) {
            listHolder.addView(TextView(this).apply {
                text = "+ ${matched.size - visible.size} more — type a name to search"
                setTextColor(TEXT_LO)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(14))
            })
        }
    }

    private fun buildAppRow(app: AppRow): View {
        val quarantined = WardenState.isQuarantined(app.pkg)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedRect(CARD, dp(14)).also {
                it.setStroke(if (quarantined) dp(2) else 1, if (quarantined) ACCENT else CARD_BORDER)
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.setMargins(0, dp(6), 0, 0)
            layoutParams = lp
            isClickable = true; isFocusable = true
            setOnClickListener { onAppTap(app, quarantined) }
        }
        // App icon
        val iconHost = FrameLayout(this).apply {
            background = roundedRect(SOFT_BADGE, dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        }
        val iconView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(4)
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        runCatching {
            iconView.setImageDrawable(packageManager.getApplicationIcon(app.pkg))
        }.onFailure {
            iconView.setImageResource(R.drawable.ic_apps)
            iconView.imageTintList = android.content.res.ColorStateList.valueOf(ACCENT)
        }
        iconHost.addView(iconView)
        row.addView(iconHost)

        // Title + package
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setPadding(dp(12), 0, dp(8), 0)
        }
        col.addView(TextView(this).apply {
            text = app.label
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        col.addView(TextView(this).apply {
            text = if (quarantined) "Quarantined · network cut" else app.pkg
            setTextColor(if (quarantined) ACCENT else TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        row.addView(col)

        // Action chip
        row.addView(TextView(this).apply {
            text = if (quarantined) "RELEASE" else "QUARANTINE"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = pillBg(if (quarantined) 0xFF3A2024.toInt() else ACCENT_DEEP, 10)
        })
        return row
    }

    private fun onAppTap(app: AppRow, alreadyQuarantined: Boolean) {
        if (alreadyQuarantined) {
            WardenState.unquarantine(app.pkg)
            WardenState.pushEvent(
                WardenState.TimelineEvent(
                    pillar = WardenState.Pillar.APPS,
                    title = "Released ${app.label}",
                    detail = "Network restored",
                )
            )
            toast("${app.label} released — network restored")
            renderList(currentFilter())
            renderSummary()
            return
        }
        // Engage quarantine: cut network now, then deep-link to App Info
        // so the user can Force Stop / Uninstall in one extra tap.
        if (!WardenState.running.get()) {
            toast("Turn on Privacy Warden first (top-right ON)")
            return
        }
        WardenState.quarantine(app.pkg)
        WardenState.appsBlocked.incrementAndGet()
        WardenState.appsLast.set("Quarantined: ${app.label}")
        WardenState.pushEvent(
            WardenState.TimelineEvent(
                pillar = WardenState.Pillar.APPS,
                title = "Quarantined ${app.label}",
                detail = "Network cut · opening App Info to force-stop",
            )
        )
        toast("${app.label} cut off · tap Force Stop on the next screen")
        renderList(currentFilter())
        renderSummary()

        // Open the system's App Info screen for this package — user taps
        // "Force stop" themselves (Android security model: 3rd-party apps
        // cannot programmatically force-stop another app on a stock device).
        runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${app.pkg}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }.onFailure {
            // Fallback: open generic app list
            startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
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

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = verticalGradient(BG_HERO, BG)
            setPadding(dp(20), dp(40), dp(20), dp(20))
        }
        // Top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = iconBadge(R.drawable.ic_arrow_left, ACCENT, SOFT_BADGE, dp(40)).apply {
            isClickable = true; isFocusable = true
            setOnClickListener { finish() }
        }
        top.addView(back)
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setPadding(dp(12), 0, 0, 0)
        }
        title.addView(TextView(this).apply {
            text = "Quarantine an app"
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        })
        title.addView(TextView(this).apply {
            text = "Cut its network instantly"
            setTextColor(TEXT_LO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
        top.addView(title)
        top.addView(iconBadge(R.drawable.ic_alert, ACCENT, SOFT_BADGE, dp(40)))
        hero.addView(top)

        hero.addView(TextView(this).apply {
            text = "Acting weird?"
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(24), 0, 0)
        })
        summary = TextView(this).apply {
            text = "Tap any app you don't trust right now. We'll cut its network and open Force-Stop for you."
            setTextColor(TEXT_MID)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(8), 0, dp(16))
        }
        hero.addView(summary)
        content.addView(hero)

        // Search bar
        val below = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        search = EditText(this).apply {
            hint = "Search by app name…"
            setHintTextColor(TEXT_LO)
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = roundedRect(0xFF1A0E10.toInt(), dp(12))
                .also { it.setStroke(1, CARD_BORDER) }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.setMargins(0, dp(8), 0, dp(12))
            layoutParams = lp
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    renderList(s?.toString().orEmpty().trim().lowercase())
                }
            })
        }
        below.addView(search)

        listHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        below.addView(listHolder)
        content.addView(below)

        scroll.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        frame.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return frame
    }

    // ── primitives ──────────────────────────────────────────────────────────

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

    private fun pillBg(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(color)
        }

    private fun verticalGradient(top: Int, bottom: Int): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(top, bottom)
        )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}
