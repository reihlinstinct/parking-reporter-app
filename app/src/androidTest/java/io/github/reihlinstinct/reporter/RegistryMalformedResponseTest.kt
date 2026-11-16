package io.github.reihlinstinct.reporter

import io.github.reihlinstinct.reporter.registry.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Platform org.json.JSONException is checked; JVM org.json differs. No real registry access. */
class RegistryMalformedResponseTest {
    @Test fun platformJsonFailuresReturnTypedErrorsForBothRegistryClients() {
        assertFalse(RuntimeException::class.java.isAssignableFrom(org.json.JSONException::class.java))
        for (body in listOf("not json", "{}", "{\"success\":true}",
            "{\"success\":true,\"result\":{}}", "{\"success\":true,\"result\":{\"records\":[42]}}")) {
            MockWebServer().apply { start() }.use { server ->
                server.enqueue(MockResponse.Builder().body(body).build())
                server.enqueue(MockResponse.Builder().body(body).build())
                val disability = DisabilityRegistryClient(server.url("/").toString())
                val vehicle = VehicleRegistryClient(VehicleRegistryConfig(
                    VehicleRegistryConfig.KNOWN_VEHICLE_RESOURCE_ID, baseUrl = server.url("/").toString()))
                val expected = if (body == "{}") LookupErrorKind.API_FAILURE else LookupErrorKind.MALFORMED_RESPONSE
                assertEquals(DisabilityLookupResult.Error(expected), disability.lookup("1234567"))
                assertEquals(VehicleLookupResult.Error(expected), vehicle.lookup("1234567"))
                assertEquals(2, server.requestCount)
            }
        }
    }
}
