package io.github.reihlinstinct.reporter.ui

import io.github.reihlinstinct.reporter.core.BuiltInTemplates
import io.github.reihlinstinct.reporter.core.DescriptionTemplates
import io.github.reihlinstinct.reporter.core.Evidence
import io.github.reihlinstinct.reporter.core.PermitState
import io.github.reihlinstinct.reporter.core.ReviewDraft
import io.github.reihlinstinct.reporter.core.TemplateSlot
import io.github.reihlinstinct.reporter.registry.DisabilityLookupResult
import io.github.reihlinstinct.reporter.registry.LookupErrorKind
import io.github.reihlinstinct.reporter.registry.PlateNumber
import io.github.reihlinstinct.reporter.registry.VehicleLookupResult
import io.github.reihlinstinct.reporter.registry.VehicleRecord
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

/** Issue #424 slice 2b: synthetic data only. */
class ReviewTemplateFactsTest {
    private val checked = Instant.parse("2026-11-15T05:00:00Z")
    private fun draft(plate: String = "1234567", address: String = "רחוב הדוגמה 12") =
        ReviewDraft(Evidence("t", "a".repeat(64), "2026-11-15T06:27:43+02:00", 31.0, 35.0), plate, address, "municipal:1145", "", emptySet())

    private fun record(maker: String? = "קיה ד. קוריאה", model: String? = "PICANTO", colour: String? = "שחור",
                       until: LocalDate? = LocalDate.of(2027, 1, 1), test: LocalDate? = LocalDate.of(2026, 1, 5), inactive: Boolean = false) =
        VehicleRecord(PlateNumber.parse("1234567")!!, until, test, maker, model, colour, null, inactive)

    private fun snapshot(tag: DisabilityLookupResult?, vehicle: VehicleLookupResult?, cachedAt: Instant? = null) =
        RegistrySnapshot(tag, vehicle, checked, cachedAt)

    @Test fun countrySuffixIsDroppedOnlyWhenItIsTheExactTail() {
        assertEquals("קיה", ReviewTemplateFacts.manufacturerName("קיה ד. קוריאה"))
        assertEquals("טויוטה", ReviewTemplateFacts.manufacturerName("טויוטה יפן"))
        assertEquals("מרצדס בנץ", ReviewTemplateFacts.manufacturerName("מרצדס בנץ גרמניה"))
        assertEquals("יפן", ReviewTemplateFacts.manufacturerName("יפן"))
        assertEquals("אלפא רומיאו", ReviewTemplateFacts.manufacturerName("אלפא רומיאו"))
        assertNull(ReviewTemplateFacts.manufacturerName("  "))
    }

    @Test fun vehicleTextDropsMissingPartsAndIsNullWhenNothingIsLeft() {
        assertEquals("קיה PICANTO בצבע שחור", ReviewTemplateFacts.vehicleText(record()))
        assertEquals("קיה PICANTO", ReviewTemplateFacts.vehicleText(record(colour = null)))
        assertEquals("PICANTO בצבע שחור", ReviewTemplateFacts.vehicleText(record(maker = null)))
        assertEquals("בצבע שחור", ReviewTemplateFacts.vehicleText(record(maker = null, model = null)))
        assertNull(ReviewTemplateFacts.vehicleText(record(maker = null, model = null, colour = null)))
    }

    @Test fun addressSplitsIntoStreetAndHouse() {
        assertEquals("רחוב הדוגמה" to "12", ReviewTemplateFacts.splitAddress("רחוב הדוגמה 12"))
        assertEquals("רחוב הדוגמה" to "12א", ReviewTemplateFacts.splitAddress("רחוב הדוגמה 12 א"))
        assertEquals("שדרות הדוגמה" to null, ReviewTemplateFacts.splitAddress("שדרות הדוגמה"))
    }

    @Test fun noRegistryResultAddsNoStateAndNoVehicle() {
        val facts = ReviewTemplateFacts.build(draft(), null)
        assertEquals(PermitState.UNKNOWN, facts.permit)
        assertNull(facts.value(TemplateSlot.VEHICLE))
        assertNull(facts.value(TemplateSlot.LICENSE_EXPIRY))
        assertEquals("1234567", facts.value(TemplateSlot.PLATE))
        assertEquals("רחוב הדוגמה", facts.value(TemplateSlot.STREET))
        assertEquals("12", facts.value(TemplateSlot.HOUSE))
    }

    @Test fun onlyACompletedLiveLookupMakesTheNoPermitSentenceAppear() {
        val vehicle = VehicleLookupResult.Found(record())
        assertEquals(PermitState.CHECKED_NONE, ReviewTemplateFacts.build(draft(), snapshot(DisabilityLookupResult.NotFound, vehicle)).permit)
        assertEquals(PermitState.HAS_PERMIT, ReviewTemplateFacts.build(draft(), snapshot(DisabilityLookupResult.Found(io.github.reihlinstinct.reporter.registry.DisabilityTagType.WHEELCHAIR), vehicle)).permit)
        assertEquals(PermitState.UNKNOWN, ReviewTemplateFacts.build(draft(), snapshot(null, vehicle)).permit)
        assertEquals(PermitState.UNKNOWN, ReviewTemplateFacts.build(draft(), snapshot(DisabilityLookupResult.Error(LookupErrorKind.NETWORK), vehicle)).permit)
        assertEquals("a cached result is not a completed live check", PermitState.UNKNOWN,
            ReviewTemplateFacts.build(draft(), snapshot(DisabilityLookupResult.NotFound, vehicle, cachedAt = checked)).permit)
    }

    @Test fun licenceDatesAppearOnlyWhenExpiredAndActive() {
        val expired = record(until = LocalDate.of(2026, 11, 14))
        val facts = ReviewTemplateFacts.build(draft(), snapshot(DisabilityLookupResult.NotFound, VehicleLookupResult.Found(expired)))
        assertEquals("14.11.2026", facts.value(TemplateSlot.LICENSE_EXPIRY))
        assertEquals("05.01.2026", facts.value(TemplateSlot.LAST_TEST))
        val today = record(until = LocalDate.of(2026, 11, 15))
        assertNull("valid through its own day", ReviewTemplateFacts.build(draft(), snapshot(null, VehicleLookupResult.Found(today))).value(TemplateSlot.LICENSE_EXPIRY))
        assertNull("an inactive vehicle record says nothing about the licence", ReviewTemplateFacts.build(draft(), snapshot(null, VehicleLookupResult.Found(expired.copy(inactive = true)))).value(TemplateSlot.LICENSE_EXPIRY))
        assertNull(ReviewTemplateFacts.build(draft(), snapshot(null, VehicleLookupResult.NotFound)).value(TemplateSlot.VEHICLE))
    }

    @Test fun sidewalkDescriptionReadsNaturallyWithTheVehicle() {
        val facts = ReviewTemplateFacts.build(draft(), snapshot(DisabilityLookupResult.NotFound, VehicleLookupResult.Found(record())))
        val text = DescriptionTemplates.join(DescriptionTemplates.render(BuiltInTemplates.byTag(BuiltInTemplates.SIDEWALK_PARKING)!!, facts))
        assertTrue(text, text.startsWith("רכב קיה PICANTO בצבע שחור, מספר רישוי 1234567, חונה על המדרכה ברחוב רחוב הדוגמה 12 בירושלים."))
        assertTrue(text.contains("לא נמצא תו חניה לנכה"))
        assertFalse(text.contains("תוקף רישיון"))
    }

    @Test fun specialOrUnreadyPlateIsNotInventedIntoTheFacts() {
        assertNull(ReviewTemplateFacts.build(draft(plate = "12"), null).value(TemplateSlot.PLATE))
        assertNull(ReviewTemplateFacts.build(draft(address = ""), null).value(TemplateSlot.STREET))
    }
}
