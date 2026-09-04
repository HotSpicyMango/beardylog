package com.hsm.beardylog.notification

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** 매일 오전 9시 무렵 한 번 실행되게 한다. 순수 24시간 주기 반복 작업(PeriodicWorkRequest)은
 *  한 번이라도 늦게 실행되면 그 이후로도 계속 그 늦어진 시각을 기준으로 24시간씩 흘러가며
 *  시각이 점점 밀리는데(드리프트), 여기서는 대신 실행할 때마다 "지금부터 다음 9시까지"를
 *  다시 계산해서 스스로 다음 회차를 1회성 작업으로 예약하는 방식을 쓴다 — 매번 실제 현재
 *  시각 기준으로 재정렬되므로 시간이 지나도 9시에서 벗어나지 않는다.
 *  실제 체이닝(다음 회차 예약)은 [DailyNotificationWorker]가 실행을 마칠 때 [scheduleNext]를
 *  호출해서 이어간다. */
internal object NotificationScheduler {
    private const val UNIQUE_WORK_NAME = "daily_notification_check"
    private val TARGET_TIME: LocalTime = LocalTime.of(9, 0)

    /** 앱 시작 시 호출. 이미 다음 회차가 예약돼 있으면 그대로 둔다(KEEP) — 안 그러면 앱을 열 때마다
     *  예약이 "지금부터 다음 9시"로 재계산돼 매번 조금씩 실행 시각이 바뀔 수 있다. */
    fun scheduleIfNeeded(context: Context) {
        enqueue(context, ExistingWorkPolicy.KEEP)
    }

    /** 워커가 한 번 실행을 마칠 때마다 스스로 호출해 다음 회차를 잡는다. 이번엔 무조건 새로
     *  계산한 시각으로 교체한다(REPLACE) — 체인이 계속 이어지게 하기 위해서다. */
    fun scheduleNext(context: Context) {
        enqueue(context, ExistingWorkPolicy.REPLACE)
    }

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<DailyNotificationWorker>()
            .setInitialDelay(delayUntilNextTarget(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request)
    }

    private fun delayUntilNextTarget(): Long {
        val now = LocalDateTime.now()
        var target = now.toLocalDate().atTime(TARGET_TIME)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target).toMillis()
    }
}
