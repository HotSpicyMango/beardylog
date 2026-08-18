package com.hsm.beardylog.data;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {Reptile.class, WeightRecord.class, CareSchedule.class, CareLog.class, BreedingRecord.class}, version = 4, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public abstract ReptileDao reptileDao();
    public abstract WeightRecordDao weightRecordDao();
    public abstract CareScheduleDao careScheduleDao();
    public abstract CareLogDao careLogDao();
    private static volatile AppDatabase instance;
    public static AppDatabase getInstance(Context context) {
        if (instance == null) synchronized (AppDatabase.class) {
            if (instance == null) instance = Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, "beardylog.db")
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4).fallbackToDestructiveMigration().build();
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
}
