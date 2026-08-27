package com.hsm.beardylog.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WeightChartScaleCalculatorTest {
    @Test
    fun integerRangeUsesRoundedTickUnits() {
        val scale = WeightChartScaleCalculator.from(listOf(43.2f, 45f, 47.1f))

        assertEquals(2f, scale.tickStep, 0.0001f)
        assertEquals(listOf(48f, 46f, 44f, 42f), scale.ticks)
        assertEquals("46g", WeightChartScaleCalculator.formatTick(46f, scale.tickStep))
    }

    @Test
    fun decimalRangeKeepsConsistentDecimalTickUnits() {
        val scale = WeightChartScaleCalculator.from(listOf(0.2f, 0.3f))

        assertEquals(0.2f, scale.tickStep, 0.0001f)
        assertTrue(scale.ticks.all { tick -> abs(tick / scale.tickStep - (tick / scale.tickStep).toInt()) < 0.0001f })
        assertEquals("0.6g", WeightChartScaleCalculator.formatTick(0.6f, scale.tickStep))
    }
}
