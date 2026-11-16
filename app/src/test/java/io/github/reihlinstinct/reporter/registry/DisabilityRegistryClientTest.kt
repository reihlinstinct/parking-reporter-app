package io.github.reihlinstinct.reporter.registry

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DisabilityRegistryClientTest {
    private fun response(record: String) = MockResponse.Builder().body("""{"success":true,"result":{"records":[$record]}}""").build()
    @Test fun exactFreeReadOnlyFilterAndCentralTypeMap() {
        for ((code, type) in listOf("1" to DisabilityTagType.TRIANGLE, "2" to DisabilityTagType.WHEELCHAIR,
            "99" to DisabilityTagType.UNKNOWN, "null" to DisabilityTagType.UNKNOWN, "1.5" to DisabilityTagType.UNKNOWN, "\"1\"" to DisabilityTagType.UNKNOWN)) {
            MockWebServer().apply { start() }.use { server ->
                server.enqueue(response("""{"MISPAR RECHEV":1234567,"SUG TAV":$code}"""))
                val client = DisabilityRegistryClient(server.url("/").toString())
                assertEquals(DisabilityLookupResult.Found(type), client.lookup("12-345-67"))
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertEquals(VehicleRegistryClient.SEARCH_PATH, request.url.encodedPath)
                assertEquals(DisabilityRegistryClient.RESOURCE, request.url.queryParameter("resource_id"))
                assertEquals("{\"MISPAR RECHEV\":1234567}", request.url.queryParameter("filters"))
                assertNull(request.headers["Authorization"])
                assertEquals(DisabilityLookupResult.Found(type), client.lookup("1234567"))
                assertEquals(1, server.requestCount)
            }
        }
    }
    @Test fun unavailableAmbiguousMalformedAndNotFoundStaySeparate() {
        MockWebServer().apply { start() }.use { server ->
            val client = DisabilityRegistryClient(server.url("/").toString(), intervalMillis = 0)
            server.enqueue(response(""))
            assertEquals(DisabilityLookupResult.NotFound, client.lookup("1234567"))
            server.enqueue(response("""{"MISPAR RECHEV":9999999,"SUG TAV":1}"""))
            assertEquals(DisabilityLookupResult.Error(LookupErrorKind.MALFORMED_RESPONSE), client.lookup("1234568"))
            server.enqueue(MockResponse.Builder().code(500).build())
            assertEquals(DisabilityLookupResult.Error(LookupErrorKind.HTTP), client.lookup("1234569"))
            server.enqueue(MockResponse.Builder().body("not json").build())
            assertEquals(DisabilityLookupResult.Error(LookupErrorKind.MALFORMED_RESPONSE), client.lookup("1234570"))
            assertEquals(DisabilityLookupResult.Error(LookupErrorKind.INVALID_PLATE), client.lookup("12"))
            assertEquals(4, server.requestCount)
        }
    }
    @Test fun deniedRateLimitMakesNoSecondCallAndErrorsAreNotCached() {
        MockWebServer().apply { start() }.use { server ->
            var now = 0L
            val client = DisabilityRegistryClient(server.url("/").toString(), clockMillis = { now })
            server.enqueue(MockResponse.Builder().code(500).build())
            assertTrue(client.lookup("1234567") is DisabilityLookupResult.Error)
            assertEquals(DisabilityLookupResult.Error(LookupErrorKind.RATE_LIMITED), client.lookup("1234567"))
            now = 1000
            server.enqueue(response(""))
            assertEquals(DisabilityLookupResult.NotFound, client.lookup("1234567"))
            assertEquals(2, server.requestCount)
        }
    }
}
