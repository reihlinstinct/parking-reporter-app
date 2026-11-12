package io.github.reihlinstinct.reporter.core

/** Stable-code additive preferences, never label-based inference. */
object PlatePolicy {
    fun required(code: String?, topics: List<MunicipalSubject>, enables: Set<String>): Boolean {
        if (code == null) return false
        val rows = topics.filter { it.code == code }
        if (rows.isEmpty()) return false // removed codes/overrides are dormant
        val metadata = rows.mapNotNull { it.plateRequired }.distinct()
        require(metadata.size <= 1)
        return code in enables || (metadata.singleOrNull() ?: (code in setOf("1144", "1145")))
    }
    fun apply(draft: ReviewDraft, topics: List<MunicipalSubject>, enables: Set<String>): ReviewDraft {
        val required = required(TopicIdentity.code(draft.subject), topics, enables)
        return draft.edit(plateRequirement = required)
    }
    private fun plateIn(draft: ReviewDraft): Boolean {
        val shown = PlateClasses.text(draft.plate, draft.plateClass)
        return if (draft.plateClass == PlateClass.REGULAR) Regex("(?<![0-9])" + shown + "(?![0-9])").containsMatchIn(draft.exactHebrewText)
        else Regex("(?<![0-9A-Za-z])" + Regex.escape(shown) + "(?![0-9])").containsMatchIn(draft.exactHebrewText)
    }
    /** Review-visible check. No transport silently appends a plate. */
    fun descriptionContainsPlate(draft: ReviewDraft): Boolean = !draft.needsPlate() ||
        PlateClasses.ready(draft.plate, draft.plateClass) && plateIn(draft)
}
