package com.hsm.beardylog.data;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {Reptile.class, WeightRecord.class, CareSchedule.class, CareLog.class, BreedingRecord.class}, version = 2, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public abstract ReptileDao reptileDao();
    public abstract WeightRecordDao weightRecordDao();
    public abstract CareScheduleDao careScheduleDao();
    private static volatile AppDatabase instance;
    public static AppDatabase getInstance(Context context) {
        if (instance == null) synchronized (AppDatabase.class) {
            if (instance == null) instance = Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, "beardylog.db").fallbackToDestructiveMigration().build();
        }
        return instance;
    }
}
