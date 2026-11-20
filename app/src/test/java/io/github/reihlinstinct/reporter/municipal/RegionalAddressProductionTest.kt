package io.github.reihlinstinct.reporter.municipal

import org.junit.Assert.*
import org.junit.Test

class RegionalAddressProductionTest {
    private fun index() = AddressShardIndex(PythonJson.dumps(mapOf("version" to 1,
        "streets" to listOf(mapOf("StreetCode" to "1", "StreetName" to "inside street"),
            mapOf("StreetCode" to "2", "StreetName" to "outside street")),
        "regions" to listOf(
            mapOf("id" to "covered", "sha256" to "a".repeat(64), "bbox" to listOf(35,31,36,32), "streetCodes" to listOf("1")),
            mapOf("id" to "outside", "sha256" to "b".repeat(64), "bbox" to null, "streetCodes" to listOf("2"))))))

    @Test fun gpsNearEdgeCannotHideOutsideCoverageCompetitor() {
        val calls = mutableListOf<String>()
        val covered = mapOf<String,Any?>(
            "polygons" to listOf(listOf(listOf(listOf(35.0,31.0),listOf(35.3,31.0),listOf(35.3,32.0),listOf(35.0,32.0),listOf(35.0,31.0)))))
        // Both points are near the fix, on opposite sides of the polygon edge.
        val inside = covered + ("addresses" to listOf(listOf(31.76,35.2999,"1","inside street")))
        val outside = mapOf<String,Any?>("addresses" to listOf(listOf(31.76,35.3001,"2","outside street")))
        @Suppress("UNCHECKED_CAST")
        val onlyInside = StreetDirectory.loadRows(index().streets, (inside["addresses"] as List<Any?>).asSequence())
        assertEquals("inside street", onlyInside.resolve(null,null,31.76 to 35.3).streetName)
        val completeRows = (inside["addresses"] as List<Any?>) + (outside["addresses"] as List<Any?>)
        val complete = StreetDirectory.loadRows(index().streets, completeRows.asSequence())
        assertThrows(MunicipalInputException::class.java) { complete.resolve(null,null,31.76 to 35.3) }
        val directory = index().loadForLocation(31.76,35.3) { id -> calls.add(id); if (id == "covered") inside else outside }
        assertEquals(listOf("covered", "outside"), calls)
        assertThrows(MunicipalInputException::class.java) { directory.resolve(null,null,31.76 to 35.3) }
        assertThrows(IllegalArgumentException::class.java) {
            index().loadForLocation(31.76,35.3001) { id -> if (id == "covered") inside else outside }
        }
    }

    @Test fun streetSuggestionsWorkFromProductionIndexWithoutAnyPointDocument() {
        val source = index()
        assertEquals(listOf("inside street"), source.suggestStreets("inside"))
        assertTrue(source.suggestStreets("i").isEmpty())
        assertTrue(source.suggestStreets("street",1).size <= 1)
        assertThrows(IllegalArgumentException::class.java) { source.suggestStreets("street",21) }
    }
}
