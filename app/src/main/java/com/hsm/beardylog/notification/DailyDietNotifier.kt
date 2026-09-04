package com.hsm.beardylog.notification

import android.content.Context
import androidx.core.app.NotificationCompat
import com.hsm.beardylog.MainActivity.MainSection
import com.hsm.beardylog.R
import com.hsm.beardylog.data.AppDatabase
import com.hsm.beardylog.data.CareSchedule
import com.hsm.beardylog.data.Reptile
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 개체별 당일 식단(충식/채식/사료/금식) 일정을 개체 여러 마리분을 한 알림에 몰아서 보여준다.
 *  "오늘 A의 식단: 충식, 사료" 형태를 개체별로 한 줄씩. 식단 일정이 하나도 없으면 조용히 끝낸다.
 *
 *  반복 일정(요일 지정)은 매주 다시 돌아오니 하루 놓쳐도 자연히 복구되지만, 특정 날짜 1회성
 *  일정은 그날 하루만 못 알리면 영영 놓친다. [DayCursor]로 최근 며칠을 같이 훑어 따라잡는다. */
internal object DailyDietNotifier {
    private val dietTypes = setOf("충식", "채식", "사료", "금식")
    private const val MAX_LOOKBACK_DAYS = 3L
    private val dateLabelFormat = DateTimeFormatter.ofPattern("M/d")

    fun notify(context: Context) {
        val database = AppDatabase.getInstance(context)
        val cursor = DayCursor(context, "daily_diet")
        val today = LocalDate.now()
        val pendingDates = cursor.pendingDates(today, MAX_LOOKBACK_DAYS)

        val allDietSchedules = database.careScheduleDao().all().filter { it.careType in dietTypes }
        val reptileNames = database.reptileDao().all().associateBy(Reptile::id)

        val perDateLines = pendingDates.mapNotNull { date ->
            val lines = allDietSchedules
                .filter { isScheduledOn(it, date) }
                .groupBy { it.reptileId }
                .mapNotNull { (reptileId, daySchedules) ->
                    val name = reptileNames[reptileId]?.name ?: return@mapNotNull null
                    "$name: ${daySchedules.joinToString(", ") { it.careType }}"
                }
            if (lines.isEmpty()) null else date to lines
        }
        cursor.markProcessed(today)
        if (perDateLines.isEmpty()) return

        val bigText = perDateLines.joinToString("\n\n") { (date, lines) ->
            val dateLabel = if (date == today) "오늘" else date.format(dateLabelFormat)
            (listOf(dateLabel) + lines.map { "· $it" }).joinToString("\n")
        }
        val summary = perDateLines.last().second.joinToString(" / ")

        val notification = NotificationCompat.Builder(context, AppNotificationChannel.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_calendar)
            .setContentTitle("오늘의 식단")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(openAppPendingIntent(context, NotificationIds.TODAY_DIET, MainSection.HOME))
            .setAutoCancel(true)
            .build()
        notifyIfPermitted(context, NotificationIds.TODAY_DIET, notification)
    }

    private fun isScheduledOn(schedule: CareSchedule, date: LocalDate): Boolean =
        schedule.repeatDayOfWeek == date.dayOfWeek.value ||
            (schedule.repeatDayOfWeek == null && schedule.scheduledDate == date.toEpochDay())
}
