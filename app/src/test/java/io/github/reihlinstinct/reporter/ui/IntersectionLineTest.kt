package io.github.reihlinstinct.reporter.ui

import io.github.reihlinstinct.reporter.i18n.I18nTestFiles
import io.github.reihlinstinct.reporter.i18n.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class IntersectionLineTest {
    private val catalog = I18nTestFiles.realCatalog()
    private val approved = "המקום בצומת הרחובות רחוב אלף ו-רחוב בית. הכתובת שצוינה היא הקרובה לצומת."

    @Test fun lineIsTheApprovedHebrewInEveryLanguageWithNoBidiMarks() {
        for (code in I18nTestFiles.languageCodes()) {
            val line = intersectionDescriptionLine(Strings(catalog, code), "רחוב אלף", "רחוב בית")
            assertEquals(code, approved, line)
            assertFalse(code, line.any { it in '\u2066'..'\u2069' })
        }
    }

    @Test fun lineIsAddedOnceAfterTheExistingText() {
        assertEquals(approved, withIntersectionLine("", approved))
        assertEquals("טקסט קיים\n$approved", withIntersectionLine("טקסט קיים\n", approved))
        val once = withIntersectionLine("טקסט קיים", approved)
        assertEquals(once, withIntersectionLine(once, approved))
    }

    @Test fun anOldCrossingLineIsRemovedAndReplacedNeverDoubled() {
        val he = Strings(catalog, I18nTestFiles.defaultCode())
        val first = intersectionDescriptionLine(he, "רחוב אלף", "רחוב בית")
        val second = intersectionDescriptionLine(he, "רחוב גימל", "רחוב דלת")
        val text = "טקסט קיים\n$first"
        assertEquals("טקסט קיים", withoutIntersectionLine(he, text))
        assertEquals("טקסט קיים\n$second", withIntersectionLine(withoutIntersectionLine(he, text), second))
        assertEquals("טקסט קיים", withoutIntersectionLine(he, "טקסט קיים"))
        assertEquals("", withoutIntersectionLine(he, first))
        assertEquals("שורה אחת\nשורה שתיים", withoutIntersectionLine(he, "שורה אחת\n$first\nשורה שתיים"))
    }
}
