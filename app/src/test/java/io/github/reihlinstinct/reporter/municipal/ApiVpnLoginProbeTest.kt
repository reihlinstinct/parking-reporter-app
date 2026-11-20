package io.github.reihlinstinct.reporter.municipal

import org.junit.Assert.*
import org.junit.Test
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class ApiVpnLoginProbeTest {
    private class Client(private val outcome: String? = "synthetic-token") : MunicipalClient {
        var logins = 0
        override fun login(): String { logins++; return outcome ?: error("synthetic auth error") }
        override fun upsertContact(token: String, contact: LinkedHashMap<String, Any?>): Map<String, Any?> = error("unexpected write")
        override fun createCase(token: String, contact: LinkedHashMap<String, Any?>, report: LinkedHashMap<String, Any?>): Map<String, Any?> = error("unexpected write")
        override fun attachPhoto(token: String, caseIdInCRM: String, webCaseIdInCRM: String, fileName: String, jpeg: ByteArray) = error("unexpected write")
    }

    @Test fun successOnlyCallsLoginOnce() {
        val client = Client()
        assertTrue(ApiVpnLoginProbe.probe(client))
        assertEquals(1, client.logins)
    }
    @Test fun blankTokenNeverCountsAsSuccess() {
        val client = Client(" ")
        assertFalse(ApiVpnLoginProbe.probe(client))
        assertEquals(1, client.logins)
    }
    @Test fun failedLoginIsNotRetriedOrConvertedToWrite() {
        val client = Client(null)
        assertThrows(IllegalStateException::class.java) { ApiVpnLoginProbe.probe(client) }
        assertEquals(1, client.logins)
    }
    @Test fun realTransportMockOnlyMakesOneLoginRequestAndNoWriteOn403() {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(MockResponse.Builder().code(403).body("synthetic refusal").build())
            val client = OkHttpMunicipalClient.forTesting(server.url("/synthetic/"),
                "synthetic-key", "synthetic-user", "synthetic-password")
            assertThrows(MunicipalApiException::class.java) { ApiVpnLoginProbe.probe(client) }
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/synthetic/Login", request.url.encodedPath)
        }
    }
    @Test fun realTransportLoginSuccessDoesNotAttemptWriteProof() {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(MockResponse.Builder().body("""{"Success":true,"Token":"synthetic-token"}""").build())
            val client = OkHttpMunicipalClient.forTesting(server.url("/synthetic/"),
                "synthetic-key", "synthetic-user", "synthetic-password")
            assertTrue(ApiVpnLoginProbe.probe(client))
            assertEquals(1, server.requestCount)
            assertEquals("/synthetic/Login", server.takeRequest().url.encodedPath)
        }
    }
}
