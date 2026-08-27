package com.hsm.beardylog.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface MemorialPhotoDao {
    @Query("SELECT * FROM memorial_photos WHERE reptileId = :reptileId ORDER BY addedAt DESC")
    LiveData<List<MemorialPhoto>> observeForReptile(long reptileId);

    @Query("SELECT * FROM memorial_photos WHERE reptileId = :reptileId ORDER BY addedAt DESC")
    List<MemorialPhoto> forReptile(long reptileId);

    @Query("SELECT * FROM memorial_photos ORDER BY id")
    List<MemorialPhoto> all();

    @Insert long insert(MemorialPhoto photo);

    @Insert void insertAll(List<MemorialPhoto> photos);

    @Query("DELETE FROM memorial_photos WHERE id = :id") int deleteById(long id);

    @Query("DELETE FROM memorial_photos") void deleteAll();
}
