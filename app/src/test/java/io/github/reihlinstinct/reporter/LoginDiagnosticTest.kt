package io.github.reihlinstinct.reporter

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class LoginDiagnosticTest {
    private val credentials = LoginCredentials("synthetic-key", "synthetic-user", "synthetic-password")
    @Test fun successOnlyCallsLoginAndDiscardsToken() {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(MockResponse.Builder().body("""{"Success":true,"Token":"synthetic-token"}""").build())
            val diagnostic = LoginDiagnostic(LoginDiagnostic.safeClient(), server.url("/Login").toString())
            val result = diagnostic.run(credentials, true)
            assertEquals(DiagnosticOutcome.SUCCESS, result.outcome)
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("/Login", request.target)
            assertEquals("POST", request.method)
            assertFalse(result.toString().contains("synthetic-token"))
        }
    }
    @Test fun redirectNotFollowedOrRetried() {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(MockResponse.Builder().code(307).addHeader("Location", server.url("/CreateCase")).build())
            val result = LoginDiagnostic(LoginDiagnostic.safeClient(), server.url("/Login").toString()).run(credentials, true)
            assertEquals(DiagnosticOutcome.DENIED, result.outcome)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun malformedAndOversizedResponseFailClosed() {
        for (body in listOf("{}", "not-json", "x".repeat(70000))) {
            MockWebServer().apply { start() }.use { server ->
                server.enqueue(MockResponse.Builder().body(body).build())
                val result = LoginDiagnostic(LoginDiagnostic.safeClient(), server.url("/Login").toString()).run(credentials, true)
                assertNotEquals(DiagnosticOutcome.SUCCESS, result.outcome)
                assertEquals(1, server.requestCount)
            }
        }
    }
    @Test fun missingCredentialsAndWrongNetworkMakeNoCall() {
        MockWebServer().apply { start() }.use { server ->
            val diagnostic = LoginDiagnostic(LoginDiagnostic.safeClient(), server.url("/Login").toString())
            assertEquals(DiagnosticOutcome.NOT_MOBILE, diagnostic.run(credentials, false).outcome)
            val missing = diagnostic.run(LoginCredentials("", "", ""), true)
            assertEquals(DiagnosticOutcome.MISSING_CREDENTIALS, missing.outcome)
            assertEquals(listOf("subscription_key", "api_username", "api_password"), missing.missingFields)
            assertEquals(listOf("api_password"),
                diagnostic.run(LoginCredentials("synthetic-key", "synthetic-user", ""), true).missingFields)
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun tokenOrExplicitSuccessMissingNeverSucceeds() {
        for (body in listOf("""{"Success":false,"Token":"synthetic"}""", """{"Success":true}""", """{"Success":"true","Token":"synthetic"}""")) {
            MockWebServer().apply { start() }.use { server ->
                server.enqueue(MockResponse.Builder().body(body).build())
                val result = LoginDiagnostic(LoginDiagnostic.safeClient(), server.url("/Login").toString()).run(credentials, true)
                assertEquals(DiagnosticOutcome.INVALID_RESPONSE, result.outcome)
                assertEquals(1, server.requestCount)
            }
        }
    }
    @Test fun credentialStringIsAlwaysRedacted() {
        assertEquals("LoginCredentials(redacted)", credentials.toString())
    }
    @Test fun deniedResponseCannotExposeServerBody() {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(MockResponse.Builder().code(403).body("private server detail").build())
            val result = LoginDiagnostic(LoginDiagnostic.safeClient(), server.url("/Login").toString()).run(credentials, true)
            assertEquals(403, result.httpStatus)
            assertFalse(result.toString().contains("private"))
        }
    }
}
