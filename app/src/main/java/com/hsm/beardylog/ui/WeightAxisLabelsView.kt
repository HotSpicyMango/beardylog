package com.hsm.beardylog.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Locale

class WeightAxisLabelsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(com.hsm.beardylog.R.color.text_secondary)
        textSize = 11f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.RIGHT
    }
    private var values: List<Float> = emptyList()

    fun setValues(next: List<Float>) { values = next; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val top = paddingTop.toFloat()
        val bottom = height - paddingBottom.toFloat() - 20f * resources.displayMetrics.density
        val min = values.minOrNull() ?: 0f
        val max = values.maxOrNull() ?: 1f
        val range = (max - min).coerceAtLeast(1f)
        repeat(4) { index ->
            val value = max - range * index / 3f
            val y = top + 4f * resources.displayMetrics.density + (bottom - top) * index / 3f
            canvas.drawText(formatWeight(value), width - 4f * resources.displayMetrics.density, y, labelPaint)
        }
    }

    private fun formatWeight(value: Float): String = if (value % 1f == 0f) "${value.toInt()}g" else "%.1fg".format(Locale.US, value)
}
