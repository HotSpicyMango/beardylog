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
    @Query("SELECT * FROM care_schedules ORDER BY id") List<CareSchedule> all();
    // 홈 화면이 '이번 주'를 렌더링 시점에 계산하므로 쿼리에 날짜를 넣지 않는다.
    // 날짜를 쿼리에 굳혀두면 앱이 며칠 떠 있을 때 지난 주 일정을 계속 보여주게 된다.
    @Query("SELECT * FROM care_schedules ORDER BY scheduledDate, id")
    LiveData<List<CareSchedule>> observeAll();
    @Query("SELECT * FROM care_schedules WHERE reptileId = :reptileId ORDER BY scheduledDate, id") LiveData<List<CareSchedule>> observeForReptile(long reptileId);
    @Insert long insert(CareSchedule schedule);
    @Insert void insertAll(List<CareSchedule> schedules);
    @Update void update(CareSchedule schedule);
    @Delete void delete(CareSchedule schedule);
    @Query("DELETE FROM care_schedules") void deleteAll();
}
