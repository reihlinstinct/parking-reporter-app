package il.sidewalks.reporter.municipal

import il.sidewalks.reporter.security.ReporterSettings

/** Explicit owner-approved compatibility mode, not a verified municipal passport contract. */
object PassportSubmission {
    // TODO: remove once a verified municipal passport contact type is supported.
    const val COMPATIBILITY_ID = "123456782"
    fun validPassport(number: String): Boolean = number.trim().matches(Regex("[\\p{L}\\p{N} -]{3,40}")) && number.trim().last().isLetterOrDigit()
    fun disclosure(settings: ReporterSettings): String {
        require(settings.passportMode && validPassport(settings.passportNumber))
        val suffix = settings.passportNumber.trim().takeLast(3)
        return "הדיווח נשלח על ידי בעל/ת דרכון שמספרו xxxxx" + suffix +
            "; המדווח/ת אינו/ה מחזיק/ה בתעודת זהות ישראלית. בשדה תעודת הזהות בממשק נשלח מספר מציין מקום " + COMPATIBILITY_ID + ", ולא מספר תעודת זהות של המדווח/ת."
    }
    fun hasReviewedDisclosure(text: String, settings: ReporterSettings): Boolean =
        validPassport(settings.passportNumber) && text.lines().contains(disclosure(settings))
}
