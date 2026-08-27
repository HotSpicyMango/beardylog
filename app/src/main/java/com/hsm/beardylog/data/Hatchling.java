package com.hsm.beardylog.data;

import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** 특정 클러치(산란 회차)에서 태어난 해츨링 관리 기록. */
@Entity(
        tableName = "hatchlings",
        foreignKeys = @ForeignKey(entity = Clutch.class, parentColumns = "id", childColumns = "clutchId", onDelete = ForeignKey.CASCADE),
        indices = @Index("clutchId")
)
public class Hatchling {
    @PrimaryKey(autoGenerate = true) public long id;
    public long clutchId;
    public int normalCount;
    public int deathCount;
    public int disabledCount;
    @Nullable public String disabledReason;
    public int midDropCount;
    public long createdAt;
}
