package com.hsm.beardylog.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.hsm.beardylog.appColor
import kotlin.math.roundToInt

class WeightChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val numberTypeface = resources.getFont(com.hsm.beardylog.R.font.d2)
    private val regularTypeface = resources.getFont(com.hsm.beardylog.R.font.pretendard_regular)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.appColor(com.hsm.beardylog.R.color.forest_light); strokeWidth = density }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.appColor(com.hsm.beardylog.R.color.forest); strokeWidth = 3f * density; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.appColor(com.hsm.beardylog.R.color.forest) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.appColor(com.hsm.beardylog.R.color.text_secondary)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        typeface = regularTypeface
    }
    private val tooltipBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.appColor(com.hsm.beardylog.R.color.text_primary) }
    private val tooltipWeightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.appColor(com.hsm.beardylog.R.color.surface_card)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        textAlign = Paint.Align.LEFT
        typeface = numberTypeface
    }
    private val tooltipDatePaint = Paint(tooltipWeightPaint).apply { typeface = numberTypeface }
    // onDraw는 스크롤/터치마다 매 프레임 호출될 수 있어서, 그때마다 새로 만들지 않고 재사용한다.
    private val linePath = Path()
    private val tooltipBounds = RectF()
    private var values: List<Float> = emptyList()
    private var xLabels: List<String> = emptyList()
    private var selectedIndex: Int? = null

    fun setValues(next: List<Float>, labels: List<String> = emptyList()) {
        values = next
        xLabels = labels
        selectedIndex = null
        contentDescription = next.lastOrNull()?.let { "최근 무게 ${formatWeight(it)}" } ?: "무게 기록 없음"
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
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
        val scale = WeightChartScaleCalculator.from(values)
        scale.ticks.forEach { value ->
            val y = scale.yFor(value, top, bottom)
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        if (values.isEmpty()) return
        linePath.reset()
        values.forEachIndexed { index, value ->
            val x = pointX(index, left, right)
            val y = scale.yFor(value, top, bottom)
            if (index == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
            canvas.drawCircle(x, y, 4.5f * density, pointPaint)
        }
        if (values.size > 1) canvas.drawPath(linePath, linePaint)
        if (xLabels.isNotEmpty()) {
            labelPaint.textAlign = Paint.Align.CENTER
            val labelStep = if (xLabels.size <= 5) 1 else ((xLabels.lastIndex + 4) / 5).coerceAtLeast(1)
            (0..xLabels.lastIndex step labelStep).toMutableList().apply {
                if (last() != xLabels.lastIndex) add(xLabels.lastIndex)
            }.distinct().forEach { index ->
                val x = pointX(index.coerceAtMost(values.lastIndex), left, right)
                canvas.drawText(xLabels[index], x, height - paddingBottom.toFloat() - 2f * resources.displayMetrics.density, labelPaint)
            }
        }
        selectedIndex?.takeIf { it in values.indices }?.let { index ->
            drawTooltip(canvas, index, left, right, scale)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (values.isEmpty()) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> true
            MotionEvent.ACTION_UP -> {
                val left = paddingLeft.toFloat()
                val right = width - paddingRight.toFloat()
                val tappedIndex = if (values.size == 1) 0 else {
                    (((event.x - left) / (right - left)) * values.lastIndex).roundToInt().coerceIn(values.indices)
                }
                selectedIndex = if (selectedIndex == tappedIndex) null else tappedIndex
                invalidate()
                performClick()
                true
            }
            MotionEvent.ACTION_CANCEL -> false
            else -> true
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun drawTooltip(canvas: Canvas, index: Int, left: Float, right: Float, scale: WeightChartScale) {
        val x = pointX(index, left, right)
        val chartTop = paddingTop.toFloat()
        val chartBottom = height - paddingBottom.toFloat() - 20f * resources.displayMetrics.density
        val y = scale.yFor(values[index], chartTop, chartBottom)
        val dateLabel = xLabels.getOrNull(index).orEmpty()
        val separator = if (dateLabel.isEmpty()) "" else " · "
        val weightLabel = formatWeight(values[index])
        val horizontalPadding = 10f * density
        val tooltipWidth = tooltipDatePaint.measureText(dateLabel + separator) + tooltipWeightPaint.measureText(weightLabel) + horizontalPadding * 2
        val tooltipHeight = 30f * density
        val centerX = x.coerceIn(tooltipWidth / 2f, width - tooltipWidth / 2f)
        val tooltipTop = if (y - tooltipHeight - 8f * density >= 0f) y - tooltipHeight - 8f * density else y + 8f * density
        tooltipBounds.set(centerX - tooltipWidth / 2f, tooltipTop, centerX + tooltipWidth / 2f, tooltipTop + tooltipHeight)
        canvas.drawRoundRect(tooltipBounds, 8f * density, 8f * density, tooltipBackgroundPaint)
        val baseline = tooltipBounds.centerY() - (tooltipWeightPaint.descent() + tooltipWeightPaint.ascent()) / 2f
        var textX = tooltipBounds.left + horizontalPadding
        if (dateLabel.isNotEmpty()) {
            canvas.drawText(dateLabel + separator, textX, baseline, tooltipDatePaint)
            textX += tooltipDatePaint.measureText(dateLabel + separator)
        }
        canvas.drawText(weightLabel, textX, baseline, tooltipWeightPaint)
    }

    private fun pointX(index: Int, left: Float, right: Float): Float = if (values.size <= 1) {
        (left + right) / 2f
    } else {
        left + (right - left) * index / values.lastIndex.toFloat()
    }

    private fun formatWeight(value: Float): String = if (value % 1f == 0f) "${value.toInt()}g" else "%.1fg".format(java.util.Locale.US, value)
}
