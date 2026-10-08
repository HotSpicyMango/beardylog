package com.hsm.beardylog.data;

import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** 브리딩 짝 한 쌍의 산란 회차(1차~5차) 기록. */
@Entity(
        tableName = "clutches",
        foreignKeys = @ForeignKey(entity = BreedingPair.class, parentColumns = "id", childColumns = "pairId", onDelete = ForeignKey.CASCADE),
        indices = @Index("pairId")
)
public class Clutch {
    @PrimaryKey(autoGenerate = true) public long id;
    public long pairId;
    public int clutchNumber;
    public long layingDate;
    public int infertileEggCount;
    public int fertileEggCount;
    public int lostEggCount;
    @Nullable public Double incubatorTemp;
    /** 디데이를 종료한 날(epochDay). null이면 아직 진행 중이라 오늘까지 센다. 부화 후 더 셀 필요가 없을 때 사용자가 종료한다. */
    @Nullable public Long ddayEndDate;
    public long createdAt;
}
