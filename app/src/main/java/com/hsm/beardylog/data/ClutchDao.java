package com.hsm.beardylog.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

@Dao
public interface ClutchDao {
    @Query("SELECT * FROM clutches ORDER BY pairId, clutchNumber") LiveData<List<Clutch>> observeAll();
    @Query("SELECT * FROM clutches ORDER BY pairId, clutchNumber") List<Clutch> all();
    @Query("SELECT * FROM clutches WHERE pairId = :pairId ORDER BY clutchNumber") List<Clutch> forPair(long pairId);
    @Insert long insert(Clutch clutch);
    @Insert void insertAll(List<Clutch> clutches);
    @Update void update(Clutch clutch);
    @Delete void delete(Clutch clutch);
    @Query("DELETE FROM clutches") void deleteAll();
}
