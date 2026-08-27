package com.hsm.beardylog

import com.hsm.beardylog.data.Reptile
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSorterTest {
    @Test
    fun nameSortOrdersTrailingNumbersNaturally() {
        val names = ProfileSorter.sortedProfiles(
            listOf(reptile("레오10"), reptile("레오2"), reptile("레오1")),
            ProfileSorter.SORT_BY_NAME
        ).map { it.name }

        assertEquals(listOf("레오1", "레오2", "레오10"), names)
    }

    @Test
    fun nameSortHandlesNumbersLargerThanInt() {
        val names = ProfileSorter.sortedProfiles(
            listOf(reptile("레오999999999999999999999999"), reptile("레오2147483648"), reptile("레오2")),
            ProfileSorter.SORT_BY_NAME
        ).map { it.name }

        assertEquals(listOf("레오2", "레오2147483648", "레오999999999999999999999999"), names)
    }

    @Test
    fun dateSortFallsBackToMatchingReferenceDateType() {
        val reptiles = listOf(
            reptile("입양", referenceDate = 20L, referenceDateType = "입양일"),
            reptile("해칭", referenceDate = 10L, referenceDateType = "해칭일"),
            reptile("직접", hatchingDate = 5L)
        )

        assertEquals(
            listOf("직접", "해칭", "입양"),
            ProfileSorter.sortedProfiles(reptiles, ProfileSorter.SORT_BY_HATCHING_DATE).map { it.name }
        )
        assertEquals(
            listOf("입양", "해칭", "직접"),
            ProfileSorter.sortedProfiles(reptiles, ProfileSorter.SORT_BY_ADOPTION_DATE).map { it.name }
        )
    }

    private fun reptile(
        name: String,
        referenceDate: Long = 0L,
        referenceDateType: String = "기준일",
        hatchingDate: Long? = null,
        adoptionDate: Long? = null
    ): Reptile =
        Reptile(0L, name, "", "", "", referenceDate, referenceDateType, null, 0L).apply {
            this.hatchingDate = hatchingDate
            this.adoptionDate = adoptionDate
        }
}
