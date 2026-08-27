package com.hsm.beardylog

import android.os.Bundle
import java.time.LocalDate
import java.time.YearMonth

internal class CalendarSectionState(today: LocalDate = LocalDate.now()) {
    var currentMonth: YearMonth = YearMonth.from(today)
    var selectedDate: LocalDate? = today
    var lastSelectedDate: LocalDate = today
    var detailDate: LocalDate? = null
    var scrollY: Int = 0

    fun restore(savedState: Bundle?, today: LocalDate = LocalDate.now()) {
        currentMonth = savedState?.getString(KEY_CURRENT_MONTH)?.let { value ->
            runCatching { YearMonth.parse(value) }.getOrNull()
        } ?: YearMonth.from(today)
        selectedDate = if (savedState == null) today else savedState.epochDay(KEY_SELECTED_DATE)
        lastSelectedDate = savedState?.epochDay(KEY_LAST_SELECTED_DATE) ?: selectedDate ?: today
        detailDate = savedState?.epochDay(KEY_DETAIL_DATE)
    }

    fun save(outState: Bundle) {
        outState.putString(KEY_CURRENT_MONTH, currentMonth.toString())
        selectedDate?.let { outState.putLong(KEY_SELECTED_DATE, it.toEpochDay()) }
        outState.putLong(KEY_LAST_SELECTED_DATE, lastSelectedDate.toEpochDay())
        detailDate?.let { outState.putLong(KEY_DETAIL_DATE, it.toEpochDay()) }
    }

    fun showToday(today: LocalDate = LocalDate.now()) {
        currentMonth = YearMonth.from(today)
        selectedDate = today
        lastSelectedDate = today
        scrollY = 0
    }

    fun moveMonth(offset: Long) {
        selectedDate?.let { lastSelectedDate = it }
        currentMonth = currentMonth.plusMonths(offset)
        selectedDate = lastSelectedDate.takeIf { YearMonth.from(it) == currentMonth }
        scrollY = 0
    }

    fun select(date: LocalDate) {
        selectedDate = date
        lastSelectedDate = date
    }

    fun openDetail(date: LocalDate, currentScrollY: Int) {
        scrollY = currentScrollY
        select(date)
        detailDate = date
    }

    fun returnFromDetail(date: LocalDate, resetScroll: Boolean) {
        select(date)
        detailDate = null
        if (resetScroll) scrollY = 0
    }

    fun leave() {
        detailDate = null
    }

    private fun Bundle.epochDay(key: String): LocalDate? =
        getLong(key, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let(LocalDate::ofEpochDay)

    private companion object {
        const val KEY_CURRENT_MONTH = "current_calendar_month"
        const val KEY_SELECTED_DATE = "calendar_selected_date"
        const val KEY_LAST_SELECTED_DATE = "calendar_last_selected_date"
        const val KEY_DETAIL_DATE = "calendar_detail_date"
    }
}
