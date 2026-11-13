package io.github.reihlinstinct.reporter.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentLocationTest {
    @Test
    fun acceptsAnInRangeFix() {
        assertEquals(DeviceFix(31.7683, 35.2137), CurrentLocation.validFix(31.7683, 35.2137))
    }

    @Test
    fun rejectsNonFiniteOutOfRangeAndZeroZero() {
        assertNull(CurrentLocation.validFix(Double.NaN, 35.0))
        assertNull(CurrentLocation.validFix(31.0, Double.POSITIVE_INFINITY))
        assertNull(CurrentLocation.validFix(91.0, 35.0))
        assertNull(CurrentLocation.validFix(31.0, 181.0))
        assertNull(CurrentLocation.validFix(0.0, 0.0))
    }

    @Test
    fun usesGpsBeforeNetworkAndSkipsProvidersThatAreOff() {
        assertEquals(listOf("gps", "network"), CurrentLocation.providerOrder(listOf("network", "gps", "passive")))
        assertEquals(listOf("network"), CurrentLocation.providerOrder(listOf("network", "passive")))
        assertEquals(emptyList<String>(), CurrentLocation.providerOrder(emptyList()))
    }

    @Test
    fun offersTheButtonOnlyForAFreshCapture() {
        val now = java.time.Instant.parse("2026-11-13T10:00:00Z")
        val zone = java.time.ZoneId.of("Asia/Jerusalem")
        assertEquals(true, CurrentLocation.freshCapture("2026-11-13T11:55:00+02:00", now, zone))
        assertEquals(true, CurrentLocation.freshCapture("2026-11-13T11:55:00", now, zone))
        assertEquals(false, CurrentLocation.freshCapture("2026-11-13T11:30:00+02:00", now, zone))
        assertEquals(false, CurrentLocation.freshCapture("2026-11-13T11:30:00", now, zone))
        assertEquals(false, CurrentLocation.freshCapture("2026-11-13T13:00:00+02:00", now, zone))
        assertEquals(false, CurrentLocation.freshCapture(null, now, zone))
        assertEquals(false, CurrentLocation.freshCapture("not a time", now, zone))
    }
}
