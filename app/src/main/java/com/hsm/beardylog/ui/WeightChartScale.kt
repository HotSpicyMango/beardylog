package com.hsm.beardylog.ui

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

internal data class WeightChartScale(
    val min: Float,
    val max: Float,
    val tickStep: Float,
    val ticks: List<Float>
) {
    fun yFor(value: Float, top: Float, bottom: Float): Float {
        val range = max - min
        if (range <= 0f) return bottom
        return bottom - (value - min) / range * (bottom - top)
    }
}

internal object WeightChartScaleCalculator {
    private const val TARGET_INTERVALS = 4
    private const val MAX_TICKS = 6

    fun from(values: List<Float>): WeightChartScale {
        if (values.isEmpty()) return WeightChartScale(0f, 4f, 1f, listOf(4f, 3f, 2f, 1f, 0f))

        val rawMin = values.minOrNull() ?: 0f
        val rawMax = values.maxOrNull() ?: 1f
        val rawRange = rawMax - rawMin
        val padding = if (abs(rawRange) < 0.001f) {
            (abs(rawMax) * 0.05f).coerceAtLeast(1f)
        } else {
            (rawRange * 0.12f).coerceAtLeast(0.5f)
        }
        val paddedMin = (rawMin - padding).coerceAtLeast(0f)
        val paddedMax = rawMax + padding
        val step = niceCeilingStep((paddedMax - paddedMin) / TARGET_INTERVALS)
        val min = floor(paddedMin / step) * step
        val max = (ceil(paddedMax / step) * step).takeIf { it > min } ?: min + step
        return WeightChartScale(min, max, step, ticks(min, max, step))
    }

    fun formatTick(value: Float, step: Float): String {
        val snapped = if (abs(value) < 0.0001f) 0f else value
        val decimals = decimalPlaces(step)
        val number = when (decimals) {
            0 -> snapped.roundToInt().toString()
            1 -> "%.1f".format(Locale.US, snapped)
            else -> "%.2f".format(Locale.US, snapped)
        }
        return "${number}g"
    }

    private fun niceCeilingStep(rawStep: Float): Float {
        if (rawStep <= 0f || rawStep.isNaN()) return 1f
        val exponent = floor(log10(rawStep)).toInt()
        val magnitude = 10f.pow(exponent)
        val normalized = rawStep / magnitude
        val niceNormalized = when {
            normalized <= 1f -> 1f
            normalized <= 2f -> 2f
            normalized <= 5f -> 5f
            else -> 10f
        }
        return niceNormalized * magnitude
    }

    private fun ticks(min: Float, max: Float, step: Float): List<Float> {
        val count = ((max - min) / step).roundToInt().coerceAtLeast(1)
        val stride = if (count + 1 > MAX_TICKS) {
            ((count + MAX_TICKS - 1) / (MAX_TICKS - 1)).coerceAtLeast(1)
        } else {
            1
        }
        val result = mutableListOf<Float>()
        for (index in count downTo 0 step stride) {
            result += min + step * index
        }
        if (abs(result.last() - min) > 0.0001f) result += min
        return result
    }

    private fun decimalPlaces(step: Float): Int {
        var scaled = step
        repeat(2) { place ->
            if (abs(scaled - scaled.roundToInt()) < 0.0001f) return place
            scaled *= 10f
        }
        return 2
    }
}
