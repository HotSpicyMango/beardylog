package com.hsm.beardylog.data

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CareScheduleTest {
    private val monday = LocalDate.of(2026, 11, 9)

    private fun schedule(type: String = "충식", date: LocalDate = monday, repeatDay: Int? = null) =
        CareSchedule().apply {
            careType = type
            scheduledDate = date.toEpochDay()
            repeatDayOfWeek = repeatDay
        }

    @Test
    fun oneTimeScheduleMatchesOnlyItsOwnDate() {
        val oneTime = schedule()
        assertTrue(oneTime.isScheduledOn(monday))
        assertFalse(oneTime.isScheduledOn(monday.plusDays(1)))
    }

    @Test
    fun weeklyScheduleMatchesItsWeekdayOnAnyDate() {
        val weeklyMonday = schedule(repeatDay = 1)
        assertTrue(weeklyMonday.isScheduledOn(monday.plusWeeks(5)))
        assertFalse(weeklyMonday.isScheduledOn(monday.plusDays(1)))
    }

    @Test
    fun onlyDietTypesCountAsDiet() {
        CareSchedule.DIET_TYPES.forEach { assertTrue(schedule(type = it).isDiet()) }
        assertFalse(schedule(type = "병원").isDiet())
    }
}
