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
    private val special = Regex("(?=.{1,12}$)[0-9]+(-[0-9]+)*")
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

    /**
     * True when [report] mentions the plate as a whole token. The left side must not continue the plate: a digit or
     * Latin letter before it never does, and for police a hyphen or dash (ASCII, Hebrew maqaf, U+2010 to U+2015, minus
     * sign U+2212) before it does not either ("1-12-34" is not "12-34"). For CD and CC a digit after a run of spaces,
     * tabs, no-break or figure spaces, maqaf, minus sign or dashes means a longer number.
     * Police ends with a Hebrew letter, so it must not be the first letter of a longer word.
     */
    fun mentions(report: String, plate: String, plateClass: PlateClass): Boolean {
        if (plate.isBlank()) return false
        val shown = text(plate, plateClass)
        val before = when (plateClass) {
            PlateClass.REGULAR -> "(?<![0-9])"
            PlateClass.POLICE -> "(?<![0-9A-Za-z\\-\\u05BE\\u2010-\\u2015\\u2212])"
            else -> "(?<![0-9A-Za-z])"
        }
        val after = when (plateClass) {
            PlateClass.POLICE -> "(?![0-9\\u05D0-\\u05EA])"
            PlateClass.REGULAR -> "(?![0-9])"
            // CD and CC are shown with spaces, so a digit after a run of spaces or dashes is a longer number.
            else -> "(?![0-9]|[ \\t\\u00A0\\u2007\\u202F\\-\\u05BE\\u2010-\\u2015\\u2212]+[0-9])"
        }
        return Regex(before + Regex.escape(shown) + after).containsMatchIn(report)
    }

    /**
     * Removes the report-text lines that mention the old plate. Used when the user changes the plate type, so the text
     * never keeps a plate line of the previous type next to the new one. A line that only contains the number inside a
     * longer number or token is kept.
     */
    fun withoutPlateLines(text: String, plate: String, plateClass: PlateClass): String {
        if (plate.isBlank()) return text
        return text.lines().filterNot { mentions(it, plate, plateClass) }.joinToString("\n").trimStart('\n')
    }
}
