package com.privacywarden.app.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Pseudo-3D battery illustration for the Benefits dashboard.
 *
 * Renders a battery viewed slightly from the front-top, with:
 *   • A rounded body given depth via left/right highlight+shadow gradients
 *   • A raised terminal cap on top with its own edge shadow
 *   • An animated liquid-style fill that rises from the bottom and has
 *     a subtle wave on the top surface
 *   • A glass-reflection streak down the left face
 *   • A center label (big value + small subtitle)
 *
 * No image assets needed — pure Canvas, so it scales to any density.
 */
class Battery3DView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, def: Int = 0
) : View(ctx, attrs, def) {

    // palette
    private val shellLight = 0xFF2A1416.toInt()
    private val shellDark  = 0xFF0D0607.toInt()
    private val capLight   = 0xFF3A1A1E.toInt()
    private val capDark    = 0xFF1A0B0D.toInt()
    private val borderHi   = 0xFF4B2328.toInt()
    private val borderLo   = 0xFF180B0D.toInt()
    private val liquidTop  = 0xFFFF6B6B.toInt()   // bright red top of the fluid
    private val liquidMid  = 0xFFEF4444.toInt()
    private val liquidDark = 0xFF8B1111.toInt()
    private val reflection = 0x33FFFFFF
    private val textHi     = 0xFFF7F2F2.toInt()
    private val textMid    = 0xFF9E9596.toInt()
    private val sparkle    = 0x88FFD8D8.toInt()

    // animation / state
    private var targetFill: Float = 0f       // 0..1
    private var currentFill: Float = 0f      // 0..1 (animated)
    private var wavePhase: Float = 0f
    private var animator: ValueAnimator? = null
    private var waveAnimator: ValueAnimator? = null

    var valueText: String = "0 mAh"
    var labelText: String = "Blocks saved"
    // Progress caption shown inside the battery, under the value (e.g. "30 / 500 blocks")
    var progressText: String = ""
    // Big number rendered on the right-hand side (e.g. "30 blocks")
    var rightHeadline: String = "0 blocks"

    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val liquidPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textHi
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textMid
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = sparkle
        style = Paint.Style.FILL
    }

    init {
        startWaveAnimation()
    }

    /** Call with 0..1. Pass the fraction of a conceptual "tank" that's full. */
    fun setFill(fraction: Float, animate: Boolean = true) {
        val f = fraction.coerceIn(0f, 1f)
        targetFill = f
        animator?.cancel()
        if (!animate) {
            currentFill = f
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(currentFill, f).apply {
            duration = 900
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                currentFill = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startWaveAnimation() {
        waveAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 2600
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                wavePhase = it.animatedValue as Float
                if (currentFill > 0.02f) invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        waveAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val pad = dp(14f)
        // Reserve space on the right for the label, left 60% for the battery
        val batAreaRight = w * 0.55f
        val batWidth = batAreaRight - pad * 2
        val capHeight = dp(14f)
        val capWidth = batWidth * 0.38f
        val bodyTop = pad + capHeight
        val bodyBottom = h - pad
        val bodyLeft = pad
        val bodyRight = pad + batWidth
        val bodyRadius = dp(14f)

        // ── 1) Cap on top ────────────────────────────────────────────────
        val capLeft = bodyLeft + (batWidth - capWidth) / 2f
        val capRight = capLeft + capWidth
        val capTop = pad
        val capRect = RectF(capLeft, capTop, capRight, bodyTop + dp(2f))
        shellPaint.shader = LinearGradient(
            capLeft, capTop, capLeft, bodyTop,
            capLight, capDark, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(capRect, dp(5f), dp(5f), shellPaint)
        // thin highlight on top of cap
        highlightPaint.color = 0x44FFFFFF
        canvas.drawRoundRect(
            RectF(capLeft + dp(3f), capTop + dp(2f), capRight - dp(3f), capTop + dp(5f)),
            dp(2f), dp(2f), highlightPaint
        )

        // ── 2) Body shell with left-highlight/right-shadow gradient ──────
        val bodyRect = RectF(bodyLeft, bodyTop, bodyRight, bodyBottom)
        shellPaint.shader = LinearGradient(
            bodyLeft, bodyTop, bodyRight, bodyTop,
            intArrayOf(shellLight, shellDark, shellDark, 0xFF060304.toInt()),
            floatArrayOf(0f, 0.35f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(bodyRect, bodyRadius, bodyRadius, shellPaint)

        // stroke border (two-tone — light at top-left, dark at bottom-right)
        strokePaint.shader = LinearGradient(
            bodyLeft, bodyTop, bodyRight, bodyBottom,
            borderHi, borderLo, Shader.TileMode.CLAMP
        )
        strokePaint.strokeWidth = dp(1.5f)
        canvas.drawRoundRect(bodyRect, bodyRadius, bodyRadius, strokePaint)

        // inner inset (padding around the liquid)
        val inset = dp(5f)
        val innerRect = RectF(
            bodyLeft + inset, bodyTop + inset,
            bodyRight - inset, bodyBottom - inset
        )
        val innerRadius = bodyRadius - inset + dp(1f)

        // ── 3) Liquid fill clipped to the inner rect ─────────────────────
        canvas.save()
        val clipPath = Path().apply {
            addRoundRect(innerRect, innerRadius, innerRadius, Path.Direction.CW)
        }
        canvas.clipPath(clipPath)

        val fillH = innerRect.height() * currentFill
        val fillTop = innerRect.bottom - fillH

        // Build liquid with wave on top
        val liquidPath = Path()
        liquidPath.moveTo(innerRect.left, innerRect.bottom)
        liquidPath.lineTo(innerRect.left, fillTop)
        if (currentFill > 0.02f) {
            val amp = dp(2.5f)
            val waveW = innerRect.width()
            val steps = 20
            for (i in 0..steps) {
                val x = innerRect.left + waveW * (i / steps.toFloat())
                val phase = wavePhase + (i / steps.toFloat()) * (Math.PI * 2).toFloat()
                val y = fillTop + (Math.sin(phase.toDouble()) * amp).toFloat()
                liquidPath.lineTo(x, y)
            }
        } else {
            liquidPath.lineTo(innerRect.right, fillTop)
        }
        liquidPath.lineTo(innerRect.right, innerRect.bottom)
        liquidPath.close()

        liquidPaint.shader = LinearGradient(
            innerRect.left, fillTop, innerRect.left, innerRect.bottom,
            intArrayOf(liquidTop, liquidMid, liquidDark),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(liquidPath, liquidPaint)

        // Top surface highlight (thin light strip)
        if (currentFill > 0.04f) {
            highlightPaint.color = 0x66FFFFFF
            canvas.drawRect(
                innerRect.left, fillTop - dp(1f),
                innerRect.right, fillTop + dp(1f),
                highlightPaint
            )
        }

        // Small bubbles floating up (static positions, twinkle by wavePhase)
        if (currentFill > 0.15f) {
            val bubbles = arrayOf(
                Triple(0.20f, 0.35f, 2.0f),
                Triple(0.55f, 0.65f, 3.0f),
                Triple(0.75f, 0.25f, 1.6f),
                Triple(0.35f, 0.80f, 2.4f),
            )
            for ((fx, fy, r) in bubbles) {
                val x = innerRect.left + innerRect.width() * fx
                val y = innerRect.bottom - innerRect.height() * fy * currentFill
                if (y < fillTop + dp(6f)) continue
                val alpha = (0.5f + 0.5f * Math.sin(wavePhase.toDouble() * (1f + fx * 1.3f))).toFloat()
                sparklePaint.alpha = (200 * alpha).toInt().coerceIn(0, 255)
                canvas.drawCircle(x, y, dp(r), sparklePaint)
            }
        }

        // Glass reflection down the left face
        highlightPaint.shader = LinearGradient(
            innerRect.left, innerRect.top, innerRect.left + innerRect.width() * 0.25f, innerRect.top,
            0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        canvas.drawRect(
            innerRect.left, innerRect.top,
            innerRect.left + innerRect.width() * 0.25f, innerRect.bottom,
            highlightPaint
        )
        highlightPaint.shader = null
        canvas.restore()

        // outer top-edge highlight (thin light strip under the cap)
        highlightPaint.color = 0x22FFFFFF
        canvas.drawRect(
            bodyLeft + dp(4f), bodyTop + dp(1f),
            bodyRight - dp(4f), bodyTop + dp(2.5f),
            highlightPaint
        )

        // ── 4) Value + progress caption inside the battery ───────────────
        // We do NOT render a "%" — that would mislead the user into thinking
        // this is a fraction of their phone's battery. Instead we show the
        // actual saved amount (e.g. "105 µAh") and, below it, a progress
        // caption (e.g. "30 / 500 blocks") that explains what the fill level
        // is tracking.
        val cx = (bodyLeft + bodyRight) / 2f
        val cy = (bodyTop + bodyBottom) / 2f

        textPaint.color = textHi
        textPaint.textSize = dp(15f)
        val fm = textPaint.fontMetrics
        canvas.drawText(valueText, cx, cy - (fm.ascent + fm.descent) / 2f - dp(4f), textPaint)

        if (progressText.isNotEmpty()) {
            subPaint.color = 0xFFE8C5C5.toInt()
            subPaint.textAlign = Paint.Align.CENTER
            subPaint.textSize = dp(10f)
            canvas.drawText(progressText, cx, cy + dp(14f), subPaint)
        }

        // Right-side labels — show blocks saved (what the fill tracks), not
        // the mAh (already rendered inside the battery). Keeps both numbers
        // visible without duplication and makes the fill meaning explicit.
        val rx = batAreaRight + dp(12f)
        val rw = w - rx - pad
        textPaint.textSize = dp(22f)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = textHi
        canvas.drawText(rightHeadline, rx, pad + dp(26f), textPaint)

        subPaint.textSize = dp(12f)
        subPaint.textAlign = Paint.Align.LEFT
        subPaint.color = textMid
        canvas.drawText(labelText, rx, pad + dp(26f) + dp(18f), subPaint)
        subPaint.color = 0xFF9E7A7C.toInt()
        canvas.drawText("live", rx, pad + dp(26f) + dp(36f), subPaint)

        // Thin bolt icon under the label
        drawBolt(canvas, rx, pad + dp(26f) + dp(50f), dp(28f))
        // "vs. no-panic baseline" text
        subPaint.color = textMid
        subPaint.textSize = dp(11f)
        subPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Charged by", rx + dp(34f), pad + dp(26f) + dp(62f), subPaint)
        textPaint.color = 0xFFFF8080.toInt()
        textPaint.textSize = dp(13f)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Privacy Warden", rx + dp(34f), pad + dp(26f) + dp(78f), textPaint)
    }

    private fun drawBolt(c: Canvas, x: Float, y: Float, size: Float) {
        val p = Path()
        p.moveTo(x + size * 0.35f, y)
        p.lineTo(x, y + size * 0.55f)
        p.lineTo(x + size * 0.30f, y + size * 0.55f)
        p.lineTo(x + size * 0.18f, y + size)
        p.lineTo(x + size * 0.60f, y + size * 0.40f)
        p.lineTo(x + size * 0.30f, y + size * 0.40f)
        p.lineTo(x + size * 0.55f, y)
        p.close()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            x, y, x, y + size,
            liquidTop, liquidDark, Shader.TileMode.CLAMP
        )
        c.drawPath(p, paint)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
