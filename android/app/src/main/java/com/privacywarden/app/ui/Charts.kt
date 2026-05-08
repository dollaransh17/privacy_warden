package com.privacywarden.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Tiny custom chart widgets drawn purely with Canvas/Paint.
 *
 * We avoid pulling in MPAndroidChart so the APK stays slim and looks consistent
 * with the rest of the Sentinel-style UI. Three widgets:
 *
 *   • SparklineView — area-filled line chart for time series.
 *   • PieChartView  — donut chart with legend baked in.
 *   • BarChartView  — horizontal bar chart for category breakdowns.
 */

class SparklineView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null,
) : View(ctx, attrs) {

    var values: IntArray = IntArray(0)
        set(v) { field = v; invalidate() }
    var lineColor: Int = Color.parseColor("#60A5FA")
        set(v) { field = v; invalidate() }
    var fillColor: Int = Color.parseColor("#33" + "60A5FA")
        set(v) { field = v; invalidate() }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F2937"); strokeWidth = 1f; style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // baseline + horizontal guides
        gridPaint.color = Color.parseColor("#1F2937")
        for (i in 0..3) {
            val y = h * (i / 3f)
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        if (values.isEmpty()) return
        val maxV = (values.max().coerceAtLeast(1)).toFloat()
        val n = values.size
        val stepX = if (n > 1) w / (n - 1) else w
        val pts = FloatArray(n * 2)
        for (i in 0 until n) {
            pts[i * 2]     = i * stepX
            pts[i * 2 + 1] = h - (values[i] / maxV) * (h * 0.85f) - h * 0.05f
        }
        // fill
        val fillPath = Path().apply {
            moveTo(pts[0], h)
            lineTo(pts[0], pts[1])
            for (i in 1 until n) lineTo(pts[i * 2], pts[i * 2 + 1])
            lineTo(pts[(n - 1) * 2], h)
            close()
        }
        fillPaint.color = fillColor
        canvas.drawPath(fillPath, fillPaint)
        // line
        linePaint.color = lineColor
        val linePath = Path().apply {
            moveTo(pts[0], pts[1])
            for (i in 1 until n) lineTo(pts[i * 2], pts[i * 2 + 1])
        }
        canvas.drawPath(linePath, linePaint)
        // last-point dot
        canvas.drawCircle(pts[(n - 1) * 2], pts[(n - 1) * 2 + 1], 6f, fillPaint.apply { color = lineColor })
    }
}

class PieChartView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null,
) : View(ctx, attrs) {

    /** label → (value, color) */
    var data: List<Triple<String, Int, Int>> = emptyList()
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0B0D10"); style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E7EAF0")
        textSize = 28f; typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7A8492")
        textSize = 18f
        textAlign = Paint.Align.CENTER
    }
    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B9C0CC"); textSize = 22f
    }
    private val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onDraw(canvas: Canvas) {
        val padding = 12f
        val pieSize = (height - padding * 2)
        val pieLeft = padding
        val pieTop = padding
        val rect = RectF(pieLeft, pieTop, pieLeft + pieSize, pieTop + pieSize)
        val total = data.sumOf { it.second }
        if (total == 0) {
            paint.color = Color.parseColor("#1F2937")
            canvas.drawArc(rect, 0f, 360f, true, paint)
            ringPaint.color = Color.parseColor("#0B0D10")
            canvas.drawCircle(rect.centerX(), rect.centerY(), pieSize / 2f * 0.55f, ringPaint)
            canvas.drawText("0", rect.centerX(), rect.centerY() + 4f, labelPaint)
            canvas.drawText("events", rect.centerX(), rect.centerY() + 28f, subPaint)
            return
        }
        var start = -90f
        for ((_, value, color) in data) {
            val sweep = (value.toFloat() / total) * 360f
            paint.color = color
            canvas.drawArc(rect, start, sweep, true, paint)
            start += sweep
        }
        // donut hole
        canvas.drawCircle(rect.centerX(), rect.centerY(), pieSize / 2f * 0.55f, ringPaint)
        canvas.drawText(total.toString(), rect.centerX(), rect.centerY() + 4f, labelPaint)
        canvas.drawText("events", rect.centerX(), rect.centerY() + 28f, subPaint)

        // legend on the right
        var ly = padding + 10f
        val lx = pieLeft + pieSize + 24f
        for ((label, value, color) in data) {
            swatchPaint.color = color
            canvas.drawRoundRect(lx, ly, lx + 18f, ly + 18f, 4f, 4f, swatchPaint)
            val pct = (value * 100f / total).toInt()
            canvas.drawText("$label  ·  $value  ($pct%)", lx + 28f, ly + 16f, legendPaint)
            ly += 30f
        }
    }
}

class BarChartView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null,
) : View(ctx, attrs) {

    /** label → value */
    var data: List<Pair<String, Int>> = emptyList()
        set(v) { field = v; invalidate() }
    var barColor: Int = Color.parseColor("#60A5FA")
        set(v) { field = v; invalidate() }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E7EAF0"); textSize = 24f
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B9C0CC"); textSize = 22f; textAlign = Paint.Align.RIGHT
        typeface = Typeface.DEFAULT_BOLD
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F2937"); style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        if (data.isEmpty()) {
            labelPaint.color = Color.parseColor("#7A8492")
            canvas.drawText("No data yet", 0f, 30f, labelPaint)
            labelPaint.color = Color.parseColor("#E7EAF0")
            return
        }
        val max = (data.maxOf { it.second }).coerceAtLeast(1)
        val rowH = (height.toFloat() / data.size).coerceAtMost(64f)
        var y = 0f
        for ((label, v) in data) {
            // label
            canvas.drawText(label, 0f, y + rowH * 0.45f, labelPaint)
            // track
            val trackTop = y + rowH * 0.55f
            val trackBot = y + rowH * 0.85f
            canvas.drawRoundRect(0f, trackTop, width.toFloat(), trackBot, 6f, 6f, trackPaint)
            // bar
            val frac = v.toFloat() / max
            barPaint.color = barColor
            canvas.drawRoundRect(0f, trackTop, width.toFloat() * frac, trackBot, 6f, 6f, barPaint)
            // value
            canvas.drawText(v.toString(), width.toFloat(), y + rowH * 0.45f, valuePaint)
            y += rowH
        }
    }
}
