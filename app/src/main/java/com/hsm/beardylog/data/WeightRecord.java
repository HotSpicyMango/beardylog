package com.hsm.beardylog.data;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "weight_records", foreignKeys = @ForeignKey(entity = Reptile.class, parentColumns = "id", childColumns = "reptileId", onDelete = ForeignKey.CASCADE), indices = @Index("reptileId"))
public class WeightRecord {
    @PrimaryKey(autoGenerate = true) public long id;
    public long reptileId;
    public long recordedAt;
    public float grams;
    public WeightRecord(long id, long reptileId, long recordedAt, float grams) { this.id = id; this.reptileId = reptileId; this.recordedAt = recordedAt; this.grams = grams; }
}
