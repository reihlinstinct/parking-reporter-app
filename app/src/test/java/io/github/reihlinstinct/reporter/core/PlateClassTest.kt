package io.github.reihlinstinct.reporter.core

import io.github.reihlinstinct.reporter.ledger.DraftCodec
import io.github.reihlinstinct.reporter.ui.PlateEntry
import org.junit.Assert.*
import org.junit.Test

/** Synthetic strings only. The formats are the user-picked classes of docs/PLATE_CLASSES.md, not claims about real plates. */
class PlateClassTest {
    private val mem = "\u05DE"
    private fun draft(plate: String = "", plateClass: PlateClass = PlateClass.REGULAR, text: String = "reviewed") = ReviewDraft(
        Evidence("class-test", "a".repeat(64), "2026-01-01T12:00:00+02:00", 31.0, 35.0),
        plate, "street 1", "municipal:1144", text, emptySet(), plateClass = plateClass)

    @Test fun regularKeepsTheSevenOrEightDigitRule() {
        assertTrue(PlateClasses.ready("1234567", PlateClass.REGULAR))
        assertTrue(PlateClasses.ready("12345678", PlateClass.REGULAR))
        assertFalse(PlateClasses.ready("123456", PlateClass.REGULAR))
        assertFalse(PlateClasses.ready("12-345-67", PlateClass.REGULAR))
    }
    @Test fun otherClassesNeedDigitsOnlyWithNoCountRule() {
        assertTrue(PlateClasses.ready("123456", PlateClass.POLICE))
        assertTrue(PlateClasses.ready("12-34-56", PlateClass.POLICE))
        assertTrue(PlateClasses.ready("1", PlateClass.DIPLOMATIC_CD))
        assertTrue(PlateClasses.ready("123", PlateClass.CONSULAR_CC))
        assertFalse(PlateClasses.ready("", PlateClass.POLICE))
        assertFalse(PlateClasses.ready("-12", PlateClass.POLICE))
        assertFalse(PlateClasses.ready("12-34", PlateClass.DIPLOMATIC_CD))
        assertFalse(PlateClasses.ready("12x", PlateClass.CONSULAR_CC))
    }
    @Test fun shownTextCarriesTheClassMarker() {
        assertEquals("1234567", PlateClasses.text("1234567", PlateClass.REGULAR))
        assertEquals("11-22-33 $mem", PlateClasses.text("11-22-33", PlateClass.POLICE))
        assertEquals("CD 123", PlateClasses.text("123", PlateClass.DIPLOMATIC_CD))
        assertEquals("CC 123", PlateClasses.text("123", PlateClass.CONSULAR_CC))
    }
    @Test fun aSevenDigitSpecialPlateIsNeverTreatedAsRegular() {
        val police = draft("1234567", PlateClass.POLICE, "x 1234567 y")
        assertFalse("the marker must be in the text", PlatePolicy.descriptionContainsPlate(police))
        val withMarker = police.copy(exactHebrewText = "x 1234567 $mem y")
        assertTrue(PlatePolicy.descriptionContainsPlate(withMarker))
        assertFalse(PlateClasses.ready(withMarker.plate, PlateClass.REGULAR) && withMarker.plateClass == PlateClass.REGULAR)
        assertNotEquals(draft("1234567").digest(), police.digest())
    }
    @Test fun descriptionCheckForEachClass() {
        assertTrue(PlatePolicy.descriptionContainsPlate(draft("123", PlateClass.DIPLOMATIC_CD, "a CD 123 b")))
        assertFalse(PlatePolicy.descriptionContainsPlate(draft("123", PlateClass.DIPLOMATIC_CD, "a CC 123 b")))
        assertFalse(PlatePolicy.descriptionContainsPlate(draft("123", PlateClass.DIPLOMATIC_CD, "a CD 1234 b")))
        assertTrue(PlatePolicy.descriptionContainsPlate(draft("1234567", PlateClass.REGULAR, "a 1234567 b")))
    }
    @Test fun readinessUsesTheClass() {
        val ready = draft("123", PlateClass.CONSULAR_CC).copy(confirmedLatitude = 31.0, confirmedLongitude = 35.0)
        assertTrue(ready.isReady())
        assertFalse(ready.copy(plateClass = PlateClass.REGULAR).isReady())
    }
    @Test fun regularDigestIsUnchangedByTheNewField() {
        val regular = draft("1234567")
        assertEquals(regular.digest(), regular.copy(plateClass = PlateClass.REGULAR).digest())
    }
    @Test fun editingToAPlatelessTopicResetsTheClass() {
        val police = draft("123", PlateClass.POLICE)
        assertEquals(PlateClass.REGULAR, police.edit(plateRequirement = false).plateClass)
        assertEquals("", police.edit(plateRequirement = false).plate)
        assertEquals(PlateClass.POLICE, police.edit(plateRequirement = true).plateClass)
    }
    @Test fun ledgerRoundTripKeepsTheClassAndRegularStaysByteCompatible() {
        val police = draft("12-34", PlateClass.POLICE)
        assertEquals(police, DraftCodec.decode(DraftCodec.encode(police)))
        assertFalse(DraftCodec.encode(draft("1234567")).contains("plateClass"))
        assertEquals(PlateClass.REGULAR, DraftCodec.decode(DraftCodec.encode(draft("1234567"))).plateClass)
        assertThrows(IllegalArgumentException::class.java) { PlateClass.fromCode("military") }
    }
    @Test fun policeEntryKeepsHyphensAndRejectsLettersOthersKeepDigits() {
        assertEquals("12-34", PlateEntry.acceptFor(PlateClass.POLICE, "", "12 - 34"))
        assertEquals("12-34", PlateEntry.acceptFor(PlateClass.POLICE, "12-34", "12-34x"))
        assertEquals("1234", PlateEntry.acceptFor(PlateClass.DIPLOMATIC_CD, "", "12-34"))
        assertEquals("123", PlateEntry.acceptFor(PlateClass.REGULAR, "", "1-2-3"))
        assertEquals(12, PlateEntry.acceptFor(PlateClass.POLICE, "", "1234567890123456").length)
    }
    @Test fun ocrPrefillNeverOverridesANonRegularClass() {
        val police = draft("", PlateClass.POLICE)
        assertEquals(police, PlateEntry.prefill(police, listOf("1234567"), ""))
    }
}
