package io.github.reihlinstinct.reporter.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import io.github.reihlinstinct.reporter.core.ReviewDraft

/** Formatting is display-only. Draft, digest, OCR and transport keep ASCII digits. */
object PlateEntry : VisualTransformation {
    fun digits(value: String) = buildString {
        value.forEach { c -> if (Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER.toInt()) {
            val digit = Character.digit(c, 10)
            if (digit in 0..9) append(('0'.code + digit).toChar())
        } }
    }
    /**
     * Manual entry for a regular plate. Digits, hyphens, spaces and bidi marks are accepted. Any
     * letter or other symbol rejects the whole edit and keeps [previous], so a pasted special
     * plate ("CD 11 222 33", "11-22-33 \u05DE") is never stripped to digits and kept as a regular one.
     */
    fun accept(previous: String, input: String): String =
        if (input.all { it.isWhitespace() || it == '-' || it == '\u200E' || it == '\u200F' ||
                Character.getType(it) == Character.DECIMAL_DIGIT_NUMBER.toInt() }) digits(input)
        else previous
    private fun breaks(length: Int) = if (length > 7) listOf(3, 5) else listOf(2, 5)
    fun format(value: String): String = buildString {
        value.forEachIndexed { index, digit ->
            if (index in breaks(value.length)) append('-')
            append(digit)
        }
    }
    override fun filter(text: AnnotatedString): TransformedText {
        val split = breaks(text.length).filter { it < text.length }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = offset + split.count { it <= offset }
            override fun transformedToOriginal(offset: Int): Int {
                var digits = 0
                format(text.text).take(offset).forEach { if (it != '-') digits++ }
                return digits.coerceIn(0, text.length)
            }
        }
        return TransformedText(AnnotatedString(format(text.text)), mapping)
    }
    /** Only one unambiguous valid candidate, never overwrite typing during async OCR. */
    fun prefill(draft: ReviewDraft, candidates: List<String>, before: String): ReviewDraft {
        val valid = candidates.filter { it.matches(Regex("[0-9]{7,8}")) }.distinct()
        return if (before.isBlank() && draft.plate == before && valid.size == 1 &&
            draft.needsPlate())
            draft.edit(plate = valid.single()) else draft
    }
}
