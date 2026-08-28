package com.hsm.beardylog

import android.app.Application
import android.util.Log
import com.hsm.beardylog.data.AppDatabase
import com.hsm.beardylog.data.PhotoStore
import kotlin.concurrent.thread

/**
 * Some Xiaomi MIUI builds patch android.os.Looper with a "isPerfLogEnable()" perf-logging hook
 * that isn't null-safe. It occasionally throws a NullPointerException on background threads
 * (observed while Room opens its SQLite connection) and, being uncaught, kills the whole app
 * process even though it has nothing to do with app logic. It only reproduces on MIUI devices,
 * not on stock/Samsung builds, so this is a vendor ROM bug we can't fix at the source. We swallow
 * just this exact NPE signature and let every other uncaught exception behave normally.
 */
class BeardylogApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (isMiuiPerfLogNoise(throwable)) {
                Log.e("Beardylog", "Ignored MIUI Looper.isPerfLogEnable() crash on thread ${thread.name}", throwable)
            } else {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
        // 사진 파일은 DB 행이 사라져도 디스크에 남는다. 시작할 때 한 번 훑어서 정리한다.
        thread(isDaemon = true) {
            runCatching { PhotoStore.deleteOrphans(this, AppDatabase.getInstance(this)) }
        }
    }

    private fun isMiuiPerfLogNoise(throwable: Throwable): Boolean =
        throwable is NullPointerException && throwable.message?.contains("isPerfLogEnable") == true
}
