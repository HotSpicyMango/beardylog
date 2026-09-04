package com.hsm.beardylog.notification

/** 알림별 고정 ID. 같은 종류 알림은 같은 ID로 덮어써서 하루에 여러 개가 쌓이지 않게 한다. */
internal object NotificationIds {
    const val TODAY_SCHEDULE = 1001
    const val TODAY_DIET = 1002
    const val HATCH_DUE = 1003
    const val BACKUP_OVERDUE = 1004
}
