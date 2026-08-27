package com.hsm.beardylog

import com.hsm.beardylog.data.Reptile

internal class MemorialSectionState {
    var detailId: Long? = null

    fun deceasedProfiles(profiles: List<Reptile>): List<Reptile> =
        profiles.filter { it.deathDate != null }.sortedByDescending { it.deathDate }

    fun closeDetail(): Boolean {
        if (detailId == null) return false
        detailId = null
        return true
    }

    fun leave() {
        detailId = null
    }
}
