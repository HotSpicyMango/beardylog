package com.hsm.beardylog.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

class WeightChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(225, 232, 228); strokeWidth = 1f }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(com.hsm.beardylog.R.color.forest); strokeWidth = 5f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(com.hsm.beardylog.R.color.forest) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(com.hsm.beardylog.R.color.text_secondary)
        textSize = 11f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private var values: List<Float> = emptyList()
    private var xLabels: List<String> = emptyList()
    fun setValues(next: List<Float>, labels: List<String> = emptyList()) {
        values = next
        xLabels = labels
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val pointWidth = 56f * density
        val desiredWidth = (paddingLeft + paddingRight +
            pointWidth * values.size.coerceAtLeast(5)).roundToInt()
        val measuredWidth = resolveSize(desiredWidth, widthMeasureSpec)
        val measuredHeight = resolveSize((170f * density).roundToInt(), heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val right = width - paddingRight.toFloat()
        val bottom = height - paddingBottom.toFloat() - 20f * resources.displayMetrics.density
        repeat(4) { index -> canvas.drawLine(left, top + (bottom - top) * index / 3f, right, top + (bottom - top) * index / 3f, gridPaint) }
        if (values.size < 2) return
        val min = values.minOrNull() ?: 0f
        val max = values.maxOrNull() ?: 1f
        val range = (max - min).coerceAtLeast(1f)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = left + (right - left) * index / values.lastIndex.toFloat(); val y = bottom - (value - min) / range * (bottom - top)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y); canvas.drawCircle(x, y, 6f, pointPaint)
        }
        canvas.drawPath(path, linePaint)
        if (xLabels.isNotEmpty()) {
            labelPaint.textAlign = Paint.Align.CENTER
            val labelStep = if (xLabels.size <= 5) 1 else ((xLabels.lastIndex + 4) / 5).coerceAtLeast(1)
            (0..xLabels.lastIndex step labelStep).toMutableList().apply {
                if (last() != xLabels.lastIndex) add(xLabels.lastIndex)
            }.distinct().forEach { index ->
                val x = left + (right - left) * index / xLabels.lastIndex.coerceAtLeast(1).toFloat()
                canvas.drawText(xLabels[index], x, height - paddingBottom.toFloat() - 2f * resources.displayMetrics.density, labelPaint)
            }
        }
    }

    private fun formatWeight(value: Float): String = if (value % 1f == 0f) "${value.toInt()}g" else "%.1fg".format(java.util.Locale.US, value)
}
