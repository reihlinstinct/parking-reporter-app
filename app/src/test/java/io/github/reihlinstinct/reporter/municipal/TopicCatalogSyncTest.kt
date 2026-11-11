package io.github.reihlinstinct.reporter.municipal

import io.github.reihlinstinct.reporter.core.CityTopicCache
import io.github.reihlinstinct.reporter.core.MunicipalSubject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class TopicCatalogSyncTest {
    private class Client : MunicipalClient {
        var logins = 0; var reads = 0; var tokenSeen = ""; var fail = false
        var rows = listOf(MunicipalSubject("5164", "אחר", listOf("גינון", "אחר")))
        override fun login(): String { logins++; return "synthetic-token" }
        override fun subjectCategories(token: String): List<MunicipalSubject> {
            reads++; tokenSeen = token
            if (fail) throw MunicipalApiException("synthetic failure")
            return rows
        }
        override fun upsertContact(token: String, contact: LinkedHashMap<String, Any?>): Map<String, Any?> = error("must not write")
        override fun createCase(token: String, contact: LinkedHashMap<String, Any?>, report: LinkedHashMap<String, Any?>): Map<String, Any?> = error("must not submit")
        override fun attachPhoto(token: String, caseIdInCRM: String, webCaseIdInCRM: String, fileName: String, jpeg: ByteArray) = error("must not attach")
    }
    @Test fun bootstrapIsOnceAndOfflineReadNeverCreatesAClient() {
        val dir = Files.createTempDirectory("topic-bootstrap").toFile()
        try {
            val cache = CityTopicCache(java.io.File(dir, "catalog.json")); val sync = TopicCatalogSync(cache); val client = Client()
            assertEquals(client.rows, sync.initial { client }.topics)
            assertEquals(1, client.logins); assertEquals(1, client.reads)
            assertEquals(client.rows, sync.initial { error("offline must not create client") }.topics)
            assertEquals(1, client.reads)
        } finally { dir.deleteRecursively() }
    }
    @Test fun successfulSubmissionRefreshesOnlyAfterSevenDaysAndReusesToken() {
        val dir = Files.createTempDirectory("topic-refresh").toFile()
        try {
            var now = Instant.parse("2026-11-11T00:00:00Z")
            val cache = CityTopicCache(java.io.File(dir, "catalog.json")) { now }
            val sync = TopicCatalogSync(cache) { now }; val client = Client()
            sync.initial { client }
            now = now.plusSeconds(7*86400L)
            sync.afterSuccess(client, "existing-submit-session")
            assertEquals(1, client.reads)
            now = now.plusSeconds(1)
            client.rows = listOf(MunicipalSubject("5164", "שם חדש", listOf("גינון", "שם חדש")))
            sync.afterSuccess(client, "existing-submit-session")
            assertEquals(2, client.reads); assertEquals(1, client.logins)
            assertEquals("existing-submit-session", client.tokenSeen)
            assertEquals("שם חדש", cache.read()!!.topics.single().name)
            now = now.plusSeconds(8*86400L); client.fail = true
            sync.afterSuccess(client, "existing-submit-session")
            assertEquals("שם חדש", cache.read()!!.topics.single().name)
            assertEquals(1, client.logins)
        } finally { dir.deleteRecursively() }
    }
}
