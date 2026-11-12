package io.github.reihlinstinct.reporter.recognition

/**
 * OCR numbers are suggestions only. Never substitute O/0 or join unrelated fragments.
 * A run next to a Hebrew mem or tsadi (police or military style, "\u05DE" / "\u05E6", also their final
 * forms), directly or across one space or hyphen, is not a regular plate and is not offered (#399).
 */
object PlateCandidates {
    private const val CLASS_LETTERS = "\u05DE\u05DD\u05E6\u05E5"
    private val shapes = Regex(
        "(?<![A-Za-z0-9$CLASS_LETTERS])(?<![$CLASS_LETTERS][\\s\\-])" +
            "(?:[0-9]{7,8}|[0-9]{2}-[0-9]{3}-[0-9]{2}|[0-9]{3}-[0-9]{2}-[0-9]{3})" +
            "(?![A-Za-z0-9$CLASS_LETTERS])(?![\\s\\-][$CLASS_LETTERS])")
    fun fromText(text: String): List<String> = shapes
        .findAll(text.take(64_000)).map { it.value.replace("-", "") }.distinct().take(10).toList()
}
