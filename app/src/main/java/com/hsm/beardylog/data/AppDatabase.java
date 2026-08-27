package com.hsm.beardylog.data;

import android.content.Context;
import android.net.Uri;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import java.io.File;

@Database(entities = {Reptile.class, WeightRecord.class, CareSchedule.class, CareLog.class, BreedingRecord.class, MemorialPhoto.class, BreedingPair.class, Clutch.class, Hatchling.class}, version = 9, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public abstract ReptileDao reptileDao();
    public abstract WeightRecordDao weightRecordDao();
    public abstract CareScheduleDao careScheduleDao();
    public abstract CareLogDao careLogDao();
    public abstract BreedingRecordDao breedingRecordDao();
    public abstract MemorialPhotoDao memorialPhotoDao();
    public abstract BreedingPairDao breedingPairDao();
    public abstract ClutchDao clutchDao();
    public abstract HatchlingDao hatchlingDao();
    private static volatile AppDatabase instance;
    public static AppDatabase getInstance(Context context) {
        if (instance == null) synchronized (AppDatabase.class) {
            if (instance == null) instance = Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, "beardylog.db")
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .build();
        }
        return instance;
    }

    private static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE reptiles ADD COLUMN hatchingDate INTEGER");
            database.execSQL("ALTER TABLE reptiles ADD COLUMN adoptionDate INTEGER");
        }
    };

    private static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE reptiles ADD COLUMN gender TEXT");
            database.execSQL("UPDATE reptiles SET gender = '미구분' WHERE gender IS NULL OR gender = ''");
        }
    };

    private static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override public void migrate(SupportSQLiteDatabase database) {
            // Version 5 changes app behavior only; the persisted schema is unchanged.
        }
    };

    private static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE reptiles ADD COLUMN deathDate INTEGER");
            database.execSQL("ALTER TABLE reptiles ADD COLUMN memorialNote TEXT");
        }
    };

    private static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `memorial_photos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `reptileId` INTEGER NOT NULL, `photoUri` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, FOREIGN KEY(`reptileId`) REFERENCES `reptiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_memorial_photos_reptileId` ON `memorial_photos` (`reptileId`)");
        }
    };

    private static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE `memorial_photos_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `reptileId` INTEGER NOT NULL, `photoUri` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, FOREIGN KEY(`reptileId`) REFERENCES `reptiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
            database.execSQL("INSERT INTO `memorial_photos_new` (`id`, `reptileId`, `photoUri`, `addedAt`) SELECT `id`, `reptileId`, `photoUri`, `addedAt` FROM `memorial_photos` WHERE `photoUri` IS NOT NULL");
            database.execSQL("DROP TABLE `memorial_photos`");
            database.execSQL("ALTER TABLE `memorial_photos_new` RENAME TO `memorial_photos`");
            database.execSQL("CREATE INDEX `index_memorial_photos_reptileId` ON `memorial_photos` (`reptileId`)");
        }
    };

    private static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `breeding_pairs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `maleReptileId` INTEGER, `maleName` TEXT, `femaleReptileId` INTEGER, `femaleName` TEXT, `matingDate` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)");
            database.execSQL("CREATE TABLE IF NOT EXISTS `clutches` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `pairId` INTEGER NOT NULL, `clutchNumber` INTEGER NOT NULL, `layingDate` INTEGER NOT NULL, `infertileEggCount` INTEGER NOT NULL, `fertileEggCount` INTEGER NOT NULL, `lostEggCount` INTEGER NOT NULL, `incubatorTemp` REAL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`pairId`) REFERENCES `breeding_pairs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_clutches_pairId` ON `clutches` (`pairId`)");
            database.execSQL("CREATE TABLE IF NOT EXISTS `hatchlings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `clutchId` INTEGER NOT NULL, `normalCount` INTEGER NOT NULL, `deathCount` INTEGER NOT NULL, `disabledCount` INTEGER NOT NULL, `disabledReason` TEXT, `midDropCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`clutchId`) REFERENCES `clutches`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_hatchlings_clutchId` ON `hatchlings` (`clutchId`)");
        }
    };

    /** Called when a profile converts to a memorial profile: wipes diet/calendar schedules and logs tied to it, keeping the reptile row (name/photo/species/dates), its weight_records, and its breeding_records intact. Irreversible. */
    public void clearActivityRecordsForReptile(long reptileId) {
        SupportSQLiteDatabase database = getOpenHelper().getWritableDatabase();
        database.execSQL("DELETE FROM care_logs WHERE scheduleId IN (SELECT id FROM care_schedules WHERE reptileId = " + reptileId + ")");
        database.execSQL("DELETE FROM care_schedules WHERE reptileId = " + reptileId);
        // Raw execSQL bypasses Room's DAO layer, so LiveData observers (e.g. the calendar section)
        // never learn the tables changed unless we nudge the invalidation tracker ourselves.
        getInvalidationTracker().refreshVersionsAsync();
    }

    /** Permanently deletes a reptile and everything tied to it (live or memorial profile alike): the profile
     *  photo and memorial album files on disk, then the row itself. weight_records/care_schedules/care_logs/
     *  memorial_photos rows cascade via FK; breeding_records has no FK so it's cleaned up manually here.
     *  breeding_pairs referencing this reptile are removed too, which cascades to their clutches/hatchlings via FK.
     *  Irreversible. Call off the main thread. */
    public void deleteReptileFully(long reptileId) {
        Reptile reptile = reptileDao().byId(reptileId);
        if (reptile != null && reptile.photoUri != null) {
            deleteFileQuietly(reptile.photoUri);
        }
        for (MemorialPhoto photo : memorialPhotoDao().forReptile(reptileId)) {
            deleteFileQuietly(photo.photoUri);
        }
        SupportSQLiteDatabase database = getOpenHelper().getWritableDatabase();
        database.execSQL("DELETE FROM breeding_records WHERE maleReptileId = " + reptileId + " OR femaleReptileId = " + reptileId);
        database.execSQL("DELETE FROM breeding_pairs WHERE maleReptileId = " + reptileId + " OR femaleReptileId = " + reptileId);
        database.execSQL("DELETE FROM reptiles WHERE id = " + reptileId);
        // Same reasoning as clearActivityRecordsForReptile: these are raw execSQL deletes, so Room's
        // LiveData (e.g. the 추억공간 list backed by ReptileDao.observeAll()) won't notice the change
        // and refresh on its own. Without this, a deleted memorial profile keeps showing on screen
        // until something unrelated happens to trigger Room's invalidation check.
        getInvalidationTracker().refreshVersionsAsync();
    }

    private static void deleteFileQuietly(String uriString) {
        try {
            String path = Uri.parse(uriString).getPath();
            if (path != null) new File(path).delete();
        } catch (Exception ignored) {
            // best-effort cleanup; a stray file left behind isn't worth failing the delete over
        }
    }

}
