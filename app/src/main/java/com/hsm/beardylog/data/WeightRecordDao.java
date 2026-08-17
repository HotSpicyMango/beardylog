package com.hsm.beardylog.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface WeightRecordDao {
    @Query("SELECT * FROM weight_records WHERE reptileId = :reptileId ORDER BY recordedAt DESC") LiveData<List<WeightRecord>> observeForReptile(long reptileId);
    @Query("SELECT * FROM weight_records WHERE reptileId = :reptileId ORDER BY recordedAt ASC") List<WeightRecord> allForReptile(long reptileId);
    @Insert long insert(WeightRecord record);
}
