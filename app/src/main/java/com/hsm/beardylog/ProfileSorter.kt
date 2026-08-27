package com.hsm.beardylog

import com.hsm.beardylog.data.Reptile
import java.text.Collator
import java.util.Locale

object ProfileSorter {
    const val SORT_BY_NAME = 0
    const val SORT_BY_HATCHING_DATE = 1
    const val SORT_BY_ADOPTION_DATE = 2

    private val trailingNumberPattern = Regex("^(.*?)(\\d+)$")

    fun sortedProfiles(reptiles: List<Reptile>, sortMode: Int): List<Reptile> =
        when (sortMode) {
            SORT_BY_HATCHING_DATE -> reptiles.sortedWith(
                compareBy { it.hatchingDate ?: if (it.referenceDateType == "해칭일") it.referenceDate else Long.MAX_VALUE }
            )
            SORT_BY_ADOPTION_DATE -> reptiles.sortedWith(
                compareBy { it.adoptionDate ?: if (it.referenceDateType == "입양일") it.referenceDate else Long.MAX_VALUE }
            )
            else -> {
                val collator = Collator.getInstance(Locale.KOREAN).apply { strength = Collator.PRIMARY }
                reptiles.sortedWith { first, second -> naturalNameCompare(first.name, second.name, collator) }
            }
        }

    internal fun naturalNameCompare(first: String, second: String, collator: Collator): Int {
        val firstName = first.trim().lowercase(Locale.KOREAN)
        val secondName = second.trim().lowercase(Locale.KOREAN)
        val firstMatch = trailingNumberPattern.find(firstName)
        val secondMatch = trailingNumberPattern.find(secondName)
        if (firstMatch != null && secondMatch != null) {
            val prefixCompare = collator.compare(firstMatch.groupValues[1], secondMatch.groupValues[1])
            if (prefixCompare != 0) return prefixCompare
            val numberCompare = firstMatch.groupValues[2].toBigInteger().compareTo(secondMatch.groupValues[2].toBigInteger())
            if (numberCompare != 0) return numberCompare
        }
        return collator.compare(firstName, secondName)
    }
}
