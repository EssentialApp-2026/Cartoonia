package com.essentialapp.sam

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/** Sfera animata che "respira" e cambia colore secondo lo stato di SAM. */
class OrbView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 8f
    }
    private var phase = 0f
    private var mode = Mode.IDLE

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    init { anim.start() }

    fun setMode(m: Mode) { if (m != mode) { mode = m; invalidate() } }

    private fun colorFor(): Int = when (mode) {
        Mode.IDLE -> Color.rgb(90, 150, 230)
        Mode.LISTENING -> Color.rgb(70, 210, 140)
        Mode.THINKING -> Color.rgb(170, 120, 230)
        Mode.SPEAKING -> Color.rgb(240, 180, 70)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val base = minOf(width, height) / 2f * 0.72f
        // respiro: ampiezza diversa secondo lo stato
        val amp = when (mode) {
            Mode.LISTENING -> 0.14f; Mode.SPEAKING -> 0.10f
            Mode.THINKING -> 0.06f; Mode.IDLE -> 0.05f
        }
        val breath = kotlin.math.sin(phase * 2 * Math.PI).toFloat()
        val r = base * (1f + amp * breath)
        if (r < 2f) return            // vista non ancora misurata
        val c = colorFor()

        paint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(c, (c and 0x00FFFFFF) or 0x66000000.toInt(), (c and 0x00FFFFFF)),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)

        // anello che gira quando "pensa"
        if (mode == Mode.THINKING) {
            ring.color = (c and 0x00FFFFFF) or 0xCC000000.toInt()
            val sweep = 90f
            val start = phase * 360f
            canvas.drawArc(cx - r - 18, cy - r - 18, cx + r + 18, cy + r + 18, start, sweep, false, ring)
        }
    }

    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
}
