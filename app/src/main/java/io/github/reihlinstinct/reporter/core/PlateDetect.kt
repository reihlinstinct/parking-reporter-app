package io.github.reihlinstinct.reporter.core

/**
 * Reads the plate type from what the user typed, so the review screen needs no type picker.
 *
 * Only the markers the app already uses are read, and nothing about digit counts or colours is claimed beyond them:
 * - "CD" or "CC" (any case) before the digits: diplomatic or consular.
 * - A hyphen between the digits, or a trailing mem: police (spec 119/2016: digits, hyphen and the letter mem).
 *   A 7 or 8 digit number grouped exactly like the app's own regular display (12-345-67, 123-45-678) stays regular.
 * - Otherwise digits only: regular.
 * Anything with another letter is not allowed in the field at all ([allowed]). A mix of markers (CD with mem) or a
 * stray partial marker gives an empty plate, so it is never ready.
 */
object PlateDetect {
    data class Result(val plate: String, val plateClass: PlateClass)

    private const val MEM = '\u05DE'
    private val bidi = Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069]")
    private val prefix = Regex("^(?i:(cd|cc))[\\s-]*")

    private fun isDigit(c: Char) = Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER.toInt() && Character.digit(c, 10) in 0..9
    private fun ascii(c: Char): Char = ('0'.code + Character.digit(c, 10)).toChar()

    /** Characters the field accepts: digits, space, hyphen, bidi marks, the letters of CD and CC, and the mem. */
    fun allowed(input: String): Boolean = input.all {
        isDigit(it) || it.isWhitespace() || it == '-' || it == MEM || it.lowercaseChar() == 'c' || it.lowercaseChar() == 'd' ||
            bidi.matches(it.toString())
    }

    fun detect(input: String): Result {
        val none = Result("", PlateClass.REGULAR)
        if (!allowed(input)) return none
        var text = bidi.replace(input, "").trim()
        val marker = prefix.find(text)
        val cls: PlateClass?
        if (marker != null) {
            cls = if (marker.groupValues[1].equals("cd", ignoreCase = true)) PlateClass.DIPLOMATIC_CD else PlateClass.CONSULAR_CC
            text = text.substring(marker.range.last + 1)
        } else cls = null
        var mem = false
        if (text.endsWith(MEM)) { mem = true; text = text.dropLast(1).trimEnd(' ', '-', '\t') }
        if (!text.all { isDigit(it) || it.isWhitespace() || it == '-' }) return none
        val body = text.filter { !it.isWhitespace() }
        val digits = body.filter(::isDigit).map(::ascii).joinToString("")
        if (cls != null) return if (mem) none else Result(digits.take(12), cls)
        val groups = body.split('-')
        val hyphen = groups.size > 1
        val regularGrouping = digits.length in 7..8 && groups.map { it.length } == (if (digits.length == 7) listOf(2, 3, 2) else listOf(3, 2, 3))
        if (mem || (hyphen && !regularGrouping)) {
            val clean = groups.filter { it.isNotEmpty() }.joinToString("-") { g -> g.map(::ascii).joinToString("") }
            return Result(clean.take(12).trimEnd('-'), PlateClass.POLICE)
        }
        return Result(digits, PlateClass.REGULAR)
    }

    /**
     * The report text after the plate changed class. Lines are removed only when the OLD plate was complete (ready), so a
     * half-typed plate ("12" on the way to "12-345-67") never deletes lines that merely contain those digits (times,
     * house numbers, dates). Typing within the same class never touches the text.
     */
    fun textAfterChange(text: String, oldPlate: String, oldClass: PlateClass, newClass: PlateClass): String =
        if (newClass != oldClass && PlateClasses.ready(oldPlate, oldClass)) PlateClasses.withoutPlateLines(text, oldPlate, oldClass) else text
}
