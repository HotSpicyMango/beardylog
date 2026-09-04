package com.hsm.beardylog.notification

import android.content.Context
import androidx.core.app.NotificationCompat
import com.hsm.beardylog.MainActivity.MainSection
import com.hsm.beardylog.R
import com.hsm.beardylog.data.CalendarEntryStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 달력 탭은 미래 날짜에도 체크·기록을 남길 수 있다(예: "다음주 금요일 병원" 을 미리 체크해 둠).
 *  그래서 이 알림은 '오늘 아직 안 한 일'이 아니라 '오늘 날짜로 미리 표시해 둔 항목'을 알려준다 —
 *  그날이 되면 전에 체크해 둔 게 무엇인지 알려주는 방식.
 *  대상은 6개 체크리스트(수분 급여/배변/온욕/구충제/사육장 청소/UVB 교체) + 병원 기록이고,
 *  요구사항대로 약 기록·메모는 제외한다.
 *
 *  워커가 특정 날짜 하루를 못 돌면(도즈, 강제종료 등) 그날 표시해둔 내용을 영영 놓치므로,
 *  [DayCursor]로 마지막 처리일 이후 최근 며칠을 훑어 따라잡는다. */
internal object TodayScheduleNotifier {
    private val checklistLabels = listOf("수분 급여", "배변", "온욕", "구충제", "사육장 청소", "UVB 교체")
    private const val MAX_LOOKBACK_DAYS = 3L
    private val dateLabelFormat = DateTimeFormatter.ofPattern("M/d")

    fun notify(context: Context) {
        val store = CalendarEntryStore(context)
        val cursor = DayCursor(context, "today_schedule")
        val today = LocalDate.now()
        val pendingDates = cursor.pendingDates(today, MAX_LOOKBACK_DAYS)

        val perDateItems = pendingDates.mapNotNull { date ->
            val marked = checklistLabels.filter { store.checkedValue(date, it) }
            val hospital = store.textValue(date, "hospital").trim()
            val items = buildList {
                addAll(marked)
                if (hospital.isNotEmpty()) add("병원: $hospital")
            }
            if (items.isEmpty()) null else date to items
        }
        cursor.markProcessed(today)
        if (perDateItems.isEmpty()) return

        val onlyToday = perDateItems.size == 1 && perDateItems[0].first == today
        val bigText = perDateItems.joinToString("\n\n") { (date, items) ->
            val dateLabel = if (date == today) "오늘" else date.format(dateLabelFormat)
            (listOf(dateLabel) + items.map { "· $it" }).joinToString("\n")
        }
        val summary = perDateItems.last().second.joinToString(", ")

        val notification = NotificationCompat.Builder(context, AppNotificationChannel.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_calendar)
            .setContentTitle(if (onlyToday) "오늘 표시해 둔 일정" else "표시해 둔 일정")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(openAppPendingIntent(context, NotificationIds.TODAY_SCHEDULE, MainSection.CALENDAR))
            .setAutoCancel(true)
            .build()
        notifyIfPermitted(context, NotificationIds.TODAY_SCHEDULE, notification)
    }
}
