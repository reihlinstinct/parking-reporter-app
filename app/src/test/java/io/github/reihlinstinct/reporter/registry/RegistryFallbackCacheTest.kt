package io.github.reihlinstinct.reporter.registry

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate

class RegistryFallbackCacheTest {
    private val plate = PlateNumber.parse("1234567")!!
    private val tag = DisabilityLookupResult.Found(DisabilityTagType.TRIANGLE)
    private fun vehicle(until: LocalDate? = null) = VehicleLookupResult.Found(VehicleRecord(plate, until, null, "must not persist", "model", "colour", 2000))
    @Test fun persistedMinimalRecordSurvivesRecreationAndHitsDoNotExtendSevenDays() {
        val dir = Files.createTempDirectory("registry-cache-test").toFile()
        try {
            var now = Instant.parse("2026-11-12T08:00:00Z")
            val cache = RegistryFallbackCache(dir) { now }
            cache.put(plate, tag, vehicle(), now)
            val text = dir.listFiles()!!.single().readText()
            assertFalse(text.contains("1234567")); assertFalse(text.contains("model")); assertFalse(text.contains("must not persist"))
            now = now.plusSeconds(6 * 86400L)
            val other = RegistryFallbackCache(dir) { now }
            assertEquals(DisabilityTagType.TRIANGLE, (other.get(plate)!!.tag as DisabilityLookupResult.Found).type)
            other.put(plate, tag, vehicle(), now) // live refresh does not slide insertion deadline
            now = now.plusSeconds(86400)
            assertNull(other.get(plate))
        } finally { dir.deleteRecursively() }
    }
    @Test fun expiresAtJerusalemMidnightAfterValidThroughAndErrorsDontOverwrite() {
        val dir = Files.createTempDirectory("registry-cache-test").toFile()
        try {
            var now = Instant.parse("2026-11-12T20:00:00Z")
            val cache = RegistryFallbackCache(dir) { now }
            cache.put(plate, tag, vehicle(LocalDate.of(2026,11,12)), now)
            cache.put(plate, DisabilityLookupResult.Error(LookupErrorKind.NETWORK), VehicleLookupResult.NotFound, now)
            now = Instant.parse("2026-11-12T21:59:59Z"); assertNotNull(cache.get(plate))
            now = Instant.parse("2026-11-12T22:00:00Z"); assertNull(cache.get(plate))
            cache.put(plate, tag, vehicle(LocalDate.of(2026,11,11)), now); assertNull(cache.get(plate))
        } finally { dir.deleteRecursively() }
    }
    @Test fun corruptionAndFutureClockFailClosed() {
        val dir = Files.createTempDirectory("registry-cache-test").toFile()
        try {
            var now = Instant.parse("2026-11-12T08:00:00Z")
            val cache = RegistryFallbackCache(dir) { now }; cache.put(plate, tag, vehicle(), now)
            now = now.minusSeconds(1); assertNull(cache.get(plate))
            now = now.plusSeconds(2); cache.put(plate, tag, vehicle(), now)
            dir.listFiles()!!.single().writeText("broken"); assertNull(cache.get(plate))
        } finally { dir.deleteRecursively() }
    }
    @Test fun boundedTo128AndValidNegativeFindingsCanBeReadBack() {
        val dir = Files.createTempDirectory("registry-cache-test").toFile()
        try {
            val now = Instant.parse("2026-11-12T08:00:00Z")
            val cache = RegistryFallbackCache(dir) { now }
            repeat(129) { i -> cache.put(PlateNumber.parse((1234500 + i).toString())!!,
                DisabilityLookupResult.NotFound, VehicleLookupResult.NotFound, now) }
            assertEquals(128, dir.listFiles()!!.size)
            val last = cache.get(PlateNumber.parse("1234628")!!)!!
            assertEquals(DisabilityLookupResult.NotFound, last.tag)
            assertEquals(VehicleLookupResult.NotFound, last.vehicle)
        } finally { dir.deleteRecursively() }
    }

}
