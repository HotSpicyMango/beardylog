package com.hsm.beardylog.notification

import android.content.Context

/** 알림 종류별 온/오프 저장소. 설정탭에서 각 알림을 개별적으로 켜고 끌 수 있어야 하므로
 *  AppSettings와 분리된 전용 SharedPreferences를 쓴다(느슨한 결합). */
internal class NotificationSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var dailyScheduleEnabled: Boolean
        get() = preferences.getBoolean(KEY_DAILY_SCHEDULE, true)
        set(value) = preferences.edit().putBoolean(KEY_DAILY_SCHEDULE, value).apply()

    var dailyDietEnabled: Boolean
        get() = preferences.getBoolean(KEY_DAILY_DIET, true)
        set(value) = preferences.edit().putBoolean(KEY_DAILY_DIET, value).apply()

    var breedingHatchDueEnabled: Boolean
        get() = preferences.getBoolean(KEY_HATCH_DUE, true)
        set(value) = preferences.edit().putBoolean(KEY_HATCH_DUE, value).apply()

    var driveBackupOverdueEnabled: Boolean
        get() = preferences.getBoolean(KEY_BACKUP_OVERDUE, true)
        set(value) = preferences.edit().putBoolean(KEY_BACKUP_OVERDUE, value).apply()

    private companion object {
        const val PREFERENCES_NAME = "notification_settings"
        const val KEY_DAILY_SCHEDULE = "daily_schedule_enabled"
        const val KEY_DAILY_DIET = "daily_diet_enabled"
        const val KEY_HATCH_DUE = "hatch_due_enabled"
        const val KEY_BACKUP_OVERDUE = "backup_overdue_enabled"
    }
}
