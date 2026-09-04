package com.hsm.beardylog.notification

import android.content.Context
import androidx.core.app.NotificationCompat
import com.hsm.beardylog.MainActivity.MainSection
import com.hsm.beardylog.R
import com.hsm.beardylog.data.AppDatabase
import com.hsm.beardylog.data.BreedingPair
import com.hsm.beardylog.data.Reptile
import java.time.LocalDate

/** 클러치(알 회차)에는 "예상 부화일" 필드가 따로 없어서, 산란일 + 평균 인큐베이션 기간으로
 *  대략 추정한다. 종/온도에 따라 실제 기간이 다를 수 있어 정확한 날짜는 아니고 참고용 임박 알림이다.
 *  이미 해츨링 기록이 생긴 클러치(이미 부화 처리 완료)는 대상에서 뺀다.
 *
 *  "정확히 D-3일"만 조건으로 잡으면 워커가 그날 하루만 못 돌아도(도즈, 강제종료 등) 그 클러치는
 *  영영 알림을 못 받는다. 그래서 "0~HEADS_UP_DAYS_BEFORE일 남은 구간"으로 넓혀서 하루 놓쳐도
 *  다음 실행에서 따라잡게 하고, 대신 같은 클러치를 그 구간 동안 여러 번 알리지 않도록
 *  [NotifiedClutchStore]로 이미 알린 클러치를 기억해둔다. */
internal object HatchDueSoonNotifier {
    private const val ESTIMATED_INCUBATION_DAYS = 60L
    private const val HEADS_UP_DAYS_BEFORE = 3L

    fun notify(context: Context) {
        val database = AppDatabase.getInstance(context)
        val today = LocalDate.now().toEpochDay()
        val notifiedStore = NotifiedClutchStore(context)

        val alreadyHatchedClutchIds = database.hatchlingDao().all().map { it.clutchId }.toSet()
        val allClutches = database.clutchDao().all()
        notifiedStore.retainOnly(allClutches.map { it.id }.toSet() - alreadyHatchedClutchIds)

        val dueClutches = allClutches.filter { clutch ->
            clutch.id !in alreadyHatchedClutchIds &&
                !notifiedStore.hasNotified(clutch.id) &&
                (clutch.layingDate + ESTIMATED_INCUBATION_DAYS) - today in 0..HEADS_UP_DAYS_BEFORE
        }
        if (dueClutches.isEmpty()) return

        val pairs = database.breedingPairDao().all().associateBy(BreedingPair::id)
        val reptiles = database.reptileDao().all().associateBy(Reptile::id)
        fun parentName(reptileId: Long?, fallbackName: String): String =
            reptileId?.let { reptiles[it]?.name } ?: fallbackName.ifBlank { "미상" }

        val lines = dueClutches.map { clutch ->
            val pair = pairs[clutch.pairId]
            val pairLabel = if (pair == null) "알 수 없는 짝" else
                "${parentName(pair.maleReptileId, pair.maleName)} x ${parentName(pair.femaleReptileId, pair.femaleName)}"
            "$pairLabel ${clutch.clutchNumber}차"
        }

        val notification = NotificationCompat.Builder(context, AppNotificationChannel.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_breeding)
            .setContentTitle("부화가 곧 예정돼 있어요")
            .setContentText("약 ${HEADS_UP_DAYS_BEFORE}일 내 부화 추정: ${lines.joinToString(", ")}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n") { "· $it" }))
            .setContentIntent(openAppPendingIntent(context, NotificationIds.HATCH_DUE, MainSection.BREEDING))
            .setAutoCancel(true)
            .build()
        notifyIfPermitted(context, NotificationIds.HATCH_DUE, notification)
        dueClutches.forEach { notifiedStore.markNotified(it.id) }
    }
}
