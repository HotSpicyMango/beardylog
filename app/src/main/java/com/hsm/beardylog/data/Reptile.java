package com.hsm.beardylog.data;

import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "reptiles")
public class Reptile {
    @PrimaryKey(autoGenerate = true) public long id;
    public String name;
    public String species;
    public String morph;
    public String gender;
    public long referenceDate;
    public String referenceDateType;
    @Nullable public String photoUri;
    public long createdAt;
    @Nullable public Long hatchingDate;
    @Nullable public Long adoptionDate;

    public Reptile(long id, String name, String species, String morph, String gender, long referenceDate, String referenceDateType, @Nullable String photoUri, long createdAt) {
        this.id = id; this.name = name; this.species = species; this.morph = morph; this.gender = gender;
        this.referenceDate = referenceDate; this.referenceDateType = referenceDateType; this.photoUri = photoUri; this.createdAt = createdAt;
    }
}
