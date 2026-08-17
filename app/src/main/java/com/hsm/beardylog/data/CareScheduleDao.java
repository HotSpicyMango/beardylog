package com.hsm.beardylog.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface CareScheduleDao {
    @Query("SELECT * FROM care_schedules WHERE scheduledDate BETWEEN :startDate AND :endDate ORDER BY scheduledDate, id")
    LiveData<List<CareSchedule>> observeBetween(long startDate, long endDate);
    @Query("SELECT * FROM care_schedules WHERE scheduledDate BETWEEN :startDate AND :endDate") List<CareSchedule> findBetween(long startDate, long endDate);
    @Insert long insert(CareSchedule schedule);
}
