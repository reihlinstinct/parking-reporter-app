package il.sidewalks.reporter.municipal

import il.sidewalks.reporter.security.ReporterSettings

/** Explicit owner-approved compatibility mode, not a verified municipal passport contract. */
object PassportSubmission {
    // TODO: remove once a verified municipal passport contact type is supported.
    const val COMPATIBILITY_ID = "123456782"
    // User-chosen: user states the 057 prefix is unassigned; unverified by us.
    // TODO remove with native international support.
    const val INTERNATIONAL_PHONE_PLACEHOLDER = "0571234567"
    fun isIsraeliPhone(phone: String): Boolean = try {
        MunicipalPayloads.normalizePhone(phone).matches(Regex("05[0-9]{8}"))
    } catch (_: Exception) { false }
    private val disclosurePrefixes = listOf("הדיווח נשלח על ידי בעל/ת דרכון שמספרו ", "מספר הטלפון של המדווח/ת ליצירת קשר הוא ")
    private fun isDisclosureLine(line: String): Boolean = disclosurePrefixes.any { line.startsWith(it) }
    fun reconcileDisclosure(text: String, settings: ReporterSettings): String {
        require(validProfile(settings))
        val base = text.lines().filterNot(::isDisclosureLine).joinToString("\n").trimEnd()
        val current = disclosure(settings)
        return if (current.isEmpty()) base else base + "\n\n" + current
    }
    fun validProfile(settings: ReporterSettings): Boolean =
        (!settings.passportMode || validPassport(settings.passportNumber)) &&
        try { MunicipalPayloads.normalizePhone(settings.phone); true } catch (_: Exception) { false }
    fun phoneForPayload(settings: ReporterSettings): String = if (isIsraeliPhone(settings.phone))
        MunicipalPayloads.normalizePhone(settings.phone) else INTERNATIONAL_PHONE_PLACEHOLDER
    fun validPassport(number: String): Boolean = number.trim().matches(Regex("[\\p{L}\\p{N} -]{3,40}")) && number.trim().last().isLetterOrDigit()
    fun disclosure(settings: ReporterSettings): String {
        require(validProfile(settings))
        val suffix = settings.passportNumber.trim().takeLast(3)
        val passportLine = if (settings.passportMode) "הדיווח נשלח על ידי בעל/ת דרכון שמספרו xxxxx" + suffix +
            "; המדווח/ת אינו/ה מחזיק/ה בתעודת זהות ישראלית. בשדה תעודת הזהות בממשק נשלח מספר מציין מקום " + COMPATIBILITY_ID + ", ולא מספר תעודת זהות של המדווח/ת." else ""
        val phoneLine = if (!isIsraeliPhone(settings.phone)) "מספר הטלפון של המדווח/ת ליצירת קשר הוא " + MunicipalPayloads.normalizePhone(settings.phone) +
            ". בשדה הטלפון בממשק נשלח מספר מציין מקום " + INTERNATIONAL_PHONE_PLACEHOLDER + ", ולא מספר הטלפון של המדווח/ת." else ""
        return listOf(passportLine, phoneLine).filter { it.isNotEmpty() }.joinToString("\n")
    }
    fun hasReviewedDisclosure(text: String, settings: ReporterSettings): Boolean =
        validProfile(settings) && run {
            val expected = disclosure(settings).lines().filter { it.isNotEmpty() }
            val actual = text.lines().filter(::isDisclosureLine)
            actual.size == expected.size && actual.toSet() == expected.toSet()
        }
}
