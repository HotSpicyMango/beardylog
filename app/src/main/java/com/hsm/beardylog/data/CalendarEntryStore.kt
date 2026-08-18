package com.hsm.beardylog.data

import android.content.Context
import java.time.LocalDate

class CalendarEntryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun checkedValue(date: LocalDate, label: String): Boolean =
        prefs.getBoolean(key(date, "task_$label"), false)

    fun saveCheckedValue(date: LocalDate, label: String, checked: Boolean) {
        prefs.edit().putBoolean(key(date, "task_$label"), checked).apply()
    }

    fun textValue(date: LocalDate, field: String): String =
        prefs.getString(key(date, field), "").orEmpty()

    fun saveTextValue(date: LocalDate, field: String, value: String) {
        prefs.edit().putString(key(date, field), value.trim()).apply()
    }

    fun deleteDate(date: LocalDate, taskLabels: Collection<String>) {
        prefs.edit().apply {
            taskLabels.forEach { label -> remove(key(date, "task_$label")) }
            listOf("hospital", "medicine", "memo").forEach { field -> remove(key(date, field)) }
        }.apply()
    }

    private fun key(date: LocalDate, field: String): String = "${date.toEpochDay()}_$field"

    private companion object {
        const val PREFS_NAME = "calendar_entries"
    }
}
