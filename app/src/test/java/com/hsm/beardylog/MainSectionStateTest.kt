package com.hsm.beardylog

import com.hsm.beardylog.data.Reptile
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainSectionStateTest {
    @Test
    fun calendarTransitionsKeepSelectionAndDetailStateConsistent() {
        val today = LocalDate.of(2026, 8, 27)
        val state = CalendarSectionState(today)

        state.moveMonth(1)
        assertEquals(YearMonth.of(2026, 9), state.currentMonth)
        assertNull(state.selectedDate)

        val selected = LocalDate.of(2026, 9, 12)
        state.openDetail(selected, currentScrollY = 120)
        assertEquals(selected, state.detailDate)
        assertEquals(120, state.scrollY)

        state.returnFromDetail(selected, resetScroll = true)
        assertNull(state.detailDate)
        assertEquals(0, state.scrollY)
    }

    @Test
    fun memorialStateSortsNewestFirstAndClosesDetailOnce() {
        val state = MemorialSectionState().apply { detailId = 2L }
        val profiles = listOf(
            reptile(1L, "먼저", deathDate = 20L),
            reptile(2L, "나중", deathDate = 30L),
            reptile(3L, "현재", deathDate = null)
        )

        assertEquals(listOf("나중", "먼저"), state.deceasedProfiles(profiles).map { it.name })
        assertTrue(state.closeDetail())
        assertFalse(state.closeDetail())
    }

    private fun reptile(id: Long, name: String, deathDate: Long?): Reptile =
        Reptile(id, name, "", "", "", 0L, "해칭일", null, 0L).apply {
            this.deathDate = deathDate
        }
}
