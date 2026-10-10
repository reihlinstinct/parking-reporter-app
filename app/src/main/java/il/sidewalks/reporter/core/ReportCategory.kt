package il.sidewalks.reporter.core

/** Explicit local categories, not a classifier or a municipal taxonomy. */
enum class ReportCategory(val code: String, val hebrew: String, val needsPlate: Boolean) {
    SIDEWALK_PARKING("sidewalk_parking", "חניה על המדרכה", true),
    BROKEN_SIGN("broken_sign", "תמרור שבור", false),
    FADED_CROSSWALK("faded_crosswalk", "מעבר חציה דהוי", false);
    companion object { fun from(code: String): ReportCategory? = entries.firstOrNull { it.code == code } }
}
