package com.hsm.beardylog.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.hsm.beardylog.appColor

class WeightAxisLabelsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val numberTypeface = resources.getFont(com.hsm.beardylog.R.font.d2)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.appColor(com.hsm.beardylog.R.color.text_secondary)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        textAlign = Paint.Align.RIGHT
        typeface = numberTypeface
    }
    private var values: List<Float> = emptyList()

    fun setValues(next: List<Float>) { values = next; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val top = paddingTop.toFloat()
        val bottom = height - paddingBottom.toFloat() - 20f * resources.displayMetrics.density
        val scale = WeightChartScaleCalculator.from(values)
        scale.ticks.forEach { value ->
            val y = scale.yFor(value, top, bottom)
            val baseline = y - (labelPaint.ascent() + labelPaint.descent()) / 2f
            canvas.drawText(
                WeightChartScaleCalculator.formatTick(value, scale.tickStep),
                width - 4f * resources.displayMetrics.density,
                baseline,
                labelPaint
            )
        }
    }
}
