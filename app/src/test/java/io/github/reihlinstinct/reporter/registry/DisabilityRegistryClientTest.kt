package io.github.reihlinstinct.reporter.registry

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DisabilityRegistryClientTest {
    private fun response(record: String) = MockResponse().setBody("""{"success":true,"result":{"records":[$record]}}""")
    @Test fun exactFreeReadOnlyFilterAndCentralTypeMap() {
        for ((code, type) in listOf("1" to DisabilityTagType.TRIANGLE, "2" to DisabilityTagType.WHEELCHAIR,
            "99" to DisabilityTagType.UNKNOWN, "null" to DisabilityTagType.UNKNOWN, "1.5" to DisabilityTagType.UNKNOWN, "\"1\"" to DisabilityTagType.UNKNOWN)) {
            MockWebServer().use { server ->
                server.enqueue(response("""{"MISPAR RECHEV":1234567,"SUG TAV":$code}"""))
                val client = DisabilityRegistryClient(server.url("/").toString())
                assertEquals(DisabilityLookupResult.Found(type), client.lookup("12-345-67"))
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertEquals(VehicleRegistryClient.SEARCH_PATH, request.requestUrl!!.encodedPath)
                assertEquals(DisabilityRegistryClient.RESOURCE, request.requestUrl!!.queryParameter("resource_id"))
                assertEquals("{\"MISPAR RECHEV\":1234567}", request.requestUrl!!.queryParameter("filters"))
                assertNull(request.getHeader("Authorization"))
                assertEquals(DisabilityLookupResult.Found(type), client.lookup("1234567"))
                assertEquals(1, server.requestCount)
            }
        }
    }
    @Test fun unavailableAmbiguousMalformedAndNotFoundStaySeparate() {
        MockWebServer().use { server ->
            val client = DisabilityRegistryClient(server.url("/").toString(), intervalMillis = 0)
            server.enqueue(response(""))
            assertEquals(DisabilityLookupResult.NotFound, client.lookup("1234567"))
            server.enqueue(response("""{"MISPAR RECHEV":9999999,"SUG TAV":1}"""))
            assertEquals(DisabilityLookupResult.Error(LookupErrorKind.MALFORMED_RESPONSE), client.lookup("1234568"))
            server.enqueue(MockResponse().setResponseCode(500))
            assertEquals(DisabilityLookupResult.Error(LookupErrorKind.HTTP), client.lookup("1234569"))
            server.enqueue(MockResponse().setBody("not json"))
            assertEquals(DisabilityLookupResult.Error(LookupErrorKind.MALFORMED_RESPONSE), client.lookup("1234570"))
            assertEquals(DisabilityLookupResult.Error(LookupErrorKind.INVALID_PLATE), client.lookup("12"))
            assertEquals(4, server.requestCount)
        }
    }
    @Test fun deniedRateLimitMakesNoSecondCallAndErrorsAreNotCached() {
        MockWebServer().use { server ->
            var now = 0L
            val client = DisabilityRegistryClient(server.url("/").toString(), clockMillis = { now })
            server.enqueue(MockResponse().setResponseCode(500))
            assertTrue(client.lookup("1234567") is DisabilityLookupResult.Error)
            assertEquals(DisabilityLookupResult.Error(LookupErrorKind.RATE_LIMITED), client.lookup("1234567"))
            now = 1000
            server.enqueue(response(""))
            assertEquals(DisabilityLookupResult.NotFound, client.lookup("1234567"))
            assertEquals(2, server.requestCount)
        }
    }
}
