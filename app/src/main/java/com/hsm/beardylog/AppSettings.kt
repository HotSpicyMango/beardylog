package com.hsm.beardylog

import android.content.Context

internal class AppSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var profileSortMode: Int
        get() = preferences.getInt(KEY_PROFILE_SORT_MODE, ProfileSorter.SORT_BY_NAME)
            .coerceIn(ProfileSorter.SORT_BY_NAME, ProfileSorter.SORT_BY_ADOPTION_DATE)
        set(value) {
            preferences.edit().putInt(KEY_PROFILE_SORT_MODE, value).apply()
        }

    var checkUpdatesOnStart: Boolean
        get() = preferences.getBoolean(KEY_CHECK_UPDATES_ON_START, true)
        set(value) {
            preferences.edit().putBoolean(KEY_CHECK_UPDATES_ON_START, value).apply()
        }

    var autoSelectLastProfile: Boolean
        get() = preferences.getBoolean(KEY_AUTO_SELECT_LAST_PROFILE, false)
        set(value) {
            preferences.edit().putBoolean(KEY_AUTO_SELECT_LAST_PROFILE, value).apply()
        }

    var lastSelectedProfileId: Long?
        get() = preferences.getLong(KEY_LAST_SELECTED_PROFILE_ID, -1L).takeIf { it > 0L }
        set(value) {
            preferences.edit().apply {
                if (value == null) remove(KEY_LAST_SELECTED_PROFILE_ID) else putLong(KEY_LAST_SELECTED_PROFILE_ID, value)
            }.apply()
        }

    var lastDriveBackupAt: Long?
        get() = preferences.getLong(KEY_LAST_DRIVE_BACKUP_AT, 0L).takeIf { it > 0L }
        set(value) {
            preferences.edit().apply {
                if (value == null) remove(KEY_LAST_DRIVE_BACKUP_AT) else putLong(KEY_LAST_DRIVE_BACKUP_AT, value)
            }.apply()
        }

    private companion object {
        const val PREFERENCES_NAME = "app_settings"
        const val KEY_CHECK_UPDATES_ON_START = "check_updates_on_start"
        const val KEY_AUTO_SELECT_LAST_PROFILE = "auto_select_last_profile"
        const val KEY_PROFILE_SORT_MODE = "profile_sort_mode"
        const val KEY_LAST_SELECTED_PROFILE_ID = "last_selected_profile_id"
        const val KEY_LAST_DRIVE_BACKUP_AT = "last_drive_backup_at"
    }
}
