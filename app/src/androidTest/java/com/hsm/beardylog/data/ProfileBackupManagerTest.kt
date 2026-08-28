package com.hsm.beardylog.data

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipFile
import org.junit.After
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileBackupManagerTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AppDatabase
    private lateinit var calendarStore: CalendarEntryStore
    private lateinit var backupManager: ProfileBackupManager
    private lateinit var archive: File

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        calendarStore = CalendarEntryStore(context).also { it.importAll(emptyMap()) }
        backupManager = ProfileBackupManager(context, database)
        archive = File.createTempFile("backup-test", ".zip", context.cacheDir)
    }

    @After
    fun tearDown() {
        archive.delete()
        calendarStore.importAll(emptyMap())
        database.close()
    }

    private fun seedProfile(date: LocalDate, photo: File? = null) {
        val reptile = Reptile(
            7L, "레오", "레오파드 게코", "하이 옐로우", "수컷", 20_000L, "해칭일",
            photo?.let { android.net.Uri.fromFile(it).toString() }, 1_000L
        ).apply { hatchingDate = 20_000L }
        database.reptileDao().insertAll(listOf(reptile))
        calendarStore.saveCheckedValue(date, "수분 급여", true)
        calendarStore.saveTextValue(date, "memo", "백업 메모")
    }

    @Test
    fun profileAndCalendarDataRoundTripThroughArchive() {
        val date = LocalDate.of(2026, 8, 26)
        seedProfile(date)

        backupManager.writeArchive(archive)
        database.reptileDao().deleteAll()
        calendarStore.importAll(emptyMap())

        val preview = backupManager.previewArchive(archive)
        val result = try {
            backupManager.restore(preview)
        } finally {
            preview.close()
        }
        val restored = database.reptileDao().all().single()

        assertEquals(1, result.profileCount)
        assertEquals("레오", restored.name)
        assertEquals(20_000L, restored.hatchingDate)
        assertTrue(calendarStore.checkedValue(date, "수분 급여"))
        assertEquals("백업 메모", calendarStore.textValue(date, "memo"))
    }

    /** 사진이 manifest 안이 아니라 photos/ 엔트리로 빠지고, 복원하면 다시 파일로 돌아오는지.
     *  ZIP 전환의 핵심이라 저장 형태와 왕복을 한 테스트에서 같이 본다. */
    @Test
    fun photoRoundTripsAsSeparateZipEntry() {
        val bytes = ByteArray(4096) { 0x7A }
        val photo = File(context.cacheDir, "seed.jpg").apply { writeBytes(bytes) }
        try {
            seedProfile(LocalDate.of(2026, 8, 26), photo)
            val result = backupManager.writeArchive(archive)

            assertEquals(1, result.photoCount)
            assertEquals(0, result.skippedPhotoCount)

            ZipFile(archive).use { zip ->
                val manifest = zip.getInputStream(zip.getEntry("manifest.json")).use { it.readBytes() }
                    .toString(Charsets.UTF_8)
                // manifest는 사진 데이터가 아니라 엔트리 이름만 들고 있어야 한다.
                assertFalse("manifest에 base64가 남아 있음", manifest.contains("base64"))
                // org.json이 "/"를 "\/"로 이스케이프하므로 문자열 포함이 아니라 파싱해서 값을 본다.
                val entryName = JSONObject(manifest)
                    .getJSONArray("profiles").getJSONObject(0).getString("photoEntry")
                assertEquals("photos/profile_7.jpg", entryName)
                assertNotNull("ZIP에 사진 엔트리가 없음", zip.getEntry(entryName))
            }

            // 복원하면 엔트리가 다시 파일로 나와야 한다.
            database.reptileDao().deleteAll()
            val preview = backupManager.previewArchive(archive)
            try {
                assertEquals(1, backupManager.restore(preview).photoCount)
            } finally {
                preview.close()
            }
            val restoredUri = database.reptileDao().all().single().photoUri
            assertNotNull("복원된 프로필에 사진 경로가 없음", restoredUri)
            val restoredPath = Uri.parse(restoredUri).path ?: error("사진 경로를 읽을 수 없음")
            val restoredFile = File(restoredPath)
            assertTrue("복원된 사진 파일이 없음: $restoredPath", restoredFile.exists())
            assertArrayEquals("복원된 사진 내용이 원본과 다름", bytes, restoredFile.readBytes())
        } finally {
            photo.delete()
        }
    }

    /** 복원을 반복해도 사진 파일이 쌓이지 않아야 한다. 복원이 만든 고아는 언제나 방금 생긴 파일이라
     *  유예를 두고 훑으면 하나도 안 지워지고, 사용자에게는 '복원할수록 앱 용량이 는다'로 보인다. */
    @Test
    fun restoringRepeatedlyDoesNotPileUpPhotoFiles() {
        val photo = File(context.cacheDir, "orphan-seed.jpg").apply { writeBytes(ByteArray(2048) { 0x5A }) }
        try {
            seedProfile(LocalDate.of(2026, 8, 26), photo)
            backupManager.writeArchive(archive)

            repeat(3) {
                val preview = backupManager.previewArchive(archive)
                try {
                    backupManager.restore(preview)
                } finally {
                    preview.close()
                }
                // 복원 파일 이름이 밀리초를 쓰므로, 같은 ms 안에 겹쳐 덮어써서 통과하는 걸 막는다.
                Thread.sleep(5)
            }

            val remaining = File(context.filesDir, PhotoStore.PROFILE_DIRECTORY).listFiles().orEmpty()
            assertEquals(
                "복원할 때마다 사진이 쌓였다: ${remaining.map { it.name }}",
                1,
                remaining.size
            )
        } finally {
            photo.delete()
        }
    }

    /** 다운로드한 백업 임시 파일은 close에서 반드시 지워져야 한다. 이게 새는 경로가 있으면
     *  cache 폴더에 restore*.tmp가 쌓인다. close를 두 번 불러도 안전해야 release에서 중복 호출해도 된다. */
    @Test
    fun previewDeletesDownloadedFileOnClose() {
        seedProfile(LocalDate.of(2026, 8, 26))
        backupManager.writeArchive(archive)
        val downloaded = File(context.cacheDir, "restore-close-check.tmp")
        archive.copyTo(downloaded, overwrite = true)

        val preview = backupManager.previewArchive(downloaded, deleteOnClose = true)
        assertTrue("close 전에는 임시 파일이 남아 있어야 한다", downloaded.exists())
        preview.close()
        assertFalse("close가 임시 파일을 지우지 않았다", downloaded.exists())
        preview.close()
    }

    /** 예전 v1 JSON 백업을 가진 사용자가 업데이트해도 복원할 수 있어야 한다. */
    @Test
    fun legacyJsonBackupStillRestores() {
        val date = LocalDate.of(2026, 8, 26)
        seedProfile(date)
        val legacy = File.createTempFile("legacy", ".json", context.cacheDir)
        try {
            legacy.writeText(
                """{"format":"beardylog-profile-backup","schemaVersion":1,"createdAt":1000,
                   "profiles":[{"id":7,"name":"레오","species":"","morph":"","gender":"수컷",
                   "referenceDate":20000,"referenceDateType":"해칭일","createdAt":1000,"photo":null}],
                   "calendarEntries":{}}""".trimIndent()
            )
            database.reptileDao().deleteAll()

            val preview = backupManager.previewArchive(legacy)
            try {
                assertEquals(1, backupManager.restore(preview).profileCount)
            } finally {
                preview.close()
            }
            assertEquals("레오", database.reptileDao().all().single().name)
        } finally {
            legacy.delete()
        }
    }
}
