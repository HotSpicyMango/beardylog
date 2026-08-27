package com.hsm.beardylog.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.widget.TextView
import com.hsm.beardylog.R

private val weightNumberPattern = Regex(
    "[+-]?\\d+(?:[.,]\\d+)?\\s*(?:kg|mg|g|개월|주|%)"
)

fun CharSequence.withWeightNumberTypeface(context: Context): CharSequence {
    val styled = SpannableString(this)
    val typeface = context.resources.getFont(R.font.d2)
    weightNumberPattern.findAll(this).forEach { match ->
        styled.setSpan(
            ExactTypefaceSpan(typeface),
            match.range.first,
            match.range.last + 1,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    return styled
}

fun TextView.setWeightNumberText(value: CharSequence) {
    text = value.withWeightNumberTypeface(context)
}

private class ExactTypefaceSpan(private val targetTypeface: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(textPaint: TextPaint) = applyTypeface(textPaint)

    override fun updateMeasureState(textPaint: TextPaint) = applyTypeface(textPaint)

    private fun applyTypeface(paint: Paint) {
        val originalStyle = paint.typeface?.style ?: Typeface.NORMAL
        paint.typeface = Typeface.create(targetTypeface, originalStyle)
    }
}
