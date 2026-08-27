package com.hsm.beardylog.data

import android.content.Context
import java.time.LocalDate

enum class WeightChartPeriod(val displayName: String) {
    ONE_WEEK("최근 1주"),
    ONE_MONTH("최근 1개월"),
    THREE_MONTHS("최근 3개월"),
    ALL("전체");

    fun cutoffEpochDay(today: LocalDate = LocalDate.now()): Long = when (this) {
        ONE_WEEK -> today.minusDays(6).toEpochDay()
        ONE_MONTH -> today.minusMonths(1).toEpochDay()
        THREE_MONTHS -> today.minusMonths(3).toEpochDay()
        ALL -> Long.MIN_VALUE
    }
}

object WeightChartPreferences {
    const val KEY_HOME_PERIOD = "home_weight_chart_period"
    val DEFAULT_HOME_PERIOD = WeightChartPeriod.ONE_MONTH

    fun homePeriod(context: Context): WeightChartPeriod {
        val stored = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HOME_PERIOD, null)
        return stored?.let { value ->
            runCatching { WeightChartPeriod.valueOf(value) }.getOrNull()
        } ?: DEFAULT_HOME_PERIOD
    }

    fun setHomePeriod(context: Context, period: WeightChartPeriod) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOME_PERIOD, period.name)
            .apply()
    }

    private const val PREFERENCES_NAME = "app_settings"
}
