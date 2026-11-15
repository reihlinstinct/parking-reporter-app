package io.github.reihlinstinct.reporter.core

/**
 * Issues #109 and #105. A suggestion is a hint, never a decision: it carries a municipal subject code and an optional
 * confidence, and nothing here selects, approves or sends anything. The user's own choice always wins.
 */
data class CategorySuggestion(val code: String, val confidence: Float? = null) {
    init { require(confidence == null || confidence in 0f..1f) { "confidence must be within 0..1" } }
}

/** What a suggester may look at. Deliberately no photo, plate or location: the first real model decides what it needs. */
data class SuggestionInput(val topics: List<MunicipalSubject>, val recentCodes: List<String> = emptyList())

/** Pluggable on-device suggester. The real model is chosen later by the feasibility benchmark. */
fun interface CategorySuggester {
    fun suggest(input: SuggestionInput): List<CategorySuggestion>
}

/** The stub: the common local categories as low-confidence hints, in a fixed order, only when the catalog has them. */
class StubCategorySuggester : CategorySuggester {
    override fun suggest(input: SuggestionInput): List<CategorySuggestion> {
        val available = input.topics.map { it.code }.toSet()
        return COMMON.filter { it in available }.map { CategorySuggestion(it, STUB_CONFIDENCE) }
    }
    companion object {
        const val STUB_CONFIDENCE = 0.2f
        val COMMON: List<String> = listOf(
            ReportCategory.SIDEWALK_PARKING, ReportCategory.BROKEN_SIGN, ReportCategory.FADED_CROSSWALK,
        ).map { it.municipalSubjectCode }
    }
}

/** Turns raw suggester output into what a screen may show. Pure and deterministic. */
object CategorySuggestions {
    /**
     * Drops codes that are not in the catalog and duplicates, orders by confidence (unknown last, ties keep the
     * suggester's order), and never returns the code the user already chose.
     */
    fun visible(raw: List<CategorySuggestion>, topics: List<MunicipalSubject>, selectedCode: String?): List<CategorySuggestion> {
        val known = topics.map { it.code }.toSet()
        val indexed = raw.withIndex().filter { it.value.code in known && it.value.code != selectedCode }
        return indexed.sortedWith(compareByDescending<IndexedValue<CategorySuggestion>> { it.value.confidence ?: -1f }.thenBy { it.index })
            .map { it.value }.distinctBy { it.code }
    }

    /** A suggester that throws must not break the review screen. It yields no hints. */
    fun safely(suggester: CategorySuggester, input: SuggestionInput): List<CategorySuggestion> =
        try { suggester.suggest(input) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }

    /** Confidence as a whole percent for display, or null when the suggester gave none. */
    fun percent(suggestion: CategorySuggestion): Int? = suggestion.confidence?.let { Math.round(it * 100f) }
}
