package com.hsm.beardylog.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** 하루에 한 번 실행되며, 설정에서 켜져 있는 알림 종류만 실제로 게시한다.
 *  끝나면 항상(예외가 나도) [NotificationScheduler.scheduleNext]로 다음 회차를 다시 예약한다 —
 *  finally에 안 넣으면 알림 하나가 예외를 던졌을 때 이후로 알림 체인 자체가 영영 끊긴다.
 *  같은 이유로 알림 종류별 호출도 각각 runCatching으로 감싸서, 하나가 실패해도 나머지는
 *  이어서 게시되게 한다. */
internal class DailyNotificationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            val settings = NotificationSettings(applicationContext)
            if (settings.dailyScheduleEnabled) runCatching { TodayScheduleNotifier.notify(applicationContext) }
            if (settings.dailyDietEnabled) runCatching { DailyDietNotifier.notify(applicationContext) }
            if (settings.breedingHatchDueEnabled) runCatching { HatchDueSoonNotifier.notify(applicationContext) }
            if (settings.driveBackupOverdueEnabled) runCatching { DriveBackupOverdueNotifier.notify(applicationContext) }
            return Result.success()
        } finally {
            NotificationScheduler.scheduleNext(applicationContext)
        }
    }
}
