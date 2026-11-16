package io.github.reihlinstinct.reporter.core

import io.github.reihlinstinct.reporter.ledger.DraftCodec
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PlatePolicyTest {
    private fun row(code: String, flag: Boolean? = null, name: String = "topic") = MunicipalSubject(code, name, listOf(name), flag)
    @Test fun metadataAndAdditiveOverridePrecedeBootstrapWithoutNameInference() {
        val topics = listOf(row("1144"), row("1145", false), row("9", true), row("10"))
        assertTrue(PlatePolicy.required("1144", topics, emptySet()))
        assertFalse(PlatePolicy.required("1145", topics, emptySet()))
        assertTrue(PlatePolicy.required("9", topics, emptySet()))
        assertFalse(PlatePolicy.required("10", topics, emptySet()))
        assertTrue(PlatePolicy.required("1145", topics, setOf("1145")))
        assertTrue(PlatePolicy.required("10", topics, setOf("10")))
        assertFalse(PlatePolicy.required("missing", topics, setOf("missing")))
        val renamed = listOf(row("10", name = "renamed"))
        assertTrue(PlatePolicy.required("10", renamed, setOf("10")))
        assertFalse(PlatePolicy.required("11", listOf(row("11", name = "renamed")), setOf("10")))
    }
    @Test fun flagsAreStrictAndCacheFingerprintTracksMetadataWithoutChangingLegacyFingerprint() {
        val dir = Files.createTempDirectory("plate-policy").toFile()
        try {
            val cache = CityTopicCache(java.io.File(dir, "catalog.json"))
            val original = cache.replace(listOf(row("1144")))
            val changed = cache.replace(listOf(row("1144", false)))
            assertNotEquals(original.fingerprint, changed.fingerprint)
            assertEquals(false, cache.read()!!.topics.single().plateRequired)
            assertThrows(IllegalArgumentException::class.java) {
                CityTopicCache.flatten(JSONArray("""[{"categoryName":"x","subjectCode":"1144","plateRequired":"false"}]"""))
            }
            assertEquals(changed, cache.read())
            assertThrows(IllegalArgumentException::class.java) { cache.replace(listOf(row("1144", false), row("1144", true))) }
            assertEquals(changed, cache.read())
        } finally { dir.deleteRecursively() }
    }
    @Test fun policyDraftRoundtripsAndDescriptionIsNeverSilentlyModified() {
        val d = ReviewDraft(Evidence("test", "a".repeat(64), null, 31.0, 35.0), "", "test12", "municipal:10", "description", emptySet())
        val enabled = PlatePolicy.apply(d, listOf(row("10")), setOf("10")).edit(plate = "1234567")
        assertTrue(enabled.needsPlate()); assertTrue(enabled.isReady())
        assertEquals(enabled, DraftCodec.decode(DraftCodec.encode(enabled)))
        assertFalse(PlatePolicy.descriptionContainsPlate(enabled))
        assertEquals("description", enabled.exactHebrewText)
        assertTrue(PlatePolicy.descriptionContainsPlate(enabled.edit(text = "רכב 1234567 חונה")))
        assertFalse(PlatePolicy.descriptionContainsPlate(enabled.edit(text = "רכב 912345678 חונה")))
        assertEquals("", enabled.edit(subject = "municipal:11").plate)
    }
}
