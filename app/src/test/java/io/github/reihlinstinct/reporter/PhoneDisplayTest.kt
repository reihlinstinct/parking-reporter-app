package io.github.reihlinstinct.reporter

import androidx.compose.ui.text.AnnotatedString
import io.github.reihlinstinct.reporter.ui.PhoneDisplayTransformation
import io.github.reihlinstinct.reporter.ui.ReporterInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #235: pins the display mapping and the non-Israeli display fallback. The platform formatter is injected, so these never depend on the Android version. */
class PhoneDisplayTest {
    private fun shown(original: String) = PhoneDisplayTransformation.filter(AnnotatedString(original))

    @Test fun plus972OriginalIsGroupedAndCursorMapsExactly() {
        val t = shown("+972501234567")
        assertEquals("+972-50-123-4567", t.text.text)
        assertEquals(0, t.offsetMapping.originalToTransformed(0))
        assertEquals(1, t.offsetMapping.originalToTransformed(1))
        assertEquals(4, t.offsetMapping.originalToTransformed(4))
        assertEquals(6, t.offsetMapping.originalToTransformed(5))
        assertEquals(16, t.offsetMapping.originalToTransformed(13))
        assertEquals(13, t.offsetMapping.transformedToOriginal(16))
        assertEquals(4, t.offsetMapping.transformedToOriginal(5))
    }

    @Test fun local05OriginalShowsTheSameGroupingAndMappingStaysInBounds() {
        val original = "0501234567"
        val t = shown(original)
        assertEquals("+972-50-123-4567", t.text.text)
        var last = 0
        for (offset in 0..original.length) {
            val shownOffset = t.offsetMapping.originalToTransformed(offset)
            assertTrue("offset $offset", shownOffset in 0..t.text.length)
            assertTrue("monotonic at $offset", shownOffset >= last)
            last = shownOffset
        }
        for (offset in 0..t.text.length) {
            assertTrue("back $offset", t.offsetMapping.transformedToOriginal(offset) in 0..original.length)
        }
    }

    @Test fun nonIsraeliNumbersUseTheInjectedPlatformFormatterWithDashesAndFallBackToCanonical() {
        assertEquals("+1-415-555-2671", ReporterInput.formattedPhone("+1 (415) 555-2671") { "+1 415-555-2671" })
        assertEquals("+14155552671", ReporterInput.formattedPhone("+1 (415) 555-2671") { null })
    }

    @Test fun partialAndLandlineInputsCanonicalizeWithoutGuessing() {
        assertEquals("05012", ReporterInput.canonicalPhone("050-12"))
        assertEquals("021234567", ReporterInput.canonicalPhone("02-123-4567"))
        assertEquals("+97221234567", ReporterInput.canonicalPhone("+972-2-123-4567"))
        assertEquals("", ReporterInput.canonicalPhone("abc"))
    }

    @Test fun combiningMarksInNamesSurvive() {
        val decomposed = "Jose\u0301 \u05E9\u05B8\u05C1\u05DC\u05D5\u05B9\u05DD"
        assertEquals(decomposed, ReporterInput.nameInput(decomposed))
        assertEquals("Jose\u0301", ReporterInput.nameInput("Jose\u03011"))
    }
}
