package il.sidewalks.reporter

import il.sidewalks.reporter.municipal.ResponseDiagnostics
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
    @Test fun unknownAndOversizeBodiesAreNotStored() {
        assertEquals("[body omitted: non-JSON]", ResponseDiagnostics.redactBody("password = rawsecret", emptyList()))
        assertEquals("[body omitted: size limit]", ResponseDiagnostics.redactBody("a".repeat(65_537), emptyList()))
    }
}
