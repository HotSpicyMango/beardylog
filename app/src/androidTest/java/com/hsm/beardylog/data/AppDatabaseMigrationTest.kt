package com.hsm.beardylog.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.testing.MigrationTestHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** schemas/.../9.json은 10.json에서 ddayEndDate 컬럼만 뺀 사본이다(9버전 코드는 스키마를 내보내지 않았다).
 *  shortcut: 9→10만 검증한다. 2~8 스키마 기록이 없어서, 다음 스키마 변경부터는 버전마다 이런 테스트를 하나씩 더한다. */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migrate9To10KeepsClutchesAndLeavesDdayOpen() {
        helper.createDatabase(TEST_DB, 9).apply {
            execSQL("INSERT INTO breeding_pairs (id, maleName, femaleName, matingDate, sortOrder, createdAt) VALUES (1, 'm', 'f', 100, 0, 1)")
            execSQL("INSERT INTO clutches (id, pairId, clutchNumber, layingDate, infertileEggCount, fertileEggCount, lostEggCount, createdAt) VALUES (7, 1, 1, 200, 1, 5, 0, 2)")
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, AppDatabase.MIGRATION_9_10)
        db.query("SELECT fertileEggCount, ddayEndDate FROM clutches WHERE id = 7").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(5, cursor.getInt(0))
            assertTrue(cursor.isNull(1))
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
