package com.hsm.beardylog.notification

import android.content.Context

/** 부화 임박 알림을 이미 보낸 클러치 id를 기록해서, 임박 기간(며칠)에 걸쳐 같은 알림이
 *  매일 반복되지 않게 한다. 부화 처리가 끝났거나 더 이상 존재하지 않는 클러치 id는
 *  매번 실행할 때 정리해서(prune) 목록이 끝없이 커지지 않게 한다. */
internal class NotifiedClutchStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun currentIds(): MutableSet<String> =
        preferences.getStringSet(KEY_NOTIFIED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()

    fun hasNotified(clutchId: Long): Boolean = clutchId.toString() in currentIds()

    fun markNotified(clutchId: Long) {
        val ids = currentIds()
        ids.add(clutchId.toString())
        preferences.edit().putStringSet(KEY_NOTIFIED_IDS, ids).apply()
    }

    fun retainOnly(validClutchIds: Set<Long>) {
        val validAsStrings = validClutchIds.map(Long::toString).toSet()
        val ids = currentIds()
        if (ids.retainAll(validAsStrings)) {
            preferences.edit().putStringSet(KEY_NOTIFIED_IDS, ids).apply()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "notified_clutches"
        const val KEY_NOTIFIED_IDS = "notified_clutch_ids"
    }
}
