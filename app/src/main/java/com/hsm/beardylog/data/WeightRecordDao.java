package com.hsm.beardylog.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Delete;
import androidx.room.Update;
import java.util.List;

@Dao
public interface WeightRecordDao {
    @Query("SELECT * FROM weight_records WHERE reptileId = :reptileId ORDER BY recordedAt DESC") LiveData<List<WeightRecord>> observeForReptile(long reptileId);
    @Query("SELECT * FROM weight_records ORDER BY id") List<WeightRecord> all();
    @Query("SELECT * FROM weight_records WHERE reptileId = :reptileId ORDER BY recordedAt ASC") List<WeightRecord> allForReptile(long reptileId);
    @Query("SELECT * FROM weight_records WHERE reptileId = :reptileId AND recordedAt = :recordedAt ORDER BY id LIMIT 1") WeightRecord findForDate(long reptileId, long recordedAt);
    @Insert long insert(WeightRecord record);
    @Insert void insertAll(List<WeightRecord> records);
    @Update void update(WeightRecord record);
    @Delete void delete(WeightRecord record);
    @Query("DELETE FROM weight_records") void deleteAll();
}
