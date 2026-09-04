package com.hsm.beardylog.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hsm.beardylog.MainActivity
import com.hsm.beardylog.MainActivity.MainSection

/** 여러 알림 발행 코드가 공통으로 쓰는 자잘한 것들. 종류별 노티파이어에 중복으로 두지 않는다. */
internal fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}

/** 권한 체크 + 게시를 한곳에서 한다. lint는 hasNotificationPermission()을 통한 체크를
 *  함수 경계 너머로 못 따라가 MissingPermission을 오탐하므로, 억제 지점을 여기 하나로 모은다. */
@SuppressLint("MissingPermission")
internal fun notifyIfPermitted(context: Context, notificationId: Int, notification: Notification) {
    if (!hasNotificationPermission(context)) return
    NotificationManagerCompat.from(context).notify(notificationId, notification)
}

/** 알림을 탭하면 앱을 열면서 관련 탭으로 바로 이동시킨다.
 *  requestCode는 알림 종류별로 달라야 한다(NotificationIds 값을 그대로 씀) — 같은 코드를 쓰면
 *  나중에 만든 PendingIntent가 먼저 만든 걸 덮어써서, 화면에 떠 있는 다른 알림을 눌러도
 *  최근에 만든 알림의 목적지로 잘못 이동하게 된다. */
internal fun openAppPendingIntent(context: Context, requestCode: Int, section: MainSection): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_OPEN_SECTION, section.name)
    }
    return PendingIntent.getActivity(
        context, requestCode, intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
