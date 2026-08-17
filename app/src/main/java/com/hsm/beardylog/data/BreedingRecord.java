package com.hsm.beardylog.data;

import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "breeding_records")
public class BreedingRecord {
    @PrimaryKey(autoGenerate = true) public long id;
    @Nullable public Long maleReptileId;
    @Nullable public String maleName;
    @Nullable public Long femaleReptileId;
    @Nullable public String femaleName;
    public String layingDates = "";
    public String hatchingDates = "";
    public String memo = "";
}
