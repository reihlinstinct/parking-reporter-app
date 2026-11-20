package io.github.reihlinstinct.reporter.municipal

import org.junit.Assert.*
import org.junit.Test

class AddressShardIndexTest {
    private val point = listOf(listOf(34,31),listOf(36,31),listOf(36,32),listOf(34,32),listOf(34,31))
    private fun index(ambiguous: Boolean = false): AddressShardIndex {
        val streets = mutableListOf<Map<String,Any?>>(mapOf("StreetCode" to "1", "StreetName" to "synthetic street", "aliases" to listOf("alias")))
        if (ambiguous) streets.add(mapOf("StreetCode" to "2", "StreetName" to "alias"))
        val data = mapOf("version" to 1, "streets" to streets, "regions" to listOf("a","b").map { id ->
            mapOf("id" to id,"sha256" to "a".repeat(64),"bbox" to listOf(34,31,36,32),"streetCodes" to listOf("1"))
        })
        return AddressShardIndex(PythonJson.dumps(data))
    }
    @Test fun oneStreetRequiresEveryContributingRegion() {
        assertEquals(setOf("a","b"),index().forStreets(listOf("synthetic street")))
        assertEquals(setOf("a","b"),index().forStreets(listOf("alias")))
    }
    @Test fun globalAliasAmbiguityCannotDisappearInPartialData() {
        assertThrows(IllegalArgumentException::class.java) { index(true).forStreets(listOf("alias")) }
    }
    @Test fun missingStreetRefuses() {
        assertThrows(IllegalArgumentException::class.java) { index().forStreets(listOf("unknown")) }
    }
    @Test fun coordinatesSelectOnlyLocalPolygonIds() {
        assertEquals(setOf("a","b"),index().candidates(31.5,35.0))
        assertTrue(index().candidates(30.0,33.0).isEmpty())
    }
}
