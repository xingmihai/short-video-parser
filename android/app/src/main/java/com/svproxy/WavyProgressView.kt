package com.svproxy

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.google.android.material.color.MaterialColors
import kotlin.math.PI
import kotlin.math.sin

/**
 * M3 Expressive 风格的实时波形进度：两条错相位的正弦波 + 可调厚度，
 * 颜色取自当前主题（动态色开启时跟随壁纸）。不依赖任何 JS / 原生库。
 */
class WavyProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val primary = MaterialColors.getColor(
        context, androidx.appcompat.R.attr.colorPrimary, Color.parseColor("#6750A4")
    )
    private val tertiary = MaterialColors.getColor(
        context, com.google.android.material.R.attr.colorTertiary, Color.parseColor("#7D5260")
    )

    private val front = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 11f
        color = primary
    }
    private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 6f
        color = tertiary
        alpha = 140
    }

    private val path = Path()
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    fun setRunning(on: Boolean) {
        if (on) {
            if (!animator.isRunning) animator.start()
        } else {
            animator.cancel()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val mid = h / 2f
        val steps = (w / 4f).toInt().coerceAtLeast(24)
        val cycles = 3f

        drawWave(canvas, front, w, mid, h * 0.30f, cycles, phase, steps)
        drawWave(canvas, back, w, mid, h * 0.21f, cycles, phase + 0.38f, steps)
    }

    private fun drawWave(
        canvas: Canvas,
        paint: Paint,
        w: Float,
        mid: Float,
        amplitude: Float,
        cycles: Float,
        phase: Float,
        steps: Int,
    ) {
        path.reset()
        for (i in 0..steps) {
            val ratio = i.toFloat() / steps
            val x = w * ratio
            // 两端收窄，形成呼吸感
            val envelope = sin(PI * ratio).toFloat()
            val y = mid + amplitude * envelope * sin(2.0 * PI * (cycles * ratio + phase)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}
