package com.hsm.beardylog.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;
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

    /** 같은 날짜의 기존 기록을 지우고 새로 쓴다. 한 트랜잭션이라 중간에 끊겨도 기록이 사라지지 않는다. */
    @Transaction
    default void setStatus(long scheduleId, long date, String status) {
        deleteForDate(scheduleId, date);
        CareLog log = new CareLog();
        log.scheduleId = scheduleId;
        log.completedDate = date;
        log.status = status;
        insert(log);
    }

    @Insert long insert(CareLog log);
    @Insert void insertAll(List<CareLog> logs);

    @Query("DELETE FROM care_logs")
    void deleteAll();
}
