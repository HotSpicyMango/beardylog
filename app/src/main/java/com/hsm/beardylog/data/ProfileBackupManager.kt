package com.hsm.beardylog.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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

    class RestorePreview internal constructor(
        val createdAt: Long,
        val profileCount: Int,
        val photoCount: Int,
        val calendarDatesCount: Int,
        val breedingPairCount: Int,
        internal val snapshot: BackupSnapshot
    )

    /** 새로 백업하면 기존 백업 파일이 지워지므로(단일 슬롯), 업로드 전에 미리 경고할 때 쓴다. */
    fun hasExistingBackup(accessToken: String): Boolean = listBackupFiles(accessToken).isNotEmpty()

    fun upload(accessToken: String): BackupResult {
        val snapshot = captureSnapshot()
        if (snapshot.profiles.isEmpty()) throw NoProfilesToBackupException()
        val body = createSnapshotJson(snapshot).toByteArray(StandardCharsets.UTF_8)
        val previousFiles = listBackupFiles(accessToken)
        createBackupFile(accessToken, body)
        previousFiles.forEach { remote ->
            runCatching { deleteFile(accessToken, remote.id) }
        }
        return snapshot.result()
    }

    fun downloadLatest(accessToken: String): RestorePreview {
        val remote = listBackupFiles(accessToken).firstOrNull()
            ?: throw NoBackupFoundException()
        val bytes = request(
            url = "https://www.googleapis.com/drive/v3/files/${remote.id}?alt=media",
            method = "GET",
            accessToken = accessToken
        )
        return previewSnapshot(String(bytes, StandardCharsets.UTF_8))
    }

    internal fun createSnapshotJson(snapshot: BackupSnapshot = captureSnapshot()): String =
        snapshot.toJson().toString()

    internal fun previewSnapshot(json: String): RestorePreview {
        val snapshot = try {
            parseSnapshot(json)
        } catch (error: InvalidBackupException) {
            throw error
        } catch (_: Throwable) {
            throw InvalidBackupException("백업 데이터가 손상되었습니다")
        }
        return RestorePreview(
            createdAt = snapshot.createdAt,
            profileCount = snapshot.profiles.size,
            photoCount = snapshot.profiles.count { it.photo != null } + snapshot.memorialPhotos.size,
            calendarDatesCount = snapshot.calendarDatesCount(),
            breedingPairCount = snapshot.breedingPairs.size,
            snapshot = snapshot
        )
    }

    fun restore(preview: RestorePreview): BackupResult {
        val restoredPhotoFiles = mutableListOf<File>()
        val restoredProfiles = preview.snapshot.profiles.map { profile ->
            val source = profile.entity
            val restoredPhotoUri = profile.photo?.let { photo ->
                val directory = File(appContext.filesDir, RESTORED_PHOTO_DIRECTORY).apply { mkdirs() }
                check(directory.isDirectory) { "프로필 사진 저장 공간을 만들지 못했습니다" }
                val extension = when (photo.mimeType.lowercase()) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }
                File(directory, "profile_${source.id}_${System.currentTimeMillis()}.$extension").also { file ->
                    file.writeBytes(photo.data)
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
        val restoredMemorialPhotos = preview.snapshot.memorialPhotos.map { photo ->
            val directory = File(appContext.filesDir, MEMORIAL_PHOTO_DIRECTORY).apply { mkdirs() }
            check(directory.isDirectory) { "추억 사진 저장 공간을 만들지 못했습니다" }
            val extension = when (photo.photo.mimeType.lowercase()) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val file = File(directory, "memorial_${photo.reptileId}_${System.currentTimeMillis()}_${photo.id}.$extension").also { file ->
                file.writeBytes(photo.photo.data)
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

        var skippedPhotos = 0
        val profiles = reptiles.map { reptile ->
            val photo = reptile.photoUri?.let(::readPhoto)
            if (reptile.photoUri != null && photo == null) skippedPhotos += 1
            ProfileSnapshot(reptile, photo)
        }
        val memorialPhotos = memorialPhotoEntities.mapNotNull { entity ->
            val photo = readPhoto(entity.photoUri)
            if (photo == null) {
                skippedPhotos += 1
                null
            } else {
                MemorialPhotoSnapshot(entity.id, entity.reptileId, entity.addedAt, photo)
            }
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
            calendarEntries = calendarEntryStore.exportAll(),
            skippedPhotoCount = skippedPhotos
        )
    }

    private fun readPhoto(uriValue: String): BackupPhoto? = runCatching {
        val uri = Uri.parse(uriValue)
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@runCatching null
        if (bytes.isEmpty()) return@runCatching null
        val mimeType = appContext.contentResolver.getType(uri)
            ?: when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }
        BackupPhoto(mimeType, bytes)
    }.getOrNull()

    private fun BackupSnapshot.toJson(): JSONObject = JSONObject().apply {
        put("format", BACKUP_FORMAT)
        put("schemaVersion", SCHEMA_VERSION)
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
                    put("photo", profile.photo?.let { photo ->
                        JSONObject().apply {
                            put("mimeType", photo.mimeType)
                            put("base64", Base64.encodeToString(photo.data, Base64.NO_WRAP))
                        }
                    } ?: JSONObject.NULL)
                })
            }
        })
        put("memorialPhotos", JSONArray().apply {
            memorialPhotos.forEach { photo ->
                put(JSONObject().apply {
                    put("id", photo.id)
                    put("reptileId", photo.reptileId)
                    put("addedAt", photo.addedAt)
                    put("photo", JSONObject().apply {
                        put("mimeType", photo.photo.mimeType)
                        put("base64", Base64.encodeToString(photo.photo.data, Base64.NO_WRAP))
                    })
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

    private fun parseSnapshot(json: String): BackupSnapshot {
        val root = runCatching { JSONObject(json) }
            .getOrElse { throw InvalidBackupException("백업 파일을 읽을 수 없습니다") }
        if (root.optString("format") != BACKUP_FORMAT || root.optInt("schemaVersion") != SCHEMA_VERSION) {
            throw InvalidBackupException("지원하지 않는 백업 형식입니다")
        }
        val backupCreatedAt = root.optLong("createdAt", -1L)
        if (backupCreatedAt <= 0L) throw InvalidBackupException("백업 생성 시간이 올바르지 않습니다")

        val profiles = root.requiredArray("profiles").mapObjects { value ->
            val id = value.requiredPositiveLong("id")
            val photo = if (value.isNull("photo")) null else value.getJSONObject("photo").let { photoValue ->
                val mimeType = photoValue.optString("mimeType", "image/jpeg")
                val bytes = runCatching { Base64.decode(photoValue.getString("base64"), Base64.DEFAULT) }
                    .getOrElse { throw InvalidBackupException("프로필 사진 데이터가 손상되었습니다") }
                if (bytes.isEmpty()) throw InvalidBackupException("비어 있는 프로필 사진이 있습니다")
                BackupPhoto(mimeType, bytes)
            }
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
            val photoValue = value.getJSONObject("photo")
            val mimeType = photoValue.optString("mimeType", "image/jpeg")
            val bytes = runCatching { Base64.decode(photoValue.getString("base64"), Base64.DEFAULT) }
                .getOrElse { throw InvalidBackupException("추억 사진 데이터가 손상되었습니다") }
            if (bytes.isEmpty()) throw InvalidBackupException("비어 있는 추억 사진이 있습니다")
            MemorialPhotoSnapshot(
                id = value.requiredPositiveLong("id"),
                reptileId = value.requiredPositiveLong("reptileId"),
                addedAt = value.getLong("addedAt"),
                photo = BackupPhoto(mimeType, bytes)
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
            calendarEntries = calendarEntries,
            skippedPhotoCount = 0
        )
    }

    private fun listBackupFiles(accessToken: String): List<RemoteFile> {
        val query = URLEncoder.encode("name = '$BACKUP_FILE_NAME' and trashed = false", "UTF-8")
        val fields = URLEncoder.encode("files(id,modifiedTime)", "UTF-8")
        val response = request(
            url = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=$query&orderBy=modifiedTime%20desc&pageSize=20&fields=$fields",
            method = "GET",
            accessToken = accessToken
        )
        val files = JSONObject(String(response, StandardCharsets.UTF_8)).optJSONArray("files") ?: JSONArray()
        return files.mapObjects { value ->
            RemoteFile(value.getString("id"))
        }
    }

    private fun createBackupFile(accessToken: String, content: ByteArray) {
        val boundary = "beardylog_${System.currentTimeMillis()}"
        val metadata = JSONObject().apply {
            put("name", BACKUP_FILE_NAME)
            put("mimeType", BACKUP_MIME_TYPE)
            put("parents", JSONArray().put("appDataFolder"))
        }
        val body = ByteArrayOutputStream().apply {
            writeUtf8("--$boundary\r\n")
            writeUtf8("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            writeUtf8(metadata.toString())
            writeUtf8("\r\n--$boundary\r\n")
            writeUtf8("Content-Type: $BACKUP_MIME_TYPE\r\n\r\n")
            write(content)
            writeUtf8("\r\n--$boundary--\r\n")
        }.toByteArray()
        request(
            url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
            method = "POST",
            accessToken = accessToken,
            contentType = "multipart/related; boundary=$boundary",
            body = body
        )
    }

    private fun deleteFile(accessToken: String, fileId: String) {
        request(
            url = "https://www.googleapis.com/drive/v3/files/$fileId",
            method = "DELETE",
            accessToken = accessToken
        )
    }

    private fun request(
        url: String,
        method: String,
        accessToken: String,
        contentType: String? = null,
        body: ByteArray? = null
    ): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", contentType ?: "application/octet-stream")
                connection.outputStream.use { it.write(body) }
            }
            val responseCode = connection.responseCode
            val response = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() }
                ?: ByteArray(0)
            if (responseCode !in 200..299) {
                val detail = runCatching {
                    JSONObject(String(response, StandardCharsets.UTF_8))
                        .optJSONObject("error")
                        ?.optString("message")
                }.getOrNull().takeUnless { it.isNullOrBlank() }
                throw DriveBackupException(detail ?: "Google Drive 요청 실패 ($responseCode)")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun BackupSnapshot.calendarDatesCount(): Int =
        calendarEntries.keys.mapNotNull { it.substringBefore('_', "").toLongOrNull() }.toSet().size

    private fun BackupSnapshot.result(): BackupResult = BackupResult(
        createdAt = createdAt,
        profileCount = profiles.size,
        photoCount = profiles.count { it.photo != null } + memorialPhotos.size,
        skippedPhotoCount = skippedPhotoCount
    )

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

    private fun ByteArrayOutputStream.writeUtf8(value: String) {
        write(value.toByteArray(StandardCharsets.UTF_8))
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
        val calendarEntries: Map<String, Any>,
        val skippedPhotoCount: Int
    )

    internal data class ProfileSnapshot(val entity: Reptile, val photo: BackupPhoto?)
    internal data class MemorialPhotoSnapshot(val id: Long, val reptileId: Long, val addedAt: Long, val photo: BackupPhoto)
    internal data class BackupPhoto(val mimeType: String, val data: ByteArray)
    private data class RemoteFile(val id: String)

    class NoBackupFoundException : Exception("Google Drive에 저장된 백업이 없습니다")
    class NoProfilesToBackupException : Exception("백업할 프로필이 없습니다. 재설치했다면 먼저 복원하세요")
    class InvalidBackupException(message: String) : Exception(message)
    class DriveBackupException(message: String) : Exception(message)

    private companion object {
        const val BACKUP_FORMAT = "beardylog-profile-backup"
        const val SCHEMA_VERSION = 1
        const val BACKUP_FILE_NAME = "beardylog_profile_backup_v1.json"
        const val BACKUP_MIME_TYPE = "application/json"
        const val RESTORED_PHOTO_DIRECTORY = "profile_photos"
        const val MEMORIAL_PHOTO_DIRECTORY = "memorial_photos"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
    }
}
