package com.hsm.beardylog.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Update;
import androidx.room.Delete;
import androidx.room.Query;
import java.util.List;

@Dao
public interface CareScheduleDao {
    @Query("SELECT * FROM care_schedules WHERE (scheduledDate BETWEEN :startDate AND :endDate OR repeatDayOfWeek IS NOT NULL) ORDER BY scheduledDate, id")
    LiveData<List<CareSchedule>> observeBetween(long startDate, long endDate);
    @Query("SELECT * FROM care_schedules WHERE scheduledDate BETWEEN :startDate AND :endDate") List<CareSchedule> findBetween(long startDate, long endDate);
    @Query("SELECT * FROM care_schedules WHERE reptileId = :reptileId ORDER BY scheduledDate, id") LiveData<List<CareSchedule>> observeForReptile(long reptileId);
    @Insert long insert(CareSchedule schedule);
    @Update void update(CareSchedule schedule);
    @Delete void delete(CareSchedule schedule);
}
