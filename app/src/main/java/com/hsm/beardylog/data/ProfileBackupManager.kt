package com.hsm.beardylog.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ProfileBackupManager(
    context: Context,
    private val database: AppDatabase
) {
    private val appContext = context.applicationContext
    private val calendarEntryStore = CalendarEntryStore(appContext)

    data class BackupResult(
        val createdAt: Long,
        val profileCount: Int,
        val photoCount: Int,
        val skippedPhotoCount: Int
    )

    /** 사진은 [archive] 안에 남아 있고 복원 시점에 한 장씩 꺼내 쓴다. 그래서 미리보기와 복원 사이
     *  기다리는 동안에도 메모리에는 DB 레코드만 있다. 다 쓰면 반드시 [close]로 임시 파일을 정리할 것. */
    class RestorePreview internal constructor(
        val createdAt: Long,
        val profileCount: Int,
        val photoCount: Int,
        val calendarDatesCount: Int,
        val breedingPairCount: Int,
        internal val snapshot: BackupSnapshot,
        private val archive: ZipFile?,
        private val downloaded: File?
    ) {
        /** 복원했든 취소했든 호출해야 한다. 두 번 불러도 안전. */
        fun close() {
            runCatching { archive?.close() }
            downloaded?.delete()
        }
    }

    /** Drive에 남아 있는 백업 한 벌. [modifiedAt]은 업로드 시각(최신순 정렬 기준). */
    data class RemoteBackup(val id: String, val modifiedAt: Long, val sizeBytes: Long?)

    /** 최신 백업이 앞에 오도록 정렬된 목록. 업로드 전 경고와 복원할 백업 선택에 쓴다. */
    fun listBackups(accessToken: String): List<RemoteBackup> = listBackupFiles(accessToken)

    /** 새 백업을 올린 뒤 최신 [MAX_BACKUPS]개만 남기고 오래된 백업을 지운다.
     *  업로드가 성공한 다음에 지우므로, 업로드가 실패해도 기존 백업은 그대로 남는다. */
    fun upload(accessToken: String): BackupResult {
        val archive = File.createTempFile("backup", ".zip", appContext.cacheDir)
        try {
            val result = writeArchive(archive)
            uploadArchive(accessToken, archive)
            listBackupFiles(accessToken).drop(MAX_BACKUPS).forEach { remote ->
                runCatching { deleteFile(accessToken, remote.id) }
            }
            return result
        } finally {
            archive.delete()
        }
    }

    fun download(accessToken: String, remote: RemoteBackup): RestorePreview {
        val downloaded = File.createTempFile("restore", ".tmp", appContext.cacheDir)
        try {
            downloadTo(
                url = "https://www.googleapis.com/drive/v3/files/${remote.id}?alt=media",
                accessToken = accessToken,
                target = downloaded
            )
            return previewArchive(downloaded, deleteOnClose = true)
        } catch (error: Throwable) {
            downloaded.delete()
            throw error
        }
    }

    /** 백업 한 벌을 ZIP으로 쓴다. manifest.json에는 DB 레코드만 담고 사진은 별도 엔트리로
     *  한 장씩 흘려보내므로, 사진이 몇 장이든 메모리에 올라오는 건 한 장뿐이다. */
    internal fun writeArchive(target: File): BackupResult {
        val snapshot = captureSnapshot()
        if (snapshot.profiles.isEmpty()) throw NoProfilesToBackupException()
        var photoCount = 0
        var skippedPhotoCount = 0
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(snapshot.toJson().toString().toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            // 사진은 이미 JPEG/PNG라 다시 압축해봐야 거의 안 줄고 시간만 쓴다.
            zip.setLevel(Deflater.NO_COMPRESSION)
            snapshot.profiles.forEach { profile ->
                val uri = profile.entity.photoUri ?: return@forEach
                if (copyPhotoInto(zip, profilePhotoEntry(profile.entity.id, uri), uri)) photoCount++
                else skippedPhotoCount++
            }
            snapshot.memorialPhotos.forEach { photo ->
                val uri = photo.sourceUri ?: return@forEach
                if (copyPhotoInto(zip, memorialPhotoEntry(photo.id, uri), uri)) photoCount++
                else skippedPhotoCount++
            }
        }
        return BackupResult(
            createdAt = snapshot.createdAt,
            profileCount = snapshot.profiles.size,
            photoCount = photoCount,
            skippedPhotoCount = skippedPhotoCount
        )
    }

    /** ZIP 백업이면 manifest를 읽고, 예전 v1 JSON 백업이면 그대로 파싱한다(사진은 Base64 인라인).
     *  구버전으로 백업해 둔 사용자가 복원하지 못하면 그대로 데이터 유실이라 두 형식을 모두 받는다. */
    internal fun previewArchive(file: File, deleteOnClose: Boolean = false): RestorePreview {
        val archive = runCatching { ZipFile(file) }.getOrNull()
        try {
            val json = if (archive != null) {
                val manifest = archive.getEntry(MANIFEST_ENTRY)
                    ?: throw InvalidBackupException("백업 파일에 목록이 없습니다")
                archive.getInputStream(manifest).use { it.readBytes() }.toString(StandardCharsets.UTF_8)
            } else {
                file.readText(StandardCharsets.UTF_8)
            }
            val snapshot = try {
                parseSnapshot(json, archive)
            } catch (error: InvalidBackupException) {
                throw error
            } catch (_: Throwable) {
                throw InvalidBackupException("백업 데이터가 손상되었습니다")
            }
            return RestorePreview(
                createdAt = snapshot.createdAt,
                profileCount = snapshot.profiles.size,
                photoCount = snapshot.profiles.count { it.photo != null } + snapshot.memorialPhotos.count { it.photo != null },
                calendarDatesCount = snapshot.calendarDatesCount(),
                breedingPairCount = snapshot.breedingPairs.size,
                snapshot = snapshot,
                archive = archive,
                downloaded = file.takeIf { deleteOnClose }
            )
        } catch (error: Throwable) {
            runCatching { archive?.close() }
            throw error
        }
    }

    private fun copyPhotoInto(zip: ZipOutputStream, entryName: String, uriValue: String): Boolean {
        val bytes = runCatching {
            appContext.contentResolver.openInputStream(Uri.parse(uriValue))?.use { it.readBytes() }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(bytes)
        zip.closeEntry()
        return true
    }

    fun restore(preview: RestorePreview): BackupResult {
        val restoredPhotoFiles = mutableListOf<File>()
        val restoredProfiles = preview.snapshot.profiles.map { profile ->
            val source = profile.entity
            val restoredPhotoUri = profile.photo?.let { photo ->
                val directory = File(appContext.filesDir, PhotoStore.PROFILE_DIRECTORY).apply { mkdirs() }
                check(directory.isDirectory) { "프로필 사진 저장 공간을 만들지 못했습니다" }
                File(directory, "profile_${source.id}_${System.currentTimeMillis()}.${photo.extension}").also { file ->
                    photo.writeTo(file)
                    restoredPhotoFiles += file
                }.let(Uri::fromFile).toString()
            }
            Reptile(
                source.id,
                source.name,
                source.species,
                source.morph,
                source.gender,
                source.referenceDate,
                source.referenceDateType,
                restoredPhotoUri,
                source.createdAt
            ).apply {
                hatchingDate = source.hatchingDate
                adoptionDate = source.adoptionDate
                deathDate = source.deathDate
                memorialNote = source.memorialNote
            }
        }

        val restoredMemorialPhotoFiles = mutableListOf<File>()
        val restoredMemorialPhotos = preview.snapshot.memorialPhotos.mapNotNull { photo ->
            val source = photo.photo ?: return@mapNotNull null
            val directory = File(appContext.filesDir, PhotoStore.MEMORIAL_DIRECTORY).apply { mkdirs() }
            check(directory.isDirectory) { "추억 사진 저장 공간을 만들지 못했습니다" }
            val file = File(directory, "memorial_${photo.reptileId}_${System.currentTimeMillis()}_${photo.id}.${source.extension}").also { file ->
                source.writeTo(file)
                restoredMemorialPhotoFiles += file
            }
            MemorialPhoto(photo.id, photo.reptileId, Uri.fromFile(file).toString(), photo.addedAt)
        }

        try {
            database.runInTransaction {
                database.careLogDao().deleteAll()
                database.careScheduleDao().deleteAll()
                database.weightRecordDao().deleteAll()
                database.breedingRecordDao().deleteAll()
                database.hatchlingDao().deleteAll()
                database.clutchDao().deleteAll()
                database.breedingPairDao().deleteAll()
                database.memorialPhotoDao().deleteAll()
                database.reptileDao().deleteAll()

                database.reptileDao().insertAll(restoredProfiles)
                database.weightRecordDao().insertAll(preview.snapshot.weightRecords)
                database.careScheduleDao().insertAll(preview.snapshot.careSchedules)
                database.careLogDao().insertAll(preview.snapshot.careLogs)
                database.breedingRecordDao().insertAll(preview.snapshot.breedingRecords)
                database.breedingPairDao().insertAll(preview.snapshot.breedingPairs)
                database.clutchDao().insertAll(preview.snapshot.clutches)
                database.hatchlingDao().insertAll(preview.snapshot.hatchlings)
                database.memorialPhotoDao().insertAll(restoredMemorialPhotos)
            }
        } catch (error: Throwable) {
            restoredPhotoFiles.forEach { it.delete() }
            restoredMemorialPhotoFiles.forEach { it.delete() }
            throw error
        }

        // Calendar entries live in SharedPreferences, not the Room database, so they're
        // applied after the DB transaction commits rather than as part of it.
        calendarEntryStore.importAll(preview.snapshot.calendarEntries)
        // 복원은 DB 행을 전부 갈아끼우므로 이전 프로필/추억 사진이 참조를 잃고 디스크에 남는다.
        // 그 고아들은 방금 만들어진 파일이라 유예를 두면 하나도 안 지워진다 — 여기서는 0으로 훑는다.
        runCatching { PhotoStore.deleteOrphans(appContext, database, gracePeriodMs = 0L) }

        return BackupResult(
            createdAt = preview.createdAt,
            profileCount = restoredProfiles.size,
            photoCount = restoredPhotoFiles.size + restoredMemorialPhotoFiles.size,
            skippedPhotoCount = 0
        )
    }

    private fun captureSnapshot(): BackupSnapshot {
        lateinit var reptiles: List<Reptile>
        lateinit var weightRecords: List<WeightRecord>
        lateinit var careSchedules: List<CareSchedule>
        lateinit var careLogs: List<CareLog>
        lateinit var breedingRecords: List<BreedingRecord>
        lateinit var breedingPairs: List<BreedingPair>
        lateinit var clutches: List<Clutch>
        lateinit var hatchlings: List<Hatchling>
        lateinit var memorialPhotoEntities: List<MemorialPhoto>
        database.runInTransaction {
            reptiles = database.reptileDao().all()
            weightRecords = database.weightRecordDao().all()
            careSchedules = database.careScheduleDao().all()
            careLogs = database.careLogDao().all()
            breedingRecords = database.breedingRecordDao().all()
            breedingPairs = database.breedingPairDao().all()
            clutches = database.clutchDao().all()
            hatchlings = database.hatchlingDao().all()
            memorialPhotoEntities = database.memorialPhotoDao().all()
        }

        // 사진 바이트는 여기서 읽지 않는다. writeArchive가 ZIP 엔트리로 한 장씩 흘려보낸다.
        val profiles = reptiles.map { ProfileSnapshot(it, photo = null) }
        val memorialPhotos = memorialPhotoEntities.map { entity ->
            MemorialPhotoSnapshot(entity.id, entity.reptileId, entity.addedAt, entity.photoUri, photo = null)
        }
        return BackupSnapshot(
            createdAt = System.currentTimeMillis(),
            profiles = profiles,
            weightRecords = weightRecords,
            careSchedules = careSchedules,
            careLogs = careLogs,
            breedingRecords = breedingRecords,
            breedingPairs = breedingPairs,
            clutches = clutches,
            hatchlings = hatchlings,
            memorialPhotos = memorialPhotos,
            calendarEntries = calendarEntryStore.exportAll()
        )
    }

    private fun profilePhotoEntry(id: Long, uriValue: String) = "$PHOTO_ENTRY_PREFIX/profile_$id.${photoExtension(uriValue)}"
    private fun memorialPhotoEntry(id: Long, uriValue: String) = "$PHOTO_ENTRY_PREFIX/memorial_$id.${photoExtension(uriValue)}"

    private fun photoExtension(uriValue: String): String =
        Uri.parse(uriValue).lastPathSegment?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it in KNOWN_PHOTO_EXTENSIONS } ?: "jpg"

    private fun BackupSnapshot.toJson(): JSONObject = JSONObject().apply {
        put("format", BACKUP_FORMAT)
        put("schemaVersion", ARCHIVE_SCHEMA_VERSION)
        put("createdAt", createdAt)
        put("profiles", JSONArray().apply {
            profiles.forEach { profile ->
                put(JSONObject().apply {
                    val entity = profile.entity
                    put("id", entity.id)
                    put("name", entity.name)
                    put("species", entity.species)
                    put("morph", entity.morph)
                    put("gender", entity.gender)
                    put("referenceDate", entity.referenceDate)
                    put("referenceDateType", entity.referenceDateType)
                    put("createdAt", entity.createdAt)
                    putNullable("hatchingDate", entity.hatchingDate)
                    putNullable("adoptionDate", entity.adoptionDate)
                    putNullable("deathDate", entity.deathDate)
                    putNullable("memorialNote", entity.memorialNote)
                    putNullable("photoEntry", entity.photoUri?.let { profilePhotoEntry(entity.id, it) })
                })
            }
        })
        put("memorialPhotos", JSONArray().apply {
            memorialPhotos.forEach { photo ->
                put(JSONObject().apply {
                    put("id", photo.id)
                    put("reptileId", photo.reptileId)
                    put("addedAt", photo.addedAt)
                    putNullable("photoEntry", photo.sourceUri?.let { memorialPhotoEntry(photo.id, it) })
                })
            }
        })
        put("weightRecords", JSONArray().apply {
            weightRecords.forEach { record ->
                put(JSONObject().apply {
                    put("id", record.id)
                    put("reptileId", record.reptileId)
                    put("recordedAt", record.recordedAt)
                    put("grams", record.grams.toDouble())
                })
            }
        })
        put("careSchedules", JSONArray().apply {
            careSchedules.forEach { schedule ->
                put(JSONObject().apply {
                    put("id", schedule.id)
                    put("reptileId", schedule.reptileId)
                    put("scheduledDate", schedule.scheduledDate)
                    put("careType", schedule.careType)
                    put("memo", schedule.memo)
                    putNullable("repeatDayOfWeek", schedule.repeatDayOfWeek)
                })
            }
        })
        put("careLogs", JSONArray().apply {
            careLogs.forEach { log ->
                put(JSONObject().apply {
                    put("id", log.id)
                    put("scheduleId", log.scheduleId)
                    put("completedDate", log.completedDate)
                    put("status", log.status)
                })
            }
        })
        put("breedingRecords", JSONArray().apply {
            breedingRecords.forEach { record ->
                put(JSONObject().apply {
                    put("id", record.id)
                    putNullable("maleReptileId", record.maleReptileId)
                    putNullable("maleName", record.maleName)
                    putNullable("femaleReptileId", record.femaleReptileId)
                    putNullable("femaleName", record.femaleName)
                    put("layingDates", record.layingDates)
                    put("hatchingDates", record.hatchingDates)
                    put("memo", record.memo)
                })
            }
        })
        put("breedingPairs", JSONArray().apply {
            breedingPairs.forEach { pair ->
                put(JSONObject().apply {
                    put("id", pair.id)
                    putNullable("maleReptileId", pair.maleReptileId)
                    put("maleName", pair.maleName)
                    putNullable("femaleReptileId", pair.femaleReptileId)
                    put("femaleName", pair.femaleName)
                    put("matingDate", pair.matingDate)
                    put("sortOrder", pair.sortOrder)
                    put("createdAt", pair.createdAt)
                })
            }
        })
        put("clutches", JSONArray().apply {
            clutches.forEach { clutch ->
                put(JSONObject().apply {
                    put("id", clutch.id)
                    put("pairId", clutch.pairId)
                    put("clutchNumber", clutch.clutchNumber)
                    put("layingDate", clutch.layingDate)
                    put("infertileEggCount", clutch.infertileEggCount)
                    put("fertileEggCount", clutch.fertileEggCount)
                    put("lostEggCount", clutch.lostEggCount)
                    putNullable("incubatorTemp", clutch.incubatorTemp)
                    putNullable("ddayEndDate", clutch.ddayEndDate)
                    put("createdAt", clutch.createdAt)
                })
            }
        })
        put("hatchlings", JSONArray().apply {
            hatchlings.forEach { hatchling ->
                put(JSONObject().apply {
                    put("id", hatchling.id)
                    put("clutchId", hatchling.clutchId)
                    put("normalCount", hatchling.normalCount)
                    put("deathCount", hatchling.deathCount)
                    put("disabledCount", hatchling.disabledCount)
                    putNullable("disabledReason", hatchling.disabledReason)
                    put("midDropCount", hatchling.midDropCount)
                    put("createdAt", hatchling.createdAt)
                })
            }
        })
        put("calendarEntries", JSONObject().apply {
            calendarEntries.forEach { (key, value) ->
                when (value) {
                    is Boolean -> put(key, value)
                    is String -> put(key, value)
                }
            }
        })
    }

    /** [archive]가 있으면 ZIP 백업(사진은 별도 엔트리), null이면 예전 v1 JSON 백업(Base64 인라인). */
    private fun parseSnapshot(json: String, archive: ZipFile?): BackupSnapshot {
        val root = runCatching { JSONObject(json) }
            .getOrElse { throw InvalidBackupException("백업 파일을 읽을 수 없습니다") }
        val expectedVersion = if (archive != null) ARCHIVE_SCHEMA_VERSION else LEGACY_SCHEMA_VERSION
        if (root.optString("format") != BACKUP_FORMAT || root.optInt("schemaVersion") != expectedVersion) {
            throw InvalidBackupException("지원하지 않는 백업 형식입니다")
        }
        val backupCreatedAt = root.optLong("createdAt", -1L)
        if (backupCreatedAt <= 0L) throw InvalidBackupException("백업 생성 시간이 올바르지 않습니다")

        val profiles = root.requiredArray("profiles").mapObjects { value ->
            val id = value.requiredPositiveLong("id")
            val photo = readPhotoReference(value, archive, "프로필 사진")
            ProfileSnapshot(
                entity = Reptile(
                    id,
                    value.getString("name"),
                    value.optString("species", ""),
                    value.optString("morph", ""),
                    value.optString("gender", "미구분"),
                    value.getLong("referenceDate"),
                    value.optString("referenceDateType", "해칭일"),
                    null,
                    value.optLong("createdAt", backupCreatedAt)
                ).apply {
                    hatchingDate = value.nullableLong("hatchingDate")
                    adoptionDate = value.nullableLong("adoptionDate")
                    deathDate = value.nullableLong("deathDate")
                    memorialNote = value.nullableString("memorialNote")
                },
                photo = photo
            )
        }
        val profileIds = profiles.map { it.entity.id }.toSet()
        if (profileIds.size != profiles.size) throw InvalidBackupException("중복된 프로필이 있습니다")

        val weights = root.optionalArray("weightRecords").mapObjects { value ->
            WeightRecord(
                value.requiredPositiveLong("id"),
                value.requiredPositiveLong("reptileId"),
                value.getLong("recordedAt"),
                value.getDouble("grams").toFloat()
            )
        }
        val schedules = root.optionalArray("careSchedules").mapObjects { value ->
            CareSchedule().apply {
                id = value.requiredPositiveLong("id")
                reptileId = value.requiredPositiveLong("reptileId")
                scheduledDate = value.getLong("scheduledDate")
                careType = value.optString("careType", "")
                memo = value.optString("memo", "")
                repeatDayOfWeek = value.nullableInt("repeatDayOfWeek")
            }
        }
        val scheduleIds = schedules.map(CareSchedule::id).toSet()
        val logs = root.optionalArray("careLogs").mapObjects { value ->
            CareLog().apply {
                id = value.requiredPositiveLong("id")
                scheduleId = value.requiredPositiveLong("scheduleId")
                completedDate = value.getLong("completedDate")
                status = value.optString("status", "")
            }
        }
        val breeding = root.optionalArray("breedingRecords").mapObjects { value ->
            BreedingRecord().apply {
                id = value.requiredPositiveLong("id")
                maleReptileId = value.nullableLong("maleReptileId")
                maleName = value.nullableString("maleName")
                femaleReptileId = value.nullableLong("femaleReptileId")
                femaleName = value.nullableString("femaleName")
                layingDates = value.optString("layingDates", "")
                hatchingDates = value.optString("hatchingDates", "")
                memo = value.optString("memo", "")
            }
        }

        val breedingPairs = root.optionalArray("breedingPairs").mapObjects { value ->
            BreedingPair().apply {
                id = value.requiredPositiveLong("id")
                maleReptileId = value.nullableLong("maleReptileId")
                maleName = value.optString("maleName", "")
                femaleReptileId = value.nullableLong("femaleReptileId")
                femaleName = value.optString("femaleName", "")
                matingDate = value.getLong("matingDate")
                sortOrder = value.optInt("sortOrder", 0)
                createdAt = value.optLong("createdAt", backupCreatedAt)
            }
        }
        val pairIds = breedingPairs.map { it.id }.toSet()
        val clutches = root.optionalArray("clutches").mapObjects { value ->
            Clutch().apply {
                id = value.requiredPositiveLong("id")
                pairId = value.requiredPositiveLong("pairId")
                clutchNumber = value.optInt("clutchNumber", 1)
                layingDate = value.getLong("layingDate")
                infertileEggCount = value.optInt("infertileEggCount", 0)
                fertileEggCount = value.optInt("fertileEggCount", 0)
                lostEggCount = value.optInt("lostEggCount", 0)
                incubatorTemp = value.nullableDouble("incubatorTemp")
                // 예전 백업에는 없는 값이라 없으면 진행 중(null)으로 복원된다.
                ddayEndDate = value.nullableLong("ddayEndDate")
                createdAt = value.optLong("createdAt", backupCreatedAt)
            }
        }
        val clutchIds = clutches.map { it.id }.toSet()
        val hatchlings = root.optionalArray("hatchlings").mapObjects { value ->
            Hatchling().apply {
                id = value.requiredPositiveLong("id")
                clutchId = value.requiredPositiveLong("clutchId")
                normalCount = value.optInt("normalCount", 0)
                deathCount = value.optInt("deathCount", 0)
                disabledCount = value.optInt("disabledCount", 0)
                disabledReason = value.nullableString("disabledReason")
                midDropCount = value.optInt("midDropCount", 0)
                createdAt = value.optLong("createdAt", backupCreatedAt)
            }
        }
        if (clutches.any { it.pairId !in pairIds }) {
            throw InvalidBackupException("브리딩 짝과 연결되지 않은 클러치 기록이 있습니다")
        }
        if (hatchlings.any { it.clutchId !in clutchIds }) {
            throw InvalidBackupException("클러치와 연결되지 않은 해츨링 기록이 있습니다")
        }

        val memorialPhotos = root.optionalArray("memorialPhotos").mapObjects { value ->
            MemorialPhotoSnapshot(
                id = value.requiredPositiveLong("id"),
                reptileId = value.requiredPositiveLong("reptileId"),
                addedAt = value.getLong("addedAt"),
                sourceUri = null,
                photo = readPhotoReference(value, archive, "추억 사진")
            )
        }

        if (weights.any { it.reptileId !in profileIds } ||
            schedules.any { it.reptileId !in profileIds } ||
            memorialPhotos.any { it.reptileId !in profileIds }
        ) {
            throw InvalidBackupException("프로필과 연결되지 않은 기록이 있습니다")
        }
        if (logs.any { it.scheduleId !in scheduleIds }) {
            throw InvalidBackupException("관리 일정과 연결되지 않은 완료 기록이 있습니다")
        }

        val calendarEntries: Map<String, Any> = buildMap {
            val calendarObj = root.optJSONObject("calendarEntries")
            calendarObj?.keys()?.forEach { key ->
                when (val value = calendarObj.opt(key)) {
                    is Boolean -> put(key, value)
                    is String -> put(key, value)
                }
            }
        }

        return BackupSnapshot(
            createdAt = backupCreatedAt,
            profiles = profiles,
            weightRecords = weights,
            careSchedules = schedules,
            careLogs = logs,
            breedingRecords = breeding,
            breedingPairs = breedingPairs,
            clutches = clutches,
            hatchlings = hatchlings,
            memorialPhotos = memorialPhotos,
            calendarEntries = calendarEntries
        )
    }

    /** ZIP 백업이면 "photoEntry"가 가리키는 엔트리를, 예전 JSON 백업이면 "photo" 안의 Base64를 읽는다. */
    private fun readPhotoReference(value: JSONObject, archive: ZipFile?, label: String): BackupPhoto? {
        if (archive != null) {
            val name = value.nullableString("photoEntry") ?: return null
            val entry = archive.getEntry(name)
                ?: throw InvalidBackupException("백업에 없는 $label 를 가리키고 있습니다")
            return BackupPhoto.Entry(archive, entry)
        }
        if (!value.has("photo") || value.isNull("photo")) return null
        val photoValue = value.getJSONObject("photo")
        val bytes = runCatching { Base64.decode(photoValue.getString("base64"), Base64.DEFAULT) }
            .getOrElse { throw InvalidBackupException("$label 데이터가 손상되었습니다") }
        if (bytes.isEmpty()) throw InvalidBackupException("비어 있는 $label 이 있습니다")
        return BackupPhoto.Inline(photoValue.optString("mimeType", "image/jpeg"), bytes)
    }

    private fun listBackupFiles(accessToken: String): List<RemoteBackup> {
        // 구버전으로 백업해 둔 사용자도 복원할 수 있어야 하므로 예전 JSON 파일명까지 함께 찾는다.
        val query = URLEncoder.encode(
            "(name = '$ARCHIVE_FILE_NAME' or name = '$LEGACY_FILE_NAME') and trashed = false",
            "UTF-8"
        )
        val fields = URLEncoder.encode("files(id,modifiedTime,size)", "UTF-8")
        val response = request(
            url = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=$query&orderBy=modifiedTime%20desc&pageSize=20&fields=$fields",
            method = "GET",
            accessToken = accessToken
        )
        val files = JSONObject(String(response, StandardCharsets.UTF_8)).optJSONArray("files") ?: JSONArray()
        return files.mapObjects { value ->
            RemoteBackup(
                id = value.getString("id"),
                modifiedAt = runCatching { Instant.parse(value.getString("modifiedTime")).toEpochMilli() }.getOrDefault(0L),
                sizeBytes = value.optString("size").toLongOrNull()
            )
        }
    }

    /** multipart 본문을 통째로 메모리에 만들지 않고, 앞뒤 헤더 사이로 ZIP 파일을 그대로 흘려보낸다. */
    private fun uploadArchive(accessToken: String, archive: File) {
        val boundary = "beardylog_${System.currentTimeMillis()}"
        val metadata = JSONObject().apply {
            put("name", ARCHIVE_FILE_NAME)
            put("mimeType", ARCHIVE_MIME_TYPE)
            put("parents", JSONArray().put("appDataFolder"))
        }
        val prefix = (
            "--$boundary\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                metadata.toString() +
                "\r\n--$boundary\r\n" +
                "Content-Type: $ARCHIVE_MIME_TYPE\r\n\r\n"
            ).toByteArray(StandardCharsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)
        streamingRequest(
            url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
            accessToken = accessToken,
            contentType = "multipart/related; boundary=$boundary",
            contentLength = prefix.size + archive.length() + suffix.size
        ) { output ->
            output.write(prefix)
            archive.inputStream().use { it.copyTo(output) }
            output.write(suffix)
        }
    }

    private fun streamingRequest(
        url: String,
        accessToken: String,
        contentType: String,
        contentLength: Long,
        writeBody: (OutputStream) -> Unit
    ) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", contentType)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(contentLength)
            connection.outputStream.use(writeBody)
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) throw DriveBackupException(failureMessage(connection, responseCode))
        } finally {
            connection.disconnect()
        }
    }

    /** 응답을 메모리에 담지 않고 파일로 바로 받는다. 백업이 커도 다운로드 자체는 상수 메모리. */
    private fun downloadTo(url: String, accessToken: String, target: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) throw DriveBackupException(failureMessage(connection, responseCode))
            connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } finally {
            connection.disconnect()
        }
    }

    private fun failureMessage(connection: HttpURLConnection, responseCode: Int): String =
        driveErrorMessage(connection.errorStream?.use { it.readBytes() } ?: ByteArray(0), responseCode)

    /** Drive 오류 응답을 사용자에게 보여줄 문장으로 바꾼다. 저장공간 부족은 영어 원문 대신 해결 방법을 안내한다. */
    private fun driveErrorMessage(body: ByteArray, responseCode: Int): String {
        val error = runCatching { JSONObject(String(body, StandardCharsets.UTF_8)).optJSONObject("error") }.getOrNull()
        val reason = error?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")
        if (reason == "storageQuotaExceeded") {
            return "Google 계정 저장공간이 부족해 백업하지 못했습니다. 저장공간을 정리한 뒤 다시 시도하세요 (기존 백업은 그대로 남아 있습니다)"
        }
        return error?.optString("message").takeUnless { it.isNullOrBlank() } ?: "Google Drive 요청 실패 ($responseCode)"
    }

    private fun deleteFile(accessToken: String, fileId: String) {
        request(
            url = "https://www.googleapis.com/drive/v3/files/$fileId",
            method = "DELETE",
            accessToken = accessToken
        )
    }

    /** 목록/삭제처럼 응답이 작은 요청 전용. 백업 본문은 streamingRequest/downloadTo를 쓴다. */
    private fun request(url: String, method: String, accessToken: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            val responseCode = connection.responseCode
            val response = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() }
                ?: ByteArray(0)
            if (responseCode !in 200..299) throw DriveBackupException(driveErrorMessage(response, responseCode))
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun BackupSnapshot.calendarDatesCount(): Int =
        calendarEntries.keys.mapNotNull { it.substringBefore('_', "").toLongOrNull() }.toSet().size

    private fun JSONObject.putNullable(name: String, value: Any?) {
        put(name, value ?: JSONObject.NULL)
    }

    private fun JSONObject.nullableLong(name: String): Long? =
        if (!has(name) || isNull(name)) null else getLong(name)

    private fun JSONObject.nullableInt(name: String): Int? =
        if (!has(name) || isNull(name)) null else getInt(name)

    private fun JSONObject.nullableDouble(name: String): Double? =
        if (!has(name) || isNull(name)) null else getDouble(name)

    private fun JSONObject.nullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)

    private fun JSONObject.requiredPositiveLong(name: String): Long = getLong(name).also { value ->
        if (value <= 0L) throw InvalidBackupException("올바르지 않은 $name 값이 있습니다")
    }

    private fun JSONObject.requiredArray(name: String): JSONArray =
        optJSONArray(name) ?: throw InvalidBackupException("$name 데이터가 없습니다")

    private fun JSONObject.optionalArray(name: String): JSONArray = optJSONArray(name) ?: JSONArray()

    private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        (0 until length()).map { index ->
            val value = optJSONObject(index) ?: throw InvalidBackupException("백업 항목 형식이 올바르지 않습니다")
            transform(value)
        }

    internal data class BackupSnapshot(
        val createdAt: Long,
        val profiles: List<ProfileSnapshot>,
        val weightRecords: List<WeightRecord>,
        val careSchedules: List<CareSchedule>,
        val careLogs: List<CareLog>,
        val breedingRecords: List<BreedingRecord>,
        val breedingPairs: List<BreedingPair>,
        val clutches: List<Clutch>,
        val hatchlings: List<Hatchling>,
        val memorialPhotos: List<MemorialPhotoSnapshot>,
        val calendarEntries: Map<String, Any>
    )

    internal data class ProfileSnapshot(val entity: Reptile, val photo: BackupPhoto?)

    /** [sourceUri]는 백업할 때(앱 안의 원본 경로), [photo]는 복원할 때(백업 안의 사진) 채워진다. */
    internal data class MemorialPhotoSnapshot(
        val id: Long,
        val reptileId: Long,
        val addedAt: Long,
        val sourceUri: String?,
        val photo: BackupPhoto?
    )

    /** 복원할 사진 한 장의 출처. ZIP 백업이면 아카이브 안에 남아 있다가 파일로 쓸 때 꺼내지고,
     *  예전 JSON 백업이면 이미 디코드된 바이트를 들고 있다. */
    internal sealed interface BackupPhoto {
        val extension: String
        fun writeTo(target: File)

        class Entry(private val archive: ZipFile, private val entry: ZipEntry) : BackupPhoto {
            override val extension: String get() = entry.name.substringAfterLast('.', "jpg")
            override fun writeTo(target: File) {
                archive.getInputStream(entry).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
        }

        class Inline(private val mimeType: String, private val data: ByteArray) : BackupPhoto {
            override val extension: String get() = when (mimeType.lowercase()) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            override fun writeTo(target: File) {
                target.writeBytes(data)
            }
        }
    }

    class NoBackupFoundException : Exception("Google Drive에 저장된 백업이 없습니다")
    class NoProfilesToBackupException : Exception("백업할 프로필이 없습니다. 재설치했다면 먼저 복원하세요")
    class InvalidBackupException(message: String) : Exception(message)
    class DriveBackupException(message: String) : Exception(message)

    companion object {
        private const val BACKUP_FORMAT = "beardylog-profile-backup"

        /** ZIP 컨테이너. manifest.json에는 DB 레코드만 있고 사진은 photos/ 엔트리로 따로 들어간다. */
        private const val ARCHIVE_SCHEMA_VERSION = 2
        private const val ARCHIVE_FILE_NAME = "beardylog_profile_backup_v2.zip"
        private const val ARCHIVE_MIME_TYPE = "application/zip"
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val PHOTO_ENTRY_PREFIX = "photos"
        private val KNOWN_PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        /** 사진을 Base64로 인라인하던 예전 형식. 새로 쓰지는 않고 복원만 지원한다. */
        private const val LEGACY_SCHEMA_VERSION = 1
        private const val LEGACY_FILE_NAME = "beardylog_profile_backup_v1.json"
        /** Drive에 보관하는 백업 개수. 넘치면 가장 오래된 것부터 지운다. */
        const val MAX_BACKUPS = 3
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
    }
}
