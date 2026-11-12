package io.github.reihlinstinct.reporter.recognition

/**
 * OCR numbers are suggestions only. Never substitute O/0 or join unrelated fragments.
 * A run next to a standalone Hebrew mem or tsadi (police or military style, "\u05DE" / "\u05E6", also their final
 * forms) is not a regular plate and is not offered (#399). "Standalone" means the letter is not part of a longer
 * Hebrew word, so "\u05E2\u05DD 1234567" or "1234567 \u05DE\u05E1\u05D5\u05D2" still offer the number. The letter may touch the
 * run or be separated from it by one space or hyphen (not a line break).
 */
object PlateCandidates {
    private const val CLASS_LETTERS = "\u05DE\u05DD\u05E6\u05E5"
    private val shapes = Regex(
        "(?<![A-Za-z0-9])" +
            "(?:[0-9]{7,8}|[0-9]{2}-[0-9]{3}-[0-9]{2}|[0-9]{3}-[0-9]{2}-[0-9]{3})" +
            "(?![A-Za-z0-9])")
    private fun hebrewLetter(c: Char) = c in '\u05D0'..'\u05EA'
    private fun classLetter(c: Char) = CLASS_LETTERS.indexOf(c) >= 0
    private fun standaloneBefore(text: String, start: Int): Boolean {
        var i = start - 1
        if (i >= 0 && (text[i] == ' ' || text[i] == '-')) i--
        return i >= 0 && classLetter(text[i]) && (i == 0 || !hebrewLetter(text[i - 1]))
    }
    private fun standaloneAfter(text: String, end: Int): Boolean {
        var i = end
        if (i < text.length && (text[i] == ' ' || text[i] == '-')) i++
        return i < text.length && classLetter(text[i]) && (i + 1 >= text.length || !hebrewLetter(text[i + 1]))
    }
    fun fromText(text: String): List<String> {
        val bounded = text.take(64_000)
        return shapes.findAll(bounded)
            .filter { !standaloneBefore(bounded, it.range.first) && !standaloneAfter(bounded, it.range.last + 1) }
            .map { it.value.replace("-", "") }.distinct().take(10).toList()
    }
}
