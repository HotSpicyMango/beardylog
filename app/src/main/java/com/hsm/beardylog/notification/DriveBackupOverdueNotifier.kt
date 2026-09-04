package com.hsm.beardylog.notification

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hsm.beardylog.AppSettings
import com.hsm.beardylog.MainActivity.MainSection
import com.hsm.beardylog.R
import com.hsm.beardylog.data.AppDatabase
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 프로필이 있는데 Google Drive 백업을 오래(또는 한 번도) 안 했으면 알려준다.
 *  더 이상 해당 안 되면(최근에 백업함, 프로필이 없어짐) 떠 있던 알림도 스스로 지운다. */
internal object DriveBackupOverdueNotifier {
    private const val OVERDUE_DAYS = 14L

    fun notify(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        val hasReptiles = AppDatabase.getInstance(context).reptileDao().all().isNotEmpty()
        val lastBackupAt = AppSettings(context).lastDriveBackupAt
        val daysSinceBackup = lastBackupAt?.let { ChronoUnit.DAYS.between(Instant.ofEpochMilli(it), Instant.now()) }
        val isOverdue = hasReptiles && (daysSinceBackup == null || daysSinceBackup >= OVERDUE_DAYS)

        if (!isOverdue) {
            manager.cancel(NotificationIds.BACKUP_OVERDUE)
            return
        }
        val message = if (daysSinceBackup == null)
            "아직 이 기기에서 Google Drive 백업을 한 적이 없어요"
        else
            "마지막 백업 후 ${daysSinceBackup}일이 지났어요"

        val notification = NotificationCompat.Builder(context, AppNotificationChannel.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_settings)
            .setContentTitle("Google Drive 백업을 확인해 주세요")
            .setContentText(message)
            .setContentIntent(openAppPendingIntent(context, NotificationIds.BACKUP_OVERDUE, MainSection.SETTINGS))
            .setAutoCancel(true)
            .build()
        notifyIfPermitted(context, NotificationIds.BACKUP_OVERDUE, notification)
    }
}
