package il.sidewalks.reporter.core

import il.sidewalks.reporter.ui.HebrewPresentation
import org.junit.Assert.*
import org.junit.Test

class HebrewPresentationTest {
    @Test fun formatKeepsEvidenceOffsetAndDoesNotGuessTimezone() {
        assertEquals("01/01/2026 12:00 (UTC+00:00)", HebrewPresentation.evidenceTime("2026-01-01T12:00:00Z"))
        assertEquals("01/01/2026 14:30 (UTC+02:00)", HebrewPresentation.evidenceTime("2026-01-01T14:30:00+02:00"))
        assertEquals("חסר", HebrewPresentation.evidenceTime(null))
        assertEquals("unknown", HebrewPresentation.evidenceTime("unknown"))
    }
    @Test fun categoryIsHebrewWithoutChangingTransportCode() {
        assertEquals("חניה על המדרכה", HebrewPresentation.category("sidewalk_parking"))
        assertEquals("קטגוריה לא מוכרת", HebrewPresentation.category("unknown"))
    }
}
