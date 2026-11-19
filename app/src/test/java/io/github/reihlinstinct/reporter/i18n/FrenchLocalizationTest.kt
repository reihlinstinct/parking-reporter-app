package io.github.reihlinstinct.reporter.i18n

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #292: French is a machine draft, left to right, with every active key present. The
 * generic completeness and placeholder tests already cover it through the catalog; this
 * file pins what is French-specific: the language block, the visible machine notice,
 * fallback, switching, and the Hebrew municipal wording that must not be translated.
 */
class FrenchLocalizationTest {
    private val catalog = I18nTestFiles.realCatalog()
    private val fr = I18nTestFiles.codeNamed("Français")
    private val he = catalog.defaultLanguage!!.code
    private val strings = JSONObject(I18nTestFiles.realCatalogText()).getJSONObject("strings")

    @Test fun frenchIsLtrMachineDraftWithTheSharedStatus() {
        val l = catalog.language(fr)!!
        assertEquals(TextDir.LTR, l.dir)
        assertTrue(l.machineDraft)
        assertEquals(catalog.language(I18nTestFiles.codeNamed("English"))!!.status, l.status)
    }

    @Test fun everyKeyHasANonBlankFrenchValue() {
        for (key in strings.keys()) {
            val v = strings.getJSONObject(key).opt(fr)
            assertTrue("$key has fr", v != null && v.toString().isNotBlank() && v.toString() != "{}")
        }
    }

    @Test fun machineTranslationNoticeExistsInEveryLanguageAndFrenchSaysSo() {
        val notice = strings.getJSONObject("settings.language.machine_notice")
        for (l in catalog.languages) assertTrue("${l.code} notice", notice.optString(l.code).isNotBlank())
        assertTrue(notice.getString(fr).contains("automatique"))
    }

    @Test fun switchingToFrenchChangesVisibleTextAndUnknownCodesFallBackToHebrew() {
        for (key in listOf("shell.home.title", "review.submit.button", "settings.language.title")) {
            assertNotEquals(key, catalog.t(he, key), catalog.t(fr, key))
        }
        assertEquals(catalog.t(he, "shell.home.title"), catalog.t("zz", "shell.home.title"))
    }

    @Test fun hebrewMunicipalWordingAndLicenceTitlesStayUntranslated() {
        for (key in listOf("review.location.intersection_line", "registry.plate_text", "licenses.osm.title", "licenses.mlkit.title", "licenses.tesseract.title")) {
            assertEquals(key, catalog.t(he, key), catalog.t(fr, key))
        }
    }

    @Test fun frenchPluralAndPlaceholdersSurviveFormatting() {
        assertEquals("Version 1.2", catalog.t(fr, "settings.version", mapOf("version" to "1.2")))
        assertFalse(catalog.t(fr, "shell.submit.sent", mapOf("case_number" to "123")).contains("{"))
    }
}
