package com.hsm.beardylog.data;

import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

@Entity(tableName = "care_schedules", foreignKeys = @ForeignKey(entity = Reptile.class, parentColumns = "id", childColumns = "reptileId", onDelete = ForeignKey.CASCADE), indices = @Index("reptileId"))
public class CareSchedule {
    @PrimaryKey(autoGenerate = true) public long id;
    public long reptileId;
    public long scheduledDate;
    public String careType;
    public String memo;
    @Nullable public Integer repeatDayOfWeek;

    /** 식단으로 치는 관리 종류. 홈 주간표와 식단 알림이 같은 기준을 쓴다. (스피너 목록은 res/values/arrays.xml의 care_types) */
    public static final List<String> DIET_TYPES = Arrays.asList("충식", "채식", "사료", "금식");

    public boolean isDiet() { return DIET_TYPES.contains(careType); }

    /** 요일 반복이면 그 요일마다, 아니면 지정한 날짜 하루만 해당한다. */
    public boolean isScheduledOn(LocalDate date) {
        return repeatDayOfWeek != null
                ? repeatDayOfWeek == date.getDayOfWeek().getValue()
                : scheduledDate == date.toEpochDay();
    }
}
