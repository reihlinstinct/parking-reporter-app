package io.github.reihlinstinct.reporter.core

import org.junit.Assert.*
import org.junit.Test

/** Issue #424 slice 2b: a category change must never lose the user's own description. Synthetic data. */
class TemplateSubjectChangeTest {
    private val sidewalk = MunicipalSubject("1145", "חנייה אסורה - חנייה על מדרכה", listOf("חנייה"))
    private val curb = MunicipalSubject("1144", "חנייה אסורה- אדום לבן / אין עצירה", listOf("חנייה"))
    private val facts = TemplateFacts(mapOf(TemplateSlot.PLATE to "1234567", TemplateSlot.STREET to "רחוב הדוגמה", TemplateSlot.HOUSE to "12"))
    private fun draft(text: String, subject: String) =
        ReviewDraft(Evidence("t", "a".repeat(64), "2026-11-15T06:27:43+02:00", 31.0, 35.0), "1234567", "רחוב הדוגמה 12", subject, text, emptySet())

    @Test fun withSubjectKeepsTheTextThatEditWouldClear() {
        val d = draft("הטקסט שלי", "municipal:1145")
        assertEquals("", d.edit(subject = "municipal:1144").exactHebrewText)
        val changed = TemplateSession.withSubject(d, "municipal:1144", true)
        assertEquals("הטקסט שלי", changed.exactHebrewText)
        assertEquals("municipal:1144", changed.subject)
    }

    @Test fun textThatOnlyArrivedWithTheDraftIsNotKeptAndCannotCarryAPlateIntoANoPlateCategory() {
        val arrived = draft("רכב 1234567 חונה על המדרכה", "municipal:1145")
        val changed = TemplateSession.withSubject(arrived, "municipal:1144", false)
        assertEquals("", changed.exactHebrewText)
        val next = TemplateSession.apply(curb, emptyList(), TemplateFacts(), changed.exactHebrewText, TemplateSession.State())
        assertFalse(next.text.contains("1234567"))
        assertFalse(next.state.frozen)
    }

    @Test fun anEditedDescriptionSurvivesACategoryChangeAndTheTemplateFreezes() {
        val first = TemplateSession.apply(sidewalk, emptyList(), facts, "", TemplateSession.State())
        assertTrue(first.text.contains("חונה על המדרכה"))
        val edited = first.text + " הערה שלי."
        val d = TemplateSession.withSubject(draft(edited, "municipal:1145"), "municipal:1144", true)
        val second = TemplateSession.apply(curb, emptyList(), facts, d.exactHebrewText, first.state)
        assertEquals(edited, second.text)
        assertTrue(second.state.frozen)
    }

    @Test fun anUntouchedDescriptionFollowsTheNewCategory() {
        val first = TemplateSession.apply(sidewalk, emptyList(), facts, "", TemplateSession.State())
        val d = TemplateSession.withSubject(draft(first.text, "municipal:1145"), "municipal:1144", false)
        val second = TemplateSession.apply(curb, emptyList(), facts, d.exactHebrewText, first.state)
        assertTrue(second.text, second.text.contains("אדום-לבן"))
        assertFalse(second.state.frozen)
    }
}
