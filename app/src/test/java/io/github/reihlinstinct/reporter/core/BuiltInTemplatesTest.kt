package io.github.reihlinstinct.reporter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInTemplatesTest {
    private fun facts(vararg p: Pair<TemplateSlot, String>, permit: PermitState = PermitState.UNKNOWN) = TemplateFacts(mapOf(*p), permit)
    private val full = arrayOf(
        TemplateSlot.VEHICLE to "יונדאי איוניק", TemplateSlot.PLATE to "1234567",
        TemplateSlot.STREET to "עגנון", TemplateSlot.HOUSE to "5",
    )
    private fun text(tag: String, f: TemplateFacts) = DescriptionTemplates.join(DescriptionTemplates.render(BuiltInTemplates.byTag(tag)!!, f))
    private val sidewalk = MunicipalSubject("1145", "חנייה אסורה - חנייה על מדרכה", emptyList())
    private val curb = MunicipalSubject("1144", "חנייה אסורה- אדום לבן / אין עצירה", emptyList())
    private val disabled = MunicipalSubject("1153", "חנייה אסורה - חניית רכב במקום המיועד לנכה", emptyList())

    @Test fun theFourApprovedTextsRenderExactly() {
        val f = facts(*full)
        assertEquals("רכב יונדאי איוניק, מספר רישוי 1234567, חונה בצמוד לאבן שפה המסומנת באדום-לבן ברחוב עגנון 5 בירושלים. אבקש לשלוח פקח לבדיקת החניה האסורה ולאכיפה בהתאם לדין.", text("red_white_curb", f))
        assertEquals("רכב יונדאי איוניק, מספר רישוי 1234567, חונה על המדרכה ברחוב עגנון 5 בירושלים. אבקש לשלוח פקח לבדיקת החניה האסורה ולאכיפה בהתאם לדין.", text("sidewalk_parking", f))
        assertEquals("רכב יונדאי איוניק, מספר רישוי 1234567, חונה בחניית נכים ברחוב עגנון 5 בירושלים. אבקש לשלוח פקח לבדוק אם קיים תו חניה לנכה בתוקף ולאכוף בהתאם לדין.", text("disabled_bay", f))
        assertEquals("רכב יונדאי איוניק, מספר רישוי 1234567, חונה ברחוב עגנון 5 בירושלים. אבקש לשלוח פקח לבדיקה ולאכיפה בהתאם לדין.", text("generic", f))
    }

    @Test fun missingFieldsDropTheirPartNeverLeaveAPlaceholder() {
        val noVehicle = text("sidewalk_parking", facts(TemplateSlot.PLATE to "1234567", TemplateSlot.STREET to "עגנון", TemplateSlot.HOUSE to "5"))
        assertTrue(noVehicle.startsWith("מספר רישוי 1234567, חונה על המדרכה ברחוב עגנון 5 בירושלים."))
        val noHouse = text("generic", facts(TemplateSlot.PLATE to "1234567", TemplateSlot.STREET to "עגנון"))
        assertTrue(noHouse.startsWith("מספר רישוי 1234567, חונה ברחוב עגנון בירושלים."))
        val noStreet = text("generic", facts(TemplateSlot.PLATE to "1234567", TemplateSlot.HOUSE to "5"))
        assertTrue(noStreet.startsWith("מספר רישוי 1234567, חונה בירושלים."))
        for (t in BuiltInTemplates.templates) {
            val out = text(t.tag, facts())
            assertFalse(out.contains("{") || out.contains("  "))
        }
    }

    @Test fun stateSentencesAppearOnlyOnVerifiedFacts() {
        val base = text("disabled_bay", facts(*full))
        assertFalse(base.contains("תו חניה לנכה על מספר הרישוי הזה"))
        assertFalse(base.contains("תוקף רישיון הרכב"))
        val none = text("disabled_bay", facts(*full, permit = PermitState.CHECKED_NONE))
        assertTrue(none.endsWith("לפי הנתונים הפומביים של משרד התחבורה לא נמצא תו חניה לנכה על מספר הרישוי הזה."))
        val expired = text("sidewalk_parking", facts(*full, TemplateSlot.LICENSE_EXPIRY to "01.03.2026"))
        assertTrue(expired.endsWith("לפי נתוני משרד התחבורה, תוקף רישיון הרכב הרשום הסתיים ביום 01.03.2026."))
        val withTest = text("sidewalk_parking", facts(*full, TemplateSlot.LICENSE_EXPIRY to "01.03.2026", TemplateSlot.LAST_TEST to "01.03.2025"))
        assertTrue(withTest.endsWith("הסתיים ביום 01.03.2026 והמבחן האחרון הרשום נערך ביום 01.03.2025."))
    }

    @Test fun shippedMappingPointsOnlyAtExistingTags() {
        for (e in BuiltInTemplates.shippedMapping) assertNotNull(BuiltInTemplates.byTag(e.tag))
        assertEquals(BuiltInTemplates.templates.size, BuiltInTemplates.templates.map { it.tag }.toSet().size)
    }

    @Test fun categoriesResolveByCodeAndFallBackToGeneric() {
        val f = facts(*full)
        val empty = TemplateSession.State()
        assertTrue(TemplateSession.apply(sidewalk, emptyList(), f, "", empty).text.contains("חונה על המדרכה"))
        assertTrue(TemplateSession.apply(curb, emptyList(), f, "", empty).text.contains("אדום-לבן"))
        assertTrue(TemplateSession.apply(disabled, emptyList(), f, "", empty).text.contains("חניית נכים"))
        val other = MunicipalSubject("9999", "קטגוריה אחרת", emptyList())
        assertEquals(text("generic", f), TemplateSession.apply(other, emptyList(), f, "", empty).text)
        assertEquals("", TemplateSession.apply(null, emptyList(), f, "", empty).text)
    }

    @Test fun aRenamedCategoryIsFlaggedNotAppliedAndTheTextStays() {
        val renamed = MunicipalSubject("1145", "שם חדש", emptyList())
        val r = TemplateSession.apply(renamed, emptyList(), facts(*full), "my text", TemplateSession.State())
        assertEquals("my text", r.text)
        assertNotNull(r.needsReview)
        assertEquals(TemplateSession.State(), r.state)
        // the tracking passed in is kept
        val kept = TemplateSession.apply(sidewalk, emptyList(), facts(*full), "", TemplateSession.State())
        assertEquals(kept.state, TemplateSession.apply(renamed, emptyList(), facts(*full), kept.text, kept.state).state)
    }

    @Test fun theUsersMappingOverridesTheShippedOne() {
        val user = listOf(TemplateMappingEntry("1145", "חנייה אסורה - חנייה על מדרכה", BuiltInTemplates.RED_WHITE_CURB))
        val r = TemplateSession.apply(sidewalk, user, facts(*full), "", TemplateSession.State())
        assertTrue(r.text.contains("אדום-לבן"))
    }

    @Test fun anEditedDescriptionIsNeverOverwrittenByACategoryChange() {
        val f = facts(*full)
        val first = TemplateSession.apply(sidewalk, emptyList(), f, "", TemplateSession.State())
        val edited = "הכל בדיוק כמו שכתבתי. " + first.text
        val second = TemplateSession.apply(curb, emptyList(), f, edited, first.state)
        assertEquals(edited, second.text)
        assertTrue(second.state.frozen)
        val third = TemplateSession.apply(curb, emptyList(), f, edited, second.state)
        assertEquals(edited, third.text)
    }

    @Test fun whileFrozenOnlyTheStateSentencesFollowTheFacts() {
        val none = facts(*full, permit = PermitState.CHECKED_NONE)
        val chip = "לפי הנתונים הפומביים של משרד התחבורה לא נמצא תו חניה לנכה על מספר הרישוי הזה."
        val first = TemplateSession.apply(sidewalk, emptyList(), none, "", TemplateSession.State())
        assertTrue(first.text.endsWith(chip))
        val edited = first.text.replace("עגנון", "רחוב אחר")
        val frozen = TemplateSession.apply(curb, emptyList(), none, edited, first.state)
        assertTrue(frozen.state.frozen)
        assertEquals(edited, frozen.text)
        val permitFound = TemplateSession.apply(curb, emptyList(), facts(*full, permit = PermitState.HAS_PERMIT), frozen.text, frozen.state)
        assertEquals(edited.removeSuffix(chip).trim(), permitFound.text)
        val again = TemplateSession.apply(curb, emptyList(), none, permitFound.text, permitFound.state)
        assertEquals(edited.removeSuffix(chip).trim() + " " + chip, again.text)
    }

    @Test fun aRenamedCategoryInTheMiddleDoesNotFreezeAnUntouchedText() {
        val f = facts(*full)
        val a = TemplateSession.apply(sidewalk, emptyList(), f, "", TemplateSession.State())
        val renamed = MunicipalSubject("1145", "שם חדש", emptyList())
        val b = TemplateSession.apply(renamed, emptyList(), f, a.text, a.state)
        assertEquals(a.text, b.text)
        assertEquals(a.state, b.state)
        val c = TemplateSession.apply(curb, emptyList(), f, b.text, b.state)
        assertTrue(c.text.contains("אדום-לבן") && !c.text.contains("על המדרכה"))
        assertFalse(c.state.frozen)
    }

    @Test fun aTemplateLessCategoryStillLetsTheStateSentencesFollowTheFacts() {
        val none = facts(*full, permit = PermitState.CHECKED_NONE)
        val chip = "לפי הנתונים הפומביים של משרד התחבורה לא נמצא תו חניה לנכה על מספר הרישוי הזה."
        val a = TemplateSession.apply(sidewalk, emptyList(), none, "", TemplateSession.State())
        assertTrue(a.text.endsWith(chip))
        val renamed = MunicipalSubject("1145", "שם חדש", emptyList())
        val b = TemplateSession.apply(renamed, emptyList(), facts(*full, permit = PermitState.HAS_PERMIT), a.text, a.state)
        assertEquals(a.text.removeSuffix(chip).trim(), b.text)
        assertNotNull(b.needsReview)
    }

    @Test fun anUntouchedDescriptionFollowsTheCategoryAndThePermitState() {
        val f = facts(*full)
        val a = TemplateSession.apply(sidewalk, emptyList(), f, "", TemplateSession.State())
        val b = TemplateSession.apply(curb, emptyList(), f, a.text, a.state)
        assertTrue(b.text.contains("אדום-לבן") && !b.text.contains("על המדרכה"))
        val c = TemplateSession.apply(curb, emptyList(), facts(*full, permit = PermitState.CHECKED_NONE), b.text, b.state)
        assertTrue(c.text.endsWith("הרישוי הזה."))
        val d = TemplateSession.apply(curb, emptyList(), f, c.text, c.state)
        assertEquals(b.text, d.text)
    }

    @Test fun aRemovedChipSentenceStaysRemoved() {
        val f = facts(*full, permit = PermitState.CHECKED_NONE)
        val a = TemplateSession.apply(disabled, emptyList(), f, "", TemplateSession.State())
        val chip = " לפי הנתונים הפומביים של משרד התחבורה לא נמצא תו חניה לנכה על מספר הרישוי הזה."
        assertTrue(a.text.endsWith(chip.trim()))
        val removed = a.text.removeSuffix(chip.trim()).trim()
        val b = TemplateSession.apply(disabled, emptyList(), f, removed, a.state)
        assertEquals(removed, b.text)
    }
}
