package com.hsm.beardylog.data;

import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "care_schedules", foreignKeys = @ForeignKey(entity = Reptile.class, parentColumns = "id", childColumns = "reptileId", onDelete = ForeignKey.CASCADE), indices = @Index("reptileId"))
public class CareSchedule {
    @PrimaryKey(autoGenerate = true) public long id;
    public long reptileId;
    public long scheduledDate;
    public String careType;
    public String memo;
    @Nullable public Integer repeatDayOfWeek;
}
