package io.github.reihlinstinct.reporter

import io.github.reihlinstinct.reporter.municipal.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.Assert.*
import org.junit.Test

class ResponseDiagnosticClientTest {
    @Test fun networkDurationIsMeasuredBeforeThePrivateDiagnosticHook() {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())

            var hookMs = 0L
            val client = OkHttpMunicipalClient.forTesting(server.url("/"), "key", "user", "pass") {
                if (it.failure != null) {
                    val started = System.nanoTime()
                    Thread.sleep(300)
                    hookMs = (System.nanoTime() - started) / 1_000_000
                }
            }
            val started = System.nanoTime()
            val error = assertThrows(MunicipalApiException::class.java) { client.login() }
            val totalMs = (System.nanoTime() - started) / 1_000_000
            val networkMs = Regex("after ([0-9]+)ms").find(requireNotNull(error.message))!!.groupValues[1].toLong()
            assertTrue(hookMs >= 250)
            assertTrue("diagnostic latency must be excluded", totalMs - networkMs >= hookMs - 25)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun throwingDiagnosticNeverChangesSuccessfulCase() {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(MockResponse.Builder().body("""{"Success":true,"CaseIdInCRM":"case","WebCaseIdInCRM":"web"}""").build())
            val client = OkHttpMunicipalClient.forTesting(server.url("/"), "key", "user", "pass") { error("diagnostic failure") }
            assertEquals("case", client.createCase("session-token", linkedMapOf(), linkedMapOf())["CaseIdInCRM"])
        }
    }
    @Test fun failingHttpBodyStillReportsHttpStatus() {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(MockResponse.Builder().code(503).body("x".repeat(2000)).onResponseBody(SocketEffect.CloseSocket()).build())
            val client = OkHttpMunicipalClient.forTesting(server.url("/"), "key", "user", "pass") {}
            try { client.createCase("session-token", linkedMapOf(), linkedMapOf()); fail("must fail") }
            catch (e: MunicipalApiException) { assertEquals("CreateCase: HTTP 503.", e.message) }
        }
    }
    @Test fun successfulResponseScrubsKnownTokenAndKey() {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(MockResponse.Builder().body("""{"Success":true,"message":"session-token and subscription-secret","Token":"returned-token"}""").build())
            var recorded: ResponseDiagnostic? = null
            val client = OkHttpMunicipalClient.forTesting(server.url("/"), "subscription-secret", "user", "pass") { recorded = it }
            client.createCase("session-token", linkedMapOf(), linkedMapOf())
            assertEquals(200, recorded?.status)
            for (secret in listOf("session-token", "subscription-secret", "returned-token")) assertFalse(recorded!!.body.contains(secret))
        }
    }
}
