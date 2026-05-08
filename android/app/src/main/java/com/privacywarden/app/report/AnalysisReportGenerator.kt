package com.privacywarden.app.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.view.View
import com.privacywarden.app.ui.BarChartView
import com.privacywarden.app.ui.PieChartView
import com.privacywarden.app.ui.SparklineView
import com.privacywarden.app.vpn.WardenState
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device PDF report generator.
 *
 * Produces a multi-page A4 PDF that visualises everything currently in
 * [WardenState]: headline counters, trend sparkline, top-tracker pie,
 * top risky apps bar chart, flagged-email list, mic/camera accesses, and
 * a recent timeline.
 *
 * Design notes:
 *   • A4 pages at 72 dpi = 595 × 842 pt. We use that unit throughout.
 *   • We reuse the existing SparklineView/PieChartView/BarChartView by
 *     measuring + laying them out off-screen and calling draw(canvas)
 *     onto the PDF canvas. No WebView, no HTML, no extra deps.
 *   • The file is written to externalCacheDir/reports/…pdf so it can be
 *     shared via FileProvider without needing WRITE_EXTERNAL_STORAGE.
 */
object AnalysisReportGenerator {

    // ── A4 page geometry (points, 72 dpi) ──────────────────────────────────
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40f

    // ── palette (matches the app's dark-red theme, printed on white paper) ─
    private val INK_HI   = Color.parseColor("#111111")
    private val INK_MID  = Color.parseColor("#444444")
    private val INK_LO   = Color.parseColor("#7A7A7A")
    private val ACCENT   = Color.parseColor("#DC2626")
    private val ACCENT_D = Color.parseColor("#991B1B")
    private val BG_TINT  = Color.parseColor("#FEF2F2")
    private val BORDER   = Color.parseColor("#F3E4E5")
    private val GREEN    = Color.parseColor("#059669")
    private val AMBER    = Color.parseColor("#D97706")

    private val dateFmt = SimpleDateFormat("EEE, MMM d yyyy · HH:mm", Locale.getDefault())
    private val tsShort = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())

    /** Generate and return the written PDF file. */
    fun generate(ctx: Context): File {
        val doc = PdfDocument()
        try {
            writeCoverPage(doc, ctx)
            writeDashboardPage(doc, ctx)
            writeActivityPage(doc, ctx)
            writeThreatsPage(doc, ctx)
            writeMethodologyPage(doc, ctx)
        } catch (t: Throwable) {
            doc.close()
            throw t
        }

        val dir = File(ctx.externalCacheDir ?: ctx.cacheDir, "reports")
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "PrivacyWarden-Report-$stamp.pdf")
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
        return out
    }

    // ─────────────────────────────────────────────────────────────────────
    // PAGE 1 — cover
    // ─────────────────────────────────────────────────────────────────────
    private fun writeCoverPage(doc: PdfDocument, ctx: Context) {
        val page = doc.startPage(pageInfo(1))
        val c = page.canvas
        val p = paint()

        // red hero band
        p.color = ACCENT
        c.drawRect(0f, 0f, PAGE_W.toFloat(), 240f, p)
        p.color = ACCENT_D
        c.drawRect(0f, 230f, PAGE_W.toFloat(), 240f, p)

        // brand
        p.color = Color.WHITE
        p.textSize = 11f
        p.typeface = Typeface.DEFAULT_BOLD
        p.letterSpacing = 0.15f
        c.drawText("PRIVACY WARDEN · CONFIDENTIAL REPORT", MARGIN, 60f, p)
        p.letterSpacing = 0f

        // headline
        p.textSize = 38f
        c.drawText("Your Privacy Report", MARGIN, 120f, p)
        p.textSize = 16f
        p.typeface = Typeface.DEFAULT
        c.drawText("Generated on-device · nothing left your phone", MARGIN, 150f, p)
        p.textSize = 11f
        c.drawText(dateFmt.format(Date()), MARGIN, 175f, p)

        // Hero metric block
        val blocks = WardenState.networkBlocked.get()
        p.color = Color.WHITE
        p.textSize = 64f
        p.typeface = Typeface.DEFAULT_BOLD
        c.drawText(blocks.toString(), MARGIN, 220f, p)

        // description strip
        p.color = INK_HI
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 13f
        c.drawText("TRACKER REQUESTS BLOCKED — LIFETIME", MARGIN, 280f, p)

        // Quick stats grid (2x2)
        val gridY = 310f
        val gridH = 140f
        val gw = (PAGE_W - 2 * MARGIN - 16f) / 2f
        drawStatTile(c, MARGIN,                    gridY,            gw, gridH,
            label = "EMAILS SCANNED", value = WardenState.emailScans.size.toString(),
            sub = "${WardenState.emailScans.count { it.isPhishing }} flagged as phishing")
        drawStatTile(c, MARGIN + gw + 16f,         gridY,            gw, gridH,
            label = "RISKY APP EVENTS", value = WardenState.appsBlocked.get().toString(),
            sub = "stalkerware-pattern detections")
        drawStatTile(c, MARGIN,                    gridY + gridH + 16, gw, gridH,
            label = "COMMS THREATS", value = WardenState.commsBlocked.get().toString(),
            sub = "phishing SMS / email blocked")
        drawStatTile(c, MARGIN + gw + 16f,         gridY + gridH + 16, gw, gridH,
            label = "QUARANTINED APPS", value = WardenState.quarantinedPackages.size.toString(),
            sub = "apps cut off from the network")

        // Summary paragraph
        val paraY = gridY + 2 * (gridH + 16) + 30f
        p.textSize = 14f
        p.typeface = Typeface.DEFAULT_BOLD
        p.color = ACCENT
        c.drawText("WHAT THIS REPORT CONTAINS", MARGIN, paraY, p)
        p.typeface = Typeface.DEFAULT
        p.color = INK_MID
        p.textSize = 11f
        drawWrappedText(
            c, p,
            "Page 2 — visual dashboard with a trend chart, top tracker domains, and top risky apps. " +
            "Page 3 — recent protection events with timestamps. Page 4 — detailed threat findings " +
            "across email, SMS, and installed apps. Page 5 — methodology, so you can verify every " +
            "number in this report. All figures reflect the state of your device at generation time.",
            MARGIN, paraY + 18f, PAGE_W - 2 * MARGIN, 15f,
        )

        // Footer
        drawFooter(c, 1, "Cover · Executive summary")
        doc.finishPage(page)
    }

    // ─────────────────────────────────────────────────────────────────────
    // PAGE 2 — dashboard with charts
    // ─────────────────────────────────────────────────────────────────────
    private fun writeDashboardPage(doc: PdfDocument, ctx: Context) {
        val page = doc.startPage(pageInfo(2))
        val c = page.canvas
        val p = paint()

        drawHeader(c, "Visual dashboard", "Same charts you see in the app, printed for the record")

        // ── Battery / tier card (manually drawn so it matches the app) ─
        val topY = 130f
        val cardW = PAGE_W - 2 * MARGIN
        val cardH = 150f
        drawCard(c, MARGIN, topY, cardW, cardH)

        p.color = ACCENT
        p.textSize = 10f
        p.letterSpacing = 0.14f
        p.typeface = Typeface.DEFAULT_BOLD
        c.drawText("SAVINGS TO DATE", MARGIN + 16f, topY + 24f, p)
        p.letterSpacing = 0f

        // Compute stats
        val blocks = WardenState.networkBlocked.get()
        val bytes = blocks * 120L * 1024L
        val mAh = (blocks * 18L) * 0.7 / 3600.0
        val radioMs = blocks * 18L

        p.color = INK_HI; p.textSize = 26f; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText("${humanBytes(bytes)} data saved", MARGIN + 16f, topY + 60f, p)
        p.textSize = 14f; p.typeface = Typeface.DEFAULT; p.color = INK_MID
        c.drawText("≈ ${formatMah(mAh)} electricity · ≈ ${humanMs(radioMs)} of radio time kept asleep",
            MARGIN + 16f, topY + 84f, p)

        // Mini battery illustration (right side)
        drawBatteryIcon(
            c, MARGIN + cardW - 110f, topY + 20f, 90f, cardH - 40f,
            fillFraction = batteryFraction(blocks),
        )

        // ── Sparkline (trend last 30 min) ───────────────────────────────
        val sparkY = topY + cardH + 20f
        val sparkH = 180f
        drawCard(c, MARGIN, sparkY, cardW, sparkH)
        p.color = ACCENT; p.textSize = 10f; p.letterSpacing = 0.14f; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText("TRACKERS BLOCKED · LAST 30 MINUTES", MARGIN + 16f, sparkY + 24f, p)
        p.letterSpacing = 0f; p.typeface = Typeface.DEFAULT

        val sparkInnerX = MARGIN + 16f
        val sparkInnerY = sparkY + 36f
        val sparkInnerW = cardW - 32f
        val sparkInnerH = sparkH - 56f
        val series = WardenState.seriesForLastMinutes(WardenState.Pillar.NETWORK, 30)
        val spark = SparklineView(ctx).apply {
            values = series
            lineColor = ACCENT
            fillColor = (ACCENT and 0x00FFFFFF) or 0x33000000
        }
        drawViewAt(c, spark, sparkInnerX, sparkInnerY, sparkInnerW.toInt(), sparkInnerH.toInt())
        // scale labels
        p.color = INK_LO; p.textSize = 9f
        c.drawText("30 min ago", sparkInnerX, sparkInnerY + sparkInnerH + 14f, p)
        val tr = Paint(p).apply { textAlign = Paint.Align.RIGHT }
        c.drawText("now", sparkInnerX + sparkInnerW, sparkInnerY + sparkInnerH + 14f, tr)

        // ── Pie chart (top tracker domains) ─────────────────────────────
        val pieY = sparkY + sparkH + 20f
        val pieH = 220f
        val pieW = (cardW - 16f) / 2f
        drawCard(c, MARGIN, pieY, pieW, pieH)
        p.color = ACCENT; p.textSize = 10f; p.letterSpacing = 0.14f; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText("TOP TRACKER DOMAINS", MARGIN + 16f, pieY + 24f, p)
        p.letterSpacing = 0f; p.typeface = Typeface.DEFAULT

        val pie = PieChartView(ctx).apply {
            data = topDomainPieData(5)
        }
        drawViewAt(c, pie, MARGIN + 10f, pieY + 36f, (pieW - 20f).toInt(), (pieH - 50f).toInt())

        // ── Bar chart (top risky apps) ──────────────────────────────────
        val barX = MARGIN + pieW + 16f
        drawCard(c, barX, pieY, pieW, pieH)
        p.color = ACCENT; p.textSize = 10f; p.letterSpacing = 0.14f; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText("TOP RISKY APPS", barX + 16f, pieY + 24f, p)
        p.letterSpacing = 0f; p.typeface = Typeface.DEFAULT

        val bar = BarChartView(ctx).apply {
            data = topAppsBarData(6)
            barColor = ACCENT
        }
        drawViewAt(c, bar, barX + 16f, pieY + 40f, (pieW - 32f).toInt(), (pieH - 56f).toInt())

        drawFooter(c, 2, "Dashboard · at a glance")
        doc.finishPage(page)
    }

    // ─────────────────────────────────────────────────────────────────────
    // PAGE 3 — recent activity
    // ─────────────────────────────────────────────────────────────────────
    private fun writeActivityPage(doc: PdfDocument, ctx: Context) {
        val page = doc.startPage(pageInfo(3))
        val c = page.canvas
        val p = paint()

        drawHeader(c, "Recent activity", "Timeline of protection events, newest first")

        val events = WardenState.timeline.toList().take(25)
        val startY = 140f
        val rowH = 56f
        var y = startY
        val cardW = PAGE_W - 2 * MARGIN

        if (events.isEmpty()) {
            drawCard(c, MARGIN, y, cardW, 80f)
            p.color = INK_MID; p.textSize = 13f
            c.drawText("No events yet. Run a scan or browse a website — activity will appear here.",
                MARGIN + 16f, y + 48f, p)
        } else {
            for (e in events) {
                if (y + rowH + 30 > PAGE_H - 60) break  // leave room for footer
                drawCard(c, MARGIN, y, cardW, rowH - 8f)
                // colored pill for pillar
                val pillColor = when (e.pillar) {
                    WardenState.Pillar.NETWORK  -> ACCENT
                    WardenState.Pillar.COMMS    -> Color.parseColor("#7C3AED")
                    WardenState.Pillar.MONEY    -> GREEN
                    WardenState.Pillar.APPS     -> Color.parseColor("#DB2777")
                    WardenState.Pillar.IDENTITY -> AMBER
                    WardenState.Pillar.PHYSICAL -> ACCENT_D
                }
                p.color = pillColor
                c.drawRoundRect(MARGIN + 10f, y + 10f, MARGIN + 78f, y + 28f, 8f, 8f, p)
                p.color = Color.WHITE; p.textSize = 9f; p.typeface = Typeface.DEFAULT_BOLD
                p.textAlign = Paint.Align.CENTER
                c.drawText(e.pillar.label.uppercase(), MARGIN + 44f, y + 23f, p)
                p.textAlign = Paint.Align.LEFT

                p.color = INK_HI; p.textSize = 12f; p.typeface = Typeface.DEFAULT_BOLD
                c.drawText(e.title, MARGIN + 90f, y + 22f, p)
                p.color = INK_MID; p.typeface = Typeface.DEFAULT; p.textSize = 10f
                val detail = e.detail.ifBlank { "—" }
                c.drawText(detail.take(90), MARGIN + 90f, y + 37f, p)

                // timestamp right-aligned
                p.color = INK_LO; p.textSize = 10f; p.textAlign = Paint.Align.RIGHT
                c.drawText(tsShort.format(Date(e.ts)), MARGIN + cardW - 12f, y + 22f, p)
                p.textAlign = Paint.Align.LEFT

                y += rowH
            }
        }

        drawFooter(c, 3, "Activity · chronological feed")
        doc.finishPage(page)
    }

    // ─────────────────────────────────────────────────────────────────────
    // PAGE 4 — threats (emails, sensors, quarantine)
    // ─────────────────────────────────────────────────────────────────────
    private fun writeThreatsPage(doc: PdfDocument, ctx: Context) {
        val page = doc.startPage(pageInfo(4))
        val c = page.canvas
        val p = paint()

        drawHeader(c, "Threat findings", "Detailed breakdown of what was caught")

        val cardW = PAGE_W - 2 * MARGIN
        var y = 140f

        // ── Emails
        val emails = WardenState.emailScans.toList().take(6)
        val emailsH = (60f + emails.size * 48f).coerceAtLeast(90f)
        drawCard(c, MARGIN, y, cardW, emailsH)
        p.color = ACCENT; p.textSize = 11f; p.letterSpacing = 0.14f; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText("EMAIL SCANS  ·  RECENT", MARGIN + 16f, y + 26f, p)
        p.letterSpacing = 0f; p.typeface = Typeface.DEFAULT
        var ey = y + 54f
        if (emails.isEmpty()) {
            p.color = INK_MID; p.textSize = 11f
            c.drawText("No emails scanned yet. Enable the email listener on the home screen.",
                MARGIN + 16f, ey, p)
        } else {
            for (e in emails) {
                val color = if (e.isPhishing) ACCENT else GREEN
                p.color = color
                c.drawRoundRect(MARGIN + 16f, ey - 13f, MARGIN + 72f, ey + 3f, 7f, 7f, p)
                p.color = Color.WHITE; p.textSize = 8f; p.typeface = Typeface.DEFAULT_BOLD
                p.textAlign = Paint.Align.CENTER
                c.drawText(if (e.isPhishing) "PHISH ${e.score}" else "CLEAN ${e.score}",
                    MARGIN + 44f, ey - 1f, p)
                p.textAlign = Paint.Align.LEFT

                p.color = INK_HI; p.textSize = 11f; p.typeface = Typeface.DEFAULT_BOLD
                c.drawText(e.sender.ifBlank { "(unknown sender)" }.take(35), MARGIN + 84f, ey, p)
                p.color = INK_MID; p.typeface = Typeface.DEFAULT; p.textSize = 9f
                c.drawText(e.subject.ifBlank { "(no subject)" }.take(70),
                    MARGIN + 84f, ey + 12f, p)
                ey += 48f
            }
        }
        y += emailsH + 16f

        // ── Sensor accesses
        val last24 = WardenState.sensorAccesses.toList().filter {
            it.startTs > System.currentTimeMillis() - 86_400_000L
        }.take(8)
        val sensorH = (60f + last24.size.coerceAtLeast(1) * 28f).coerceAtLeast(90f)
        drawCard(c, MARGIN, y, cardW, sensorH)
        p.color = ACCENT; p.textSize = 11f; p.letterSpacing = 0.14f; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText("MIC / CAMERA ACCESSES  ·  LAST 24h", MARGIN + 16f, y + 26f, p)
        p.letterSpacing = 0f; p.typeface = Typeface.DEFAULT
        var sy = y + 54f
        if (last24.isEmpty()) {
            p.color = INK_MID; p.textSize = 11f
            c.drawText("No mic or camera access recorded in the last 24 hours.",
                MARGIN + 16f, sy, p)
        } else {
            for (s in last24) {
                val icon = when (s.kind) {
                    WardenState.SensorAccess.Kind.MIC    -> "[MIC]"
                    WardenState.SensorAccess.Kind.CAMERA -> "[CAM]"
                }
                p.color = if (s.active) ACCENT else INK_HI
                p.typeface = Typeface.DEFAULT_BOLD; p.textSize = 11f
                c.drawText(icon, MARGIN + 16f, sy, p)
                p.color = INK_HI; p.typeface = Typeface.DEFAULT_BOLD
                c.drawText(s.label.take(36), MARGIN + 60f, sy, p)
                p.color = INK_MID; p.typeface = Typeface.DEFAULT; p.textSize = 10f
                val durSec = s.durationMs / 1000
                c.drawText("${tsShort.format(Date(s.startTs))} · ${durSec}s${if (s.active) " (live)" else ""}",
                    MARGIN + cardW - 16f - 180f, sy, p)
                sy += 28f
            }
        }
        y += sensorH + 16f

        // ── Quarantine
        val qPkgs = WardenState.quarantinedPackages.toList()
        val qH = (60f + qPkgs.size.coerceAtLeast(1) * 20f).coerceAtLeast(80f)
        if (y + qH < PAGE_H - 60f) {
            drawCard(c, MARGIN, y, cardW, qH)
            p.color = ACCENT; p.textSize = 11f; p.letterSpacing = 0.14f; p.typeface = Typeface.DEFAULT_BOLD
            c.drawText("QUARANTINED APPS", MARGIN + 16f, y + 26f, p)
            p.letterSpacing = 0f; p.typeface = Typeface.DEFAULT
            var qy = y + 50f
            if (qPkgs.isEmpty()) {
                p.color = INK_MID; p.textSize = 11f
                c.drawText("No apps are currently quarantined.", MARGIN + 16f, qy, p)
            } else {
                p.color = INK_HI; p.textSize = 10.5f; p.typeface = Typeface.MONOSPACE
                for (pkg in qPkgs.take(8)) {
                    c.drawText("• $pkg", MARGIN + 16f, qy, p)
                    qy += 20f
                }
            }
        }

        drawFooter(c, 4, "Threats · detailed findings")
        doc.finishPage(page)
    }

    // ─────────────────────────────────────────────────────────────────────
    // PAGE 5 — methodology
    // ─────────────────────────────────────────────────────────────────────
    private fun writeMethodologyPage(doc: PdfDocument, ctx: Context) {
        val page = doc.startPage(pageInfo(5))
        val c = page.canvas
        val p = paint()

        drawHeader(c, "How these numbers were computed", "Transparent methodology — verify everything")

        val lines = listOf(
            "Data saved" to "Each blocked tracker request is scored as 120 KB of payload (conservative average based on published Exodus tracker-flow measurements).",
            "Battery saved" to "Computed as blocks × 18 ms × 0.7 mA (transmit) ÷ 3600 s/h × 1 h. We ignore the multi-second radio tail-state for conservatism.",
            "Radio time" to "Each blocked tracker avoids ~18 ms of active-transmit cellular radio time. This number under-reports real savings because tail-state is excluded.",
            "Tier system" to "Starter (0→100 blocks), Defender (100→500), Guardian (500→2k), Shield (2k→10k), Sentinel (10k→50k). Designed to always give you a new goal.",
            "Email phishing score" to "Six rule layers: urgency phrases (+25), OTP harvesting (+60), money lures (+20), URL shortener (+30), brand impersonation (+50), suspicious hyphenation (+10). Score ≥ 60 = phishing.",
            "Stalkerware detection" to "Matches package/label against known stalkerware fingerprints (mSpy, FlexiSPY, Cocospy…), surveillance-permission clusters, and stealth signals. Score ≥ 60 = flagged.",
            "Mic/camera" to "Uses AudioManager.registerAudioRecordingCallback and CameraManager.AvailabilityCallback — the same APIs that power Android's own privacy indicators. A reconciliation watchdog resolves stale recording state every 8 s.",
            "Privacy promise" to "All of the above runs entirely on this device. No email body, SMS content, or app list leaves your phone at any point. No account, no login, no cloud scoring.",
        )

        var y = 140f
        for ((title, body) in lines) {
            if (y + 60 > PAGE_H - 60) break
            p.color = ACCENT; p.textSize = 13f; p.typeface = Typeface.DEFAULT_BOLD
            c.drawText(title, MARGIN, y, p)
            y += 18f
            p.color = INK_MID; p.textSize = 10.5f; p.typeface = Typeface.DEFAULT
            y = drawWrappedText(c, p, body, MARGIN, y, PAGE_W - 2 * MARGIN, 14f)
            y += 12f
        }

        drawFooter(c, 5, "Methodology · transparent scoring")
        doc.finishPage(page)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Drawing helpers
    // ─────────────────────────────────────────────────────────────────────
    private fun pageInfo(num: Int): PdfDocument.PageInfo =
        PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, num).create()

    private fun paint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK_HI
        textSize = 12f
        typeface = Typeface.DEFAULT
    }

    private fun drawHeader(c: Canvas, title: String, sub: String) {
        val p = paint()
        p.color = ACCENT; p.textSize = 10f; p.letterSpacing = 0.14f; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText("PRIVACY WARDEN", MARGIN, 50f, p)
        p.letterSpacing = 0f

        p.color = INK_HI; p.textSize = 26f
        c.drawText(title, MARGIN, 84f, p)

        p.color = INK_MID; p.textSize = 11f; p.typeface = Typeface.DEFAULT
        c.drawText(sub, MARGIN, 104f, p)

        p.color = ACCENT
        c.drawRect(MARGIN, 114f, MARGIN + 40f, 117f, p)
    }

    private fun drawFooter(c: Canvas, pageNo: Int, note: String) {
        val p = paint()
        p.color = INK_LO; p.textSize = 9f
        c.drawText("Privacy Warden · generated on-device · ${dateFmt.format(Date())}",
            MARGIN, PAGE_H - 24f, p)
        p.textAlign = Paint.Align.RIGHT
        c.drawText("Page $pageNo · $note", PAGE_W - MARGIN, PAGE_H - 24f, p)
    }

    private fun drawCard(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val p = paint()
        p.color = Color.WHITE
        c.drawRoundRect(RectF(x, y, x + w, y + h), 12f, 12f, p)
        p.color = BORDER; p.style = Paint.Style.STROKE; p.strokeWidth = 1f
        c.drawRoundRect(RectF(x, y, x + w, y + h), 12f, 12f, p)
        p.style = Paint.Style.FILL
    }

    private fun drawStatTile(
        c: Canvas, x: Float, y: Float, w: Float, h: Float,
        label: String, value: String, sub: String,
    ) {
        val p = paint()
        // background
        p.color = BG_TINT
        c.drawRoundRect(RectF(x, y, x + w, y + h), 12f, 12f, p)
        // left stripe
        p.color = ACCENT
        c.drawRoundRect(RectF(x, y, x + 4f, y + h), 2f, 2f, p)

        p.color = ACCENT; p.textSize = 10f; p.letterSpacing = 0.14f; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText(label, x + 16f, y + 26f, p)
        p.letterSpacing = 0f

        p.color = INK_HI; p.textSize = 36f; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText(value, x + 16f, y + 78f, p)

        p.color = INK_MID; p.textSize = 11f; p.typeface = Typeface.DEFAULT
        c.drawText(sub, x + 16f, y + 108f, p)
    }

    /** Draw a View onto the PDF canvas at (x, y) with the given dimensions. */
    private fun drawViewAt(c: Canvas, view: View, x: Float, y: Float, w: Int, h: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        c.save()
        c.translate(x, y)
        view.draw(c)
        c.restore()
    }

    private fun drawBatteryIcon(c: Canvas, x: Float, y: Float, w: Float, h: Float, fillFraction: Float) {
        val p = paint()
        val bodyLeft = x + 6f
        val bodyRight = x + w
        val bodyTop = y + 10f
        val bodyBottom = y + h
        // nub
        p.color = ACCENT_D
        c.drawRoundRect(RectF(x + w / 2f - 10f, y, x + w / 2f + 10f, y + 10f), 3f, 3f, p)
        // body shell
        p.color = Color.parseColor("#FFE2E2")
        c.drawRoundRect(RectF(bodyLeft, bodyTop, bodyRight, bodyBottom), 10f, 10f, p)
        // liquid
        val fillTop = bodyBottom - (bodyBottom - bodyTop - 8f) * fillFraction - 4f
        val liquidRect = RectF(bodyLeft + 4f, fillTop, bodyRight - 4f, bodyBottom - 4f)
        p.color = ACCENT
        c.drawRoundRect(liquidRect, 8f, 8f, p)
        // border
        p.color = ACCENT_D; p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f
        c.drawRoundRect(RectF(bodyLeft, bodyTop, bodyRight, bodyBottom), 10f, 10f, p)
        p.style = Paint.Style.FILL
    }

    /**
     * Draw a paragraph of text wrapped at maxWidth. Returns the y coordinate
     * after the last line so the caller can continue laying content below.
     */
    private fun drawWrappedText(
        c: Canvas, p: Paint, text: String, x: Float, y: Float, maxWidth: Float, lineHeight: Float,
    ): Float {
        val words = text.split(' ')
        val line = StringBuilder()
        var cy = y
        for (w in words) {
            val candidate = if (line.isEmpty()) w else "$line $w"
            if (p.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                c.drawText(line.toString(), x, cy, p)
                cy += lineHeight
                line.clear(); line.append(w)
            } else {
                line.clear(); line.append(candidate)
            }
        }
        if (line.isNotEmpty()) {
            c.drawText(line.toString(), x, cy, p)
            cy += lineHeight
        }
        return cy
    }

    // ─────────────────────────────────────────────────────────────────────
    // Data helpers
    // ─────────────────────────────────────────────────────────────────────
    private fun topDomainPieData(n: Int): List<Triple<String, Int, Int>> {
        val breakdown = WardenState.breakdown[WardenState.Pillar.NETWORK]
            ?: return emptyList()
        val top = breakdown.entries.sortedByDescending { it.value }.take(n)
        val palette = listOf(
            ACCENT, Color.parseColor("#F59E0B"), Color.parseColor("#7C3AED"),
            Color.parseColor("#0EA5E9"), Color.parseColor("#10B981"),
        )
        return top.mapIndexed { i, e ->
            Triple(e.key.take(20), e.value, palette[i % palette.size])
        }
    }

    private fun topAppsBarData(n: Int): List<Pair<String, Int>> {
        val all = mutableMapOf<String, Int>()
        val apps = WardenState.breakdown[WardenState.Pillar.APPS] ?: emptyMap()
        for ((k, v) in apps) all.merge(k, v) { a, b -> a + b }
        // Also fold in sensor-access counts (last 24h) as a proxy for activity
        val last24 = WardenState.sensorAccesses.toList().filter {
            it.startTs > System.currentTimeMillis() - 86_400_000L
        }
        for (s in last24) all.merge(s.label, 1) { a, b -> a + b }
        return all.entries.sortedByDescending { it.value }.take(n)
            .map { it.key.take(22) to it.value }
    }

    private fun batteryFraction(blocks: Int): Float {
        // Tier ladder matching BenefitsActivity.
        val ladder = listOf(100, 500, 2_000, 10_000, 50_000)
        var floor = 0
        for (goal in ladder) {
            if (blocks < goal) {
                val span = (goal - floor).coerceAtLeast(1)
                return ((blocks - floor).toFloat() / span.toFloat()).coerceIn(0f, 1f)
            }
            floor = goal
        }
        return 1f
    }

    private fun humanBytes(n: Long): String = when {
        n >= 1_073_741_824 -> String.format("%.2f GB", n / 1_073_741_824.0)
        n >= 1_048_576     -> String.format("%.2f MB", n / 1_048_576.0)
        n >= 1024          -> String.format("%.1f KB", n / 1024.0)
        else               -> "$n B"
    }

    private fun humanMs(ms: Long): String = when {
        ms >= 3600_000 -> String.format("%.1f h", ms / 3600_000.0)
        ms >= 60_000   -> String.format("%.1f min", ms / 60_000.0)
        ms >= 1000     -> String.format("%.1f s", ms / 1000.0)
        else           -> "$ms ms"
    }

    private fun formatMah(mAh: Double): String = when {
        mAh < 0.001 -> "${(mAh * 1_000_000).toInt()} µAh"
        mAh < 1.0   -> String.format("%.2f mAh", mAh)
        else        -> String.format("%.2f mAh", mAh)
    }
}
