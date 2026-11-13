package io.github.reihlinstinct.reporter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DescriptionTemplateTest {
    private fun T(v: String): TemplatePart = TemplatePart.Text(v)
    private fun S(slot: TemplateSlot): TemplatePart = TemplatePart.Slot(slot)
    private val template = DescriptionTemplate(
        tag = "test_tag", version = 1,
        sentences = listOf(
            TemplateSentence("where", listOf(T("Car "), S(TemplateSlot.VEHICLE), T(", plate "), S(TemplateSlot.PLATE), T(", at "), S(TemplateSlot.STREET), T(" "), S(TemplateSlot.HOUSE), T("."))),
            TemplateSentence("expired", listOf(T("Licence ended "), S(TemplateSlot.LICENSE_EXPIRY), TemplatePart.Optional(listOf(T(", last test "), S(TemplateSlot.LAST_TEST))), T("."))),
            TemplateSentence("nopermit", listOf(T("No permit found.")), onlyWhenPermit = PermitState.CHECKED_NONE),
        ),
    )
    private fun facts(vararg p: Pair<TemplateSlot, String>, permit: PermitState = PermitState.UNKNOWN) = TemplateFacts(mapOf(*p), permit)
    private val full = arrayOf(TemplateSlot.VEHICLE to "Ioniq", TemplateSlot.PLATE to "1234567", TemplateSlot.STREET to "Main", TemplateSlot.HOUSE to "22")

    @Test fun missingSlotDropsTheWholeSentenceNeverAPlaceholder() {
        val text = DescriptionTemplates.join(DescriptionTemplates.render(template, facts(TemplateSlot.PLATE to "1234567", TemplateSlot.STREET to "Main")))
        assertEquals("", text)
        val ok = DescriptionTemplates.join(DescriptionTemplates.render(template, facts(*full)))
        assertEquals("Car Ioniq, plate 1234567, at Main 22.", ok)
        assertFalse(ok.contains("{"))
    }

    @Test fun optionalGroupDisappearsAloneAndBlankValuesCountAsEmpty() {
        val a = DescriptionTemplates.render(template, facts(*full, TemplateSlot.LICENSE_EXPIRY to "12.10.2026"))
        assertEquals("Licence ended 12.10.2026.", a.last().text)
        val b = DescriptionTemplates.render(template, facts(*full, TemplateSlot.LICENSE_EXPIRY to "12.10.2026", TemplateSlot.LAST_TEST to "  "))
        assertEquals("Licence ended 12.10.2026.", b.last().text)
        val c = DescriptionTemplates.render(template, facts(*full, TemplateSlot.LICENSE_EXPIRY to "12.10.2026", TemplateSlot.LAST_TEST to "12.10.2025"))
        assertEquals("Licence ended 12.10.2026, last test 12.10.2025.", c.last().text)
    }

    @Test fun permitSentenceNeedsTheCheckedState() {
        for (p in listOf(PermitState.UNKNOWN, PermitState.HAS_PERMIT)) {
            assertTrue(DescriptionTemplates.render(template, facts(*full, permit = p)).none { it.id == "nopermit" })
        }
        assertTrue(DescriptionTemplates.render(template, facts(*full, permit = PermitState.CHECKED_NONE)).any { it.id == "nopermit" })
    }

    @Test fun changingPermitStateUpdatesAnUntouchedDescription() {
        val before = DescriptionTemplates.render(template, facts(*full))
        var text = DescriptionTemplates.join(before)
        val withNone = DescriptionTemplates.render(template, facts(*full, permit = PermitState.CHECKED_NONE))
        text = DescriptionTemplates.update(text, before, withNone)
        assertEquals("Car Ioniq, plate 1234567, at Main 22. No permit found.", text)
        val back = DescriptionTemplates.update(text, withNone, before)
        assertEquals("Car Ioniq, plate 1234567, at Main 22.", back)
    }

    @Test fun aHandEditedSentenceIsKeptAndNotReAdded() {
        val before = DescriptionTemplates.render(template, facts(*full))
        val edited = "My own words about the car. " + before.single().text.replace("Main", "Other")
        val moved = DescriptionTemplates.render(template, facts(*full, TemplateSlot.STREET to "Third"))
        assertEquals(edited, DescriptionTemplates.update(edited, before, moved))
        // an untouched sentence still follows the form while the edited one stays
        val two = DescriptionTemplates.render(template, facts(*full, TemplateSlot.LICENSE_EXPIRY to "1.1.2026"))
        val joined = DescriptionTemplates.join(two)
        val editedFirst = joined.replace("Main", "Other")
        val later = DescriptionTemplates.render(template, facts(*full, TemplateSlot.LICENSE_EXPIRY to "2.2.2026"))
        assertEquals(editedFirst.replace("1.1.2026", "2.2.2026"), DescriptionTemplates.update(editedFirst, two, later))
    }

    @Test fun untouchedMeansBlankOrExactlyWhatWasRendered() {
        val r = DescriptionTemplates.render(template, facts(*full))
        assertTrue(DescriptionTemplates.isUntouched("", r))
        assertTrue(DescriptionTemplates.isUntouched(DescriptionTemplates.join(r), r))
        assertFalse(DescriptionTemplates.isUntouched(DescriptionTemplates.join(r) + "!", r))
    }

    private fun topic(code: String, name: String) = MunicipalSubject(code, name, listOf(name))

    @Test fun mappingMatchesCodeThenNameAndFlagsARenamedCode() {
        val shipped = listOf(TemplateMappingEntry("1144", "red white curb", "curb"), TemplateMappingEntry("999", "disabled bay", "disabled"))
        assertEquals(TemplateResolution.Match("curb"), TemplateMapping.resolve(topic("1144", "Red  White Curb"), emptyList(), shipped))
        // renumbered by the city: the name still finds it
        assertEquals(TemplateResolution.Match("disabled"), TemplateMapping.resolve(topic("2001", "disabled bay"), emptyList(), shipped))
        // same code, different name: flagged, not applied
        val r = TemplateMapping.resolve(topic("1144", "something else"), emptyList(), shipped)
        assertTrue(r is TemplateResolution.NeedsReview)
        assertEquals(TemplateResolution.None, TemplateMapping.resolve(topic("5", "other"), emptyList(), shipped))
        assertEquals(TemplateResolution.Match("generic"), TemplateMapping.resolve(topic("5", "other"), emptyList(), shipped, "generic"))
        assertEquals(TemplateResolution.None, TemplateMapping.resolve(null, emptyList(), shipped, "generic"))
    }

    @Test fun userMappingOverridesShippedAndResetRestoresDefaults() {
        val shipped = listOf(TemplateMappingEntry("1144", "curb", "curb"))
        val user = listOf(TemplateMappingEntry("1144", "curb", "my_curb"))
        assertEquals(TemplateResolution.Match("my_curb"), TemplateMapping.resolve(topic("1144", "curb"), user, shipped))
        assertEquals(TemplateResolution.Match("curb"), TemplateMapping.resolve(topic("1144", "curb"), emptyList(), shipped))
        // a stale user entry is flagged and does not fall through to the shipped one
        assertTrue(TemplateMapping.resolve(topic("1144", "renamed"), user, shipped) is TemplateResolution.NeedsReview)
    }
}
