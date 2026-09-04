package com.hsm.beardylog.notification

import android.content.Context
import java.time.LocalDate

/** "지난번에 어느 날짜까지 처리했는지" 기억해서, 워커가 하루 이틀 못 돌아도 다음 실행에서
 *  놓친 날짜를 이어서 처리할 수 있게 해주는 커서. 앱을 몇 주씩 안 켜서 너무 오래 밀린 경우까지
 *  전부 몰아서 알리면 오히려 스팸이 되므로 조회 범위를 최근 며칠(maxLookbackDays)로 제한한다.
 *  키(key)별로 독립적으로 동작하므로 알림 종류마다 별도 커서를 쓸 수 있다. */
internal class DayCursor(context: Context, key: String) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val prefKey = "cursor_$key"

    /** 마지막으로 처리 완료한 날의 다음날부터 오늘까지, 최대 maxLookbackDays일치를 오래된 순으로 반환한다.
     *  한 번도 처리한 적 없으면(최초 실행) 과거 이력을 몰아서 알리지 않도록 오늘 하루만 반환한다. */
    fun pendingDates(today: LocalDate, maxLookbackDays: Long): List<LocalDate> {
        val lastProcessedEpochDay = preferences.getLong(prefKey, today.toEpochDay() - 1)
        val startEpochDay = maxOf(lastProcessedEpochDay + 1, today.toEpochDay() - maxLookbackDays)
        return (startEpochDay..today.toEpochDay()).map(LocalDate::ofEpochDay)
    }

    fun markProcessed(date: LocalDate) {
        preferences.edit().putLong(prefKey, date.toEpochDay()).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "notification_day_cursor"
    }
}
