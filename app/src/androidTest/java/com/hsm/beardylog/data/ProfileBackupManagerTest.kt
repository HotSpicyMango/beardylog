package com.hsm.beardylog.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileBackupManagerTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AppDatabase
    private lateinit var calendarStore: CalendarEntryStore
    private lateinit var backupManager: ProfileBackupManager

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        calendarStore = CalendarEntryStore(context).also { it.importAll(emptyMap()) }
        backupManager = ProfileBackupManager(context, database)
    }

    @After
    fun tearDown() {
        calendarStore.importAll(emptyMap())
        database.close()
    }

    @Test
    fun profileAndCalendarDataRoundTripThroughBackupJson() {
        val date = LocalDate.of(2026, 8, 26)
        val reptile = Reptile(7L, "레오", "레오파드 게코", "하이 옐로우", "수컷", 20_000L, "해칭일", null, 1_000L).apply {
            hatchingDate = 20_000L
        }
        database.reptileDao().insertAll(listOf(reptile))
        calendarStore.saveCheckedValue(date, "수분 급여", true)
        calendarStore.saveTextValue(date, "memo", "백업 메모")

        val json = backupManager.createSnapshotJson()
        database.reptileDao().deleteAll()
        calendarStore.importAll(emptyMap())

        val result = backupManager.restore(backupManager.previewSnapshot(json))
        val restored = database.reptileDao().all().single()

        assertEquals(1, result.profileCount)
        assertEquals("레오", restored.name)
        assertEquals(20_000L, restored.hatchingDate)
        assertTrue(calendarStore.checkedValue(date, "수분 급여"))
        assertEquals("백업 메모", calendarStore.textValue(date, "memo"))
    }
}
