package com.hsm.beardylog.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface CareLogDao {
    @Query("SELECT * FROM care_logs ORDER BY id")
    List<CareLog> all();

    @Query("SELECT * FROM care_logs ORDER BY completedDate DESC, id DESC")
    LiveData<List<CareLog>> observeAll();
    @Query("SELECT l.* FROM care_logs l INNER JOIN care_schedules s ON s.id = l.scheduleId WHERE s.reptileId = :reptileId ORDER BY l.completedDate DESC")
    LiveData<List<CareLog>> observeForReptile(long reptileId);

    @Query("DELETE FROM care_logs WHERE scheduleId = :scheduleId AND completedDate = :date")
    void deleteForDate(long scheduleId, long date);

    @Insert long insert(CareLog log);
    @Insert void insertAll(List<CareLog> logs);

    @Query("DELETE FROM care_logs")
    void deleteAll();
}
