package io.github.reihlinstinct.reporter.core

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import java.nio.file.Files
import java.time.Instant

class CityTopicSearchTest {
    private val rows = listOf(MunicipalSubject("1280", "עמוד נפל", listOf("גדרות", "עמוד נפל")), MunicipalSubject("1145", "חניה על מדרכה", listOf("חניה", "חניה על מדרכה")))
    @Test fun aliasesAndHebrewNormalizationAreOfflineAndDeterministic() {
        val ranker = LocalTopicRanker(mapOf("1280" to listOf("התקנת עמודים")))
        assertEquals("1280", ranker.search("הַתְקָנַת עמודים", rows, emptyList()).first().code)
        assertEquals("1145", ranker.search("1145", rows, emptyList()).single().code)
        assertEquals("1280", ranker.search("", rows, listOf("1280")).first().code)
        assertTrue(ranker.search("לא קיים בכלל", rows, emptyList()).isEmpty())
        assertEquals(1, LocalTopicRanker.distance("abcd", "acbd"))
    }
    @Test fun repeatedCodeStillSearchesEveryMunicipalHierarchyPath() {
        val copies = listOf(MunicipalSubject("1", "אחר", listOf("גינון", "אחר")), MunicipalSubject("1", "אחר", listOf("תאורה", "אחר")))
        assertEquals("1", LocalTopicRanker().search("תאורה", copies, emptyList()).single().code)
        assertEquals(1, LocalTopicRanker().search("", copies, emptyList()).size)
    }
    @Test fun cachePreservesLastGoodListAndDates() {
        val dir = Files.createTempDirectory("topics-test").toFile()
        try {
            val now = Instant.parse("2026-11-11T00:00:00Z")
            val cache = CityTopicCache(java.io.File(dir, "catalog.json")) { now }
            assertNull(cache.read())
            val saved = cache.replace(rows)
            assertEquals(rows, cache.read()!!.topics)
            assertFalse(saved.refreshDue(now.plusSeconds(7*86400L)))
            assertTrue(saved.refreshDue(now.plusSeconds(7*86400L+1)))
            assertTrue(saved.stale(now.plusSeconds(91*86400L)))
            try { cache.replace(emptyList()); fail("empty catalog") } catch (_: IllegalArgumentException) { }
            assertEquals(rows, cache.read()!!.topics)
        } finally { dir.deleteRecursively() }
    }
    @Test fun cacheRejectsTruncationDetectsCorruptionAndClampsFutureDate() {
        val dir = Files.createTempDirectory("topics-sanity").toFile()
        try {
            var now = Instant.parse("2026-11-11T00:00:00Z")
            val file = java.io.File(dir, "catalog.json")
            val cache = CityTopicCache(file) { now }
            val topics = (1..10).map { MunicipalSubject(it.toString(), "topic$it", listOf("root", "topic$it")) }
            val initial = cache.replace(topics)
            try { cache.replace(topics.take(1)); fail("partial catalog must not replace last-good") } catch (_: IllegalArgumentException) { }
            assertEquals(topics, cache.read()!!.topics)
            now = now.plusSeconds(86400)
            val unchanged = cache.replace(topics.reversed())
            assertEquals(initial.fingerprint, unchanged.fingerprint)
            assertEquals(topics, unchanged.topics)
            assertEquals(now, unchanged.checkedAt)
            val root = org.json.JSONObject(file.readText())
            root.put("checked_at", now.plusSeconds(365*86400L).toString()); file.writeText(root.toString())
            assertEquals(Instant.EPOCH, cache.read()!!.checkedAt)
            assertTrue(cache.read()!!.refreshDue(now))
            root.put("fingerprint", "invalid"); file.writeText(root.toString())
            assertNull(cache.read())
            file.writeText("broken JSON"); assertNull(cache.read())
        } finally { dir.deleteRecursively() }
    }

    @Test fun flattenKeepsPathsAndRejectsMalformedNodes() {
        val tree = JSONArray("""[{"categoryName":"גדרות","children":[{"categoryName":"עמוד נפל","subjectCode":"1280"}]}]""")
        assertEquals(listOf("גדרות", "עמוד נפל"), CityTopicCache.flatten(tree).single().path)
        try { CityTopicCache.flatten(JSONArray("""[{"categoryName":"bad","subjectCode":1280}]""")); fail("numeric code") } catch (_: Exception) { }
    }
    @Test fun reviewedAliasAssetReferencesExistingCityCodesWithoutCollisions() {
        val module = java.io.File("src/main/assets").takeIf { it.isDirectory } ?: java.io.File("app/src/main/assets")
        val catalog = java.io.File(module, "taxonomy/municipal_subjects.json").inputStream().use { MunicipalSubjects.load(it) }
        val aliases = org.json.JSONObject(java.io.File(module, "taxonomy/topic_aliases.json").readText())
        val seen = mutableSetOf<String>()
        for (code in aliases.keys()) {
            assertTrue("unknown alias code $code", catalog.hasCode(code))
            val values = aliases.getJSONArray(code)
            for (i in 0 until values.length()) assertTrue("alias collision", seen.add(LocalTopicRanker.normalize(values.getString(i))))
        }
    }
    @Test fun legacyMigrationDoesNotCreateASecondCategorySource() {
        assertEquals("1145", TopicIdentity.code("sidewalk_parking"))
        assertEquals("5164", TopicIdentity.code("municipal:5164"))
        assertFalse(TopicIdentity.needsPlate("municipal:5164"))
        assertTrue(TopicIdentity.needsPlate("municipal:1145"))
        assertNull(TopicIdentity.code("municipal:../bad"))
    }
}
