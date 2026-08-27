package com.hsm.beardylog.data

import android.content.Context
import java.time.LocalDate
import java.time.YearMonth

class CalendarEntryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class MonthEntry(
        val checkedTasks: Set<String>,
        val hospital: String,
        val medicine: String,
        val memo: String
    )

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

    fun monthEntries(month: YearMonth, taskLabels: Collection<String>): Map<LocalDate, MonthEntry> {
        val values = prefs.all
        val entries = linkedMapOf<LocalDate, MonthEntry>()
        for (day in 1..month.lengthOfMonth()) {
            val date = month.atDay(day)
            val checkedTasks = taskLabels.filterTo(linkedSetOf()) { label ->
                values[key(date, "task_$label")] as? Boolean == true
            }
            val hospital = values[key(date, "hospital")] as? String ?: ""
            val medicine = values[key(date, "medicine")] as? String ?: ""
            val memo = values[key(date, "memo")] as? String ?: ""
            if (checkedTasks.isNotEmpty() || hospital.isNotBlank() || medicine.isNotBlank() || memo.isNotBlank()) {
                entries[date] = MonthEntry(checkedTasks, hospital, medicine, memo)
            }
        }
        return entries
    }

    /** Raw key/value snapshot of every stored entry, for backup. Values are always Boolean or String. */
    fun exportAll(): Map<String, Any> {
        val snapshot = LinkedHashMap<String, Any>()
        prefs.all.forEach { (key, value) ->
            when (value) {
                is Boolean -> snapshot[key] = value
                is String -> snapshot[key] = value
            }
        }
        return snapshot
    }

    /** Replaces all stored entries with [entries] (as returned by [exportAll]), for restore. */
    fun importAll(entries: Map<String, Any?>) {
        prefs.edit().apply {
            clear()
            entries.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                }
            }
        }.apply()
    }

    private fun key(date: LocalDate, field: String): String = "${date.toEpochDay()}_$field"

    private companion object {
        const val PREFS_NAME = "calendar_entries"
    }
}
