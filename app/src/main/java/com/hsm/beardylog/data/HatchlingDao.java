package com.hsm.beardylog.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

@Dao
public interface HatchlingDao {
    @Query("SELECT * FROM hatchlings ORDER BY clutchId, id") LiveData<List<Hatchling>> observeAll();
    @Query("SELECT * FROM hatchlings ORDER BY clutchId, id") List<Hatchling> all();
    @Query("SELECT * FROM hatchlings WHERE clutchId = :clutchId ORDER BY id") List<Hatchling> forClutch(long clutchId);
    @Insert long insert(Hatchling hatchling);
    @Insert void insertAll(List<Hatchling> hatchlings);
    @Update void update(Hatchling hatchling);
    @Delete void delete(Hatchling hatchling);
    @Query("DELETE FROM hatchlings") void deleteAll();
}
