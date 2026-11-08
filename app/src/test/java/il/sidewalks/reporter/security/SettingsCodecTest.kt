package il.sidewalks.reporter.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plaintext-shape tests only; encryption itself is Keystore-instrumented. */
class SettingsCodecTest {

    @Test
    fun roundtripPreservesMunicipalCredentials() {
        val settings = ReporterSettings("א", "ב", "050", "0000000", "a@b.invalid",
            municipalSubscriptionKey = "synthetic-key", municipalUsername = "synthetic-user",
            municipalPassword = "synthetic-pass")
        val decoded = SettingsCodec.decode(SettingsCodec.encode(settings))
        assertEquals(settings, decoded)
        assertTrue(decoded.hasMunicipalCredentials())
    }

    @Test
    fun oldSavesWithoutCredentialsDecodeToBlank() {
        val legacy = """{"firstName":"א","lastName":"ב","phone":"050","idNumber":"0000000","email":"a@b.invalid"}"""
        val decoded = SettingsCodec.decode(legacy.toByteArray(Charsets.UTF_8))
        assertEquals("", decoded.municipalSubscriptionKey)
        assertEquals("", decoded.municipalUsername)
        assertEquals("", decoded.municipalPassword)
        assertFalse(decoded.hasMunicipalCredentials())
    }

    @Test
    fun blankCredentialsAreOmittedFromJson() {
        val encoded = String(SettingsCodec.encode(ReporterSettings("א", "ב", "050", "0000000", "a@b.invalid")), Charsets.UTF_8)
        assertFalse(encoded.contains("municipal"))
    }

    @Test
    fun partialCredentialsAreNotComplete() {
        assertFalse(ReporterSettings("א", "ב", "050", "0000000", "a@b.invalid",
            municipalSubscriptionKey = "synthetic-key").hasMunicipalCredentials())
    }

    @Test
    fun credentialsNeverAppearInToString() {
        val settings = ReporterSettings("א", "ב", "050", "0000000", "a@b.invalid",
            municipalSubscriptionKey = "synthetic-key", municipalUsername = "synthetic-user",
            municipalPassword = "synthetic-pass")
        val rendered = settings.toString()
        assertFalse(rendered.contains("synthetic-key"))
        assertFalse(rendered.contains("synthetic-user"))
        assertFalse(rendered.contains("synthetic-pass"))
    }
}
