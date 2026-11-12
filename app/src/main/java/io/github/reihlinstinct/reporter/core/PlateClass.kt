package io.github.reihlinstinct.reporter.core

/**
 * Plate class chosen by the user in Review (issue #354). Nothing is detected or guessed: only REGULAR is ever
 * derived from the digits. The other classes exist only because the user picked them, and only facts
 * from official sources are coded (docs/PLATE_CLASSES.md): police plates carry digits, a hyphen and the
 * letter mem; diplomatic (CD) and consular (CC) plates have no verified grammar, so no digit-count rule.
 */
enum class PlateClass(val code: String) {
    REGULAR("regular"), POLICE("police"), DIPLOMATIC_CD("cd"), CONSULAR_CC("cc");

    companion object {
        /** A missing code is REGULAR. An unknown code is corrupt data and is rejected, never reinterpreted. */
        fun fromCode(code: String?): PlateClass =
            if (code == null) REGULAR else requireNotNull(entries.firstOrNull { it.code == code }) { "Unknown plate class" }
    }
}

object PlateClasses {
    private val regular = Regex("[0-9]{7,8}")
    private val special = Regex("[0-9][0-9-]{0,11}")
    private const val MEM = "\u05DE"

    /** Regular keeps the 7 or 8 digit rule. Other classes need the typed digits (hyphen allowed for police) and nothing else. */
    fun ready(plate: String, plateClass: PlateClass): Boolean = when (plateClass) {
        PlateClass.REGULAR -> plate.matches(regular)
        PlateClass.POLICE -> plate.matches(special)
        PlateClass.DIPLOMATIC_CD, PlateClass.CONSULAR_CC -> plate.matches(Regex("[0-9]{1,12}"))
    }

    /** The exact string shown to the user and required in the report text. Synthetic style: "11-22-33 mem", "CD 123". */
    fun text(plate: String, plateClass: PlateClass): String = when (plateClass) {
        PlateClass.REGULAR -> plate
        PlateClass.POLICE -> "$plate $MEM"
        PlateClass.DIPLOMATIC_CD -> "CD $plate"
        PlateClass.CONSULAR_CC -> "CC $plate"
    }
}
