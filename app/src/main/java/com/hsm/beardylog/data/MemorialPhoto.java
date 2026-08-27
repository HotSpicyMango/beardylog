package com.hsm.beardylog.data;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

@Entity(tableName = "memorial_photos", foreignKeys = @ForeignKey(entity = Reptile.class, parentColumns = "id", childColumns = "reptileId", onDelete = ForeignKey.CASCADE), indices = @Index("reptileId"))
public class MemorialPhoto {
    @PrimaryKey(autoGenerate = true) public long id;
    public long reptileId;
    @NonNull public String photoUri;
    public long addedAt;

    public MemorialPhoto(long id, long reptileId, @NonNull String photoUri, long addedAt) {
        this.id = id;
        this.reptileId = reptileId;
        this.photoUri = photoUri;
        this.addedAt = addedAt;
    }
}
