package com.hsm.beardylog.data;

import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** 브리딩 짝(부/모) 한 쌍. 각 개체 id는 프로필이 이후 완전 삭제되어도 이력이 남도록
 *  이름을 스냅샷으로 함께 저장하고 reptiles에 FK를 걸지 않는다 (BreedingRecord와 동일한 정책). */
@Entity(tableName = "breeding_pairs")
public class BreedingPair {
    @PrimaryKey(autoGenerate = true) public long id;
    @Nullable public Long maleReptileId;
    public String maleName = "";
    @Nullable public Long femaleReptileId;
    public String femaleName = "";
    public long matingDate;
    public int sortOrder;
    public long createdAt;
}
