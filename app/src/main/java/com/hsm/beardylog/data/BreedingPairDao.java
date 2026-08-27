package com.hsm.beardylog.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

@Dao
public interface BreedingPairDao {
    @Query("SELECT * FROM breeding_pairs ORDER BY sortOrder, id") LiveData<List<BreedingPair>> observeAll();
    @Query("SELECT * FROM breeding_pairs ORDER BY sortOrder, id") List<BreedingPair> all();
    @Query("SELECT * FROM breeding_pairs WHERE id = :id") BreedingPair byId(long id);
    @Insert long insert(BreedingPair pair);
    @Insert void insertAll(List<BreedingPair> pairs);
    @Update void update(BreedingPair pair);
    @Delete void delete(BreedingPair pair);
    @Query("DELETE FROM breeding_pairs") void deleteAll();
}
