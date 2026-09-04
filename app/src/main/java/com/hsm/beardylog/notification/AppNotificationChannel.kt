package com.hsm.beardylog.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.ContextCompat

/** 시간이 중요한 알림이 아니므로(요구사항: "배터리 최적화된 알림") 소리·진동 없는
 *  낮은 중요도 채널 하나로 통일한다. 채널을 알림 종류별로 쪼개도 사용자에게 득이 없고
 *  설정 화면(시스템 알림 설정)만 복잡해진다. */
internal object AppNotificationChannel {
    const val CHANNEL_ID = "daily_care_reminders"

    fun ensureCreated(context: Context) {
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "돌봄 알림",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "오늘의 일정, 식단, 브리딩, 백업 관련 알림"
        }
        manager.createNotificationChannel(channel)
    }
}
