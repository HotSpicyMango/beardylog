package com.hsm.beardylog.data

import android.content.Context
import android.net.Uri
import java.io.File

/** 앱이 사진을 두는 곳. 파일을 만드는 쪽(크롭 저장, 앨범 추가, 백업 복원)은 여럿인데
 *  지우는 쪽이 없어서 참조를 잃은 사진이 계속 쌓인다. 그 정리를 여기서 한 곳에 모아 처리한다. */
internal object PhotoStore {
    const val PROFILE_DIRECTORY = "profile_photos"
    const val MEMORIAL_DIRECTORY = "memorial_photos"

    /** 아직 DB에 기록되지 않은 작업 중 파일(크롭 직후 등)을 지우지 않기 위한 기본 유예. */
    const val DEFAULT_GRACE_PERIOD_MS = 24L * 60 * 60 * 1000

    /** DB가 더 이상 가리키지 않는 사진 파일을 지운다. 프로필 사진 교체, 저장하지 않고 나간 크롭,
     *  백업 복원이 모두 예전 파일을 남기므로 주기적으로 훑어야 한다. 메인 스레드에서 부르지 말 것.
     *
     *  [gracePeriodMs]는 '이 시간 안에 만들어진 파일은 건드리지 않는다'는 뜻이다. 앱 시작 시 훑을 때는
     *  편집 중이던 파일을 지키려고 하루를 두지만, 복원 직후에는 0을 넘겨야 한다 — 복원이 만든 고아는
     *  언제나 방금 생긴 파일이라 유예를 두면 하나도 지워지지 않고, 복원할수록 용량만 늘어난다.
     *  복원으로 새로 쓴 파일은 DB가 가리키므로 유예 없이도 안전하다. */
    fun deleteOrphans(
        context: Context,
        database: AppDatabase,
        gracePeriodMs: Long = DEFAULT_GRACE_PERIOD_MS
    ) {
        val referenced = buildSet {
            database.reptileDao().all().forEach { reptile -> reptile.photoUri?.let(::add) }
            database.memorialPhotoDao().all().forEach { add(it.photoUri) }
        }.mapNotNullTo(mutableSetOf()) { runCatching { Uri.parse(it).path }.getOrNull() }

        val cutoff = System.currentTimeMillis() - gracePeriodMs
        listOf(PROFILE_DIRECTORY, MEMORIAL_DIRECTORY).forEach { directory ->
            File(context.filesDir, directory).listFiles()?.forEach { file ->
                if (file.absolutePath !in referenced && file.lastModified() < cutoff) {
                    runCatching { file.delete() }
                }
            }
        }
    }
}
