package com.hsm.beardylog.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface BreedingRecordDao {
    @Query("SELECT * FROM breeding_records ORDER BY id")
    List<BreedingRecord> all();

    @Insert
    void insertAll(List<BreedingRecord> records);

    @Query("DELETE FROM breeding_records")
    void deleteAll();
}
