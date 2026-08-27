package com.hsm.beardylog.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

@Dao
public interface ReptileDao {
    @Query("SELECT * FROM reptiles ORDER BY name COLLATE NOCASE") LiveData<List<Reptile>> observeAll();
    @Query("SELECT * FROM reptiles WHERE id = :id") LiveData<Reptile> observeById(long id);
    @Query("SELECT * FROM reptiles WHERE id = :id") Reptile byId(long id);
    @Query("SELECT * FROM reptiles ORDER BY id LIMIT 1") Reptile first();
    @Query("SELECT * FROM reptiles ORDER BY id") List<Reptile> all();
    @Insert long insert(Reptile reptile);
    @Insert void insertAll(List<Reptile> reptiles);
    @Update void update(Reptile reptile);
    @Delete void delete(Reptile reptile);
    @Query("DELETE FROM reptiles WHERE id = :id") int deleteById(long id);
    @Query("DELETE FROM reptiles") void deleteAll();
}
