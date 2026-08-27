package com.hsm.beardylog

import android.content.Context
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityStateTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun disableStartupUpdateCheck() {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("check_updates_on_start", false)
            .commit()
    }

    @After
    fun clearStartupUpdateCheckOverride() {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit()
            .remove("check_updates_on_start")
            .commit()
    }

    @Test
    fun calendarStateSurvivesRecreationAndBackReturnsHome() {
        val expectedMonth = YearMonth.now().plusMonths(1)
            .format(DateTimeFormatter.ofPattern("yyyy년 M월"))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.nav_calendar
                activity.findViewById<TextView>(R.id.calendar_next_month_button).performClick()
            }

            scenario.recreate()

            scenario.onActivity { activity ->
                assertEquals(
                    R.id.nav_calendar,
                    activity.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId
                )
                assertEquals(expectedMonth, activity.findViewById<TextView>(R.id.calendar_month_title).text.toString())

                activity.onBackPressedDispatcher.onBackPressed()

                assertEquals(
                    R.id.nav_home,
                    activity.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId
                )
            }
        }
    }
}
