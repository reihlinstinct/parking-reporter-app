package io.github.reihlinstinct.reporter.i18n

import org.junit.Assert.*
import org.junit.Test

class RegistryStringsTest {
    @Test fun registryAndPlateCategoryKeysAreTranslatedAndPlaceholderParityHolds() {
        val catalog = I18nTestFiles.realCatalog()
        val keys = catalog.keys.filter { it.startsWith("registry.") || it.startsWith("plate_categories.") }
        assertTrue(keys.size >= 20)
        for (key in keys) {
            val row = catalog.rawEntry(key)!!
            val expected = StringCatalog.PLACEHOLDER.findAll(row.getString(catalog.defaultLanguage!!.code)).map { it.groupValues[1] }.toSet()
            for (lang in catalog.languages) {
                val text = row.getString(lang.code)
                assertTrue("$key/${lang.code}", text.isNotBlank())
                assertEquals("$key/${lang.code}", expected, StringCatalog.PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toSet())
            }
        }
    }
}
