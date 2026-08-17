package com.hsm.beardylog.data;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "care_logs", foreignKeys = @ForeignKey(entity = CareSchedule.class, parentColumns = "id", childColumns = "scheduleId", onDelete = ForeignKey.CASCADE), indices = @Index("scheduleId"))
public class CareLog {
    @PrimaryKey(autoGenerate = true) public long id;
    public long scheduleId;
    public long completedDate;
    public String status;
}
