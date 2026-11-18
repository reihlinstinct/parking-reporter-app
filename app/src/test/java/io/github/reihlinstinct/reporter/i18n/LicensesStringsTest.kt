package io.github.reihlinstinct.reporter.i18n

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings -> Licenses wording follow-ups (Refs #163, #167): the ML Kit body names the
 * page its link opens (the ML Kit Terms of Service at developers.google.com/ml-kit/terms,
 * which incorporate the Google APIs Terms of Service), and the Amharic OSM body uses
 * "derived" for the ODbL derivative database instead of the stray "የተራመደው". The OSM body
 * names the directly bundled database, without a false reproducibility claim.
 */
class LicensesStringsTest {

    private val raw = JSONObject(I18nTestFiles.realCatalogText()).getJSONObject("strings")

    /** Every catalog language (td423): a new language is covered by the structural checks at once. */
    private val languages = I18nTestFiles.languageCodes()

    /** Wording pins are keyed by each language's own name, so no code is written here. */
    private fun code(nativeName: String): String = I18nTestFiles.codeNamed(nativeName)

    private fun value(key: String, lang: String): String = raw.getJSONObject(key).getString(lang)

    /** A pin table may only name catalog languages; a language without a pin is checked structurally. */
    private fun pinsByCode(pins: Map<String, String>): Map<String, String> = pins.mapKeys { (name, _) -> code(name) }

    private val mlkitBody = linkedMapOf(
        "עברית" to "זיהוי טקסט בתמונות באמצעות Google ML Kit, בכפוף לתנאי השירות של ML Kit (הכוללים את תנאי השימוש של Google APIs): developers.google.com/ml-kit/terms",
        "English" to "On-image text recognition by Google ML Kit, subject to the ML Kit Terms of Service (which incorporate the Google APIs Terms of Service): developers.google.com/ml-kit/terms",
        "العربية" to "التعرف على النص في الصور بواسطة Google ML Kit، وفقًا لشروط خدمة ML Kit (التي تتضمن شروط استخدام Google APIs): developers.google.com/ml-kit/terms",
        "Русский" to "Распознавание текста на изображениях с помощью Google ML Kit в соответствии с условиями использования ML Kit (включающими условия использования Google APIs): developers.google.com/ml-kit/terms",
        "አማርኛ" to "በምስሎች ላይ ያለውን ጽሑፍ መለየት በGoogle ML Kit፣ በML Kit የአገልግሎት ውሎች ሥር (የGoogle APIs የአገልግሎት ውሎችን ያካትታሉ): developers.google.com/ml-kit/terms",
    )

    @Test
    fun mlkitBodyIsPinnedInEveryPinnedLanguage() {
        for ((lang, pinned) in pinsByCode(mlkitBody)) {
            assertEquals("licenses.mlkit.body[$lang]", pinned, value("licenses.mlkit.body", lang))
        }
    }

    @Test
    fun mlkitBodyNamesTheMlKitTermsPageItLinksTo() {
        for (lang in languages) {
            val text = value("licenses.mlkit.body", lang)
            assertTrue("$lang ends with the ML Kit terms URL", text.endsWith("developers.google.com/ml-kit/terms"))
            // "Google ML Kit" (the product) plus "ML Kit" in the terms label.
            assertEquals("$lang names ML Kit twice (product and terms)", 2, Regex("ML Kit").findAll(text).count())
            assertTrue("$lang still names the incorporated Google APIs terms", text.contains("Google APIs"))
        }
    }

    @Test
    fun amharicOsmBodyUsesDerivedNotTheOldWord() {
        val am = value("licenses.osm.body", code("አማርኛ"))
        assertTrue(am.contains("የአድራሻ ዳታቤዙ"))
        assertFalse(am.contains("የተራመደው"))
    }

    @Test
    fun theOldAmharicWordAppearsNowhereInTheCatalog() {
        val keys = raw.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = raw.optJSONObject(key) ?: continue
            assertFalse("$key[am]", entry.optString(code("አማርኛ")).contains("የተራመደው"))
        }
    }

    // The bundled legacy file is available directly, not reproduced by the open
    // replacement generator. Keep the approved notice honest about that route.
    private val osmBody = linkedMapOf(
        "עברית" to "נקודות הכתובות מבוססות על OpenStreetMap, © תורמי OpenStreetMap, ברישיון ODbL 1.0. מאגר הכתובות כלול בקוד המקור ובאפליקציה ומופץ באותו רישיון.",
        "English" to "Address points are based on OpenStreetMap, © OpenStreetMap contributors, under ODbL 1.0. The address database is included in the source repository and app and distributed under the same licence.",
        "العربية" to "تستند نقاط العناوين إلى OpenStreetMap، © مساهمو OpenStreetMap، بموجب ODbL 1.0. قاعدة العناوين مضمنة في مستودع المصدر والتطبيق وموزعة بموجب الترخيص نفسه.",
        "Русский" to "Адресные точки основаны на OpenStreetMap, © участники OpenStreetMap, по лицензии ODbL 1.0. База адресов включена в исходный репозиторий и приложение и распространяется по той же лицензии.",
        "አማርኛ" to "የአድራሻ ነጥቦች በOpenStreetMap፣ © የOpenStreetMap አበርካቾች፣ በODbL 1.0 ፍቃድ ላይ የተመሰረቱ ናቸው። የአድራሻ ዳታቤዙ በምንጭ ማከማቻው እና በመተግበሪያው ውስጥ ተካትቶ በዚያው ፍቃድ ይሰራጫል።",
    )
    @Test fun osmBodyIsPinnedInEveryPinnedLanguage() {
        for ((lang, pinned) in pinsByCode(osmBody)) assertEquals(pinned, value("licenses.osm.body", lang))
    }
    @Test fun osmNoticeKeepsAttributionAndLicenseWithoutFalseExactnessClaim() {
        for (lang in languages) {
            val text = value("licenses.osm.body", lang)
            assertTrue(text.contains("©") && text.contains("OpenStreetMap"))
            assertTrue(text.contains("ODbL 1.0"))
            assertFalse(text.contains("1381350"))
        }
        assertFalse(value("licenses.osm.body", code("English")).contains("reproduced exactly"))
        assertTrue(value("licenses.osm.body", code("English")).contains("included in the source repository and app"))
    }
    @Test fun sourceLinksAreClickableAndUseTheNamedSources() {
        val source = I18nTestFiles.moduleFile("src/main/java/io/github/reihlinstinct/reporter/ui/LicensesScreen.kt").readText()
        for (url in listOf("https://www.openstreetmap.org/copyright", "https://opendatacommons.org/licenses/odbl/1-0/", "https://data.gov.il/terms-of-use")) {
            assertTrue(source.contains(url))
        }
        assertTrue(source.contains("uriHandler.openUri"))
    }
    @Test fun everyPinTableNamesOnlyCatalogLanguages() {
        for (pins in listOf(mlkitBody, osmBody)) {
            assertTrue(pinsByCode(pins).keys.all { it in languages })
            assertEquals(pins.size, pinsByCode(pins).keys.toSet().size)
        }
    }
}
