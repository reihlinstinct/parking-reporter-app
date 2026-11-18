package io.github.reihlinstinct.reporter

import io.github.reihlinstinct.reporter.municipal.ResponseDiagnostics
import org.junit.Assert.*
import org.junit.Test

class ResponseDiagnosticsTest {
    @Test fun nestedSecretsAndActualValuesNeverSurvive() {
        val raw = """{"Success":false,"Token":"abc","nested":{"Password":"def","message":"known-secret"},"items":[{"Authorization":"Bearer xyz"}],"CaseNumberInCRM":"2000-0000000"}"""
        val redacted = ResponseDiagnostics.redactBody(raw, listOf("known-secret"))
        for (secret in listOf("abc", "def", "xyz", "known-secret")) assertFalse(secret, redacted.contains(secret))
        assertTrue(redacted.contains("2000-0000000"))
        assertTrue(redacted.contains("false"))
    }
    @Test fun lineTerminatorsInSensitiveKeysCannotBypassRedaction() {
        val input = org.json.JSONObject().put("\nToken", "NL-SECRET").put("\rPassword", "CR-SECRET").put("\u2028Token", "LS-SECRET")
        val redacted = ResponseDiagnostics.redactBody(input.toString(), emptyList())
        for (secret in listOf("NL-SECRET", "CR-SECRET", "LS-SECRET")) assertFalse(secret, redacted.contains(secret))
    }
    @Test fun sensitiveObjectsAndLongNumbersAreScrubbedWithoutErasingBooleanStatus() {
        val raw = """{"Token":{"v":"OBJ-1","nested":["ARR-1",987654321,true]},"echo":"OBJ-1 ARR-1 987654321 true","numeric":987654321,"boolean":true}"""
        val redacted = ResponseDiagnostics.redactBody(raw, emptyList())
        for (secret in listOf("OBJ-1", "ARR-1", "987654321")) assertFalse(secret, redacted.contains(secret))
    }
    @Test fun shortSensitiveNumbersDoNotCorruptUnrelatedDatesOrCounts() {
        val redacted = org.json.JSONObject(ResponseDiagnostics.redactBody(
            """{"Token":0,"Password":1,"date":"2026-11-18","count":10,"status":200}""", emptyList()))
        assertEquals("[REDACTED]", redacted.getString("Token"))
        assertEquals("[REDACTED]", redacted.getString("Password"))
        assertEquals("2026-11-18", redacted.getString("date"))
        assertEquals(10, redacted.getInt("count"))
        assertEquals(200, redacted.getInt("status"))
        val echoed = org.json.JSONObject(ResponseDiagnostics.redactBody(
            """{"Token":1,"echo":1,"textEcho":"1","date":"2026-11-18","count":10,"Password":false,"Success":false}""", emptyList()))
        assertEquals("[REDACTED]", echoed.getString("echo"))
        assertEquals("[REDACTED]", echoed.getString("textEcho"))
        assertEquals("2026-11-18", echoed.getString("date"))
        assertEquals(10, echoed.getInt("count"))
        assertFalse(echoed.getBoolean("Success"))
        // Explicitly supplied credentials still redact even when short.
        assertFalse(ResponseDiagnostics.redactBody("""{"echo":"x1x"}""", listOf("1")).contains("x1x"))
    }
    @Test fun longestSecretIsRemovedBeforeItsPrefix() {
        assertFalse(ResponseDiagnostics.redactBody("""{"message":"abcdef"}""", listOf("abcd", "abcdef")).contains("ef"))
    }
    @Test fun unknownAndOversizeBodiesAreNotStored() {
        assertEquals("[body omitted: non-JSON]", ResponseDiagnostics.redactBody("password = rawsecret", emptyList()))
        assertEquals("[body omitted: size limit]", ResponseDiagnostics.redactBody("a".repeat(65_537), emptyList()))
    }
}
