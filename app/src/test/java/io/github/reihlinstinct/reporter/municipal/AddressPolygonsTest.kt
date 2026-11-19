package io.github.reihlinstinct.reporter.municipal

import org.junit.Assert.*
import org.junit.Test

class AddressPolygonsTest {
    private fun square(west: Double, east: Double, south: Double = 31.0, north: Double = 32.0) = listOf(
        AddressPolygons.Point(west, south), AddressPolygons.Point(east, south),
        AddressPolygons.Point(east, north), AddressPolygons.Point(west, north),
        AddressPolygons.Point(west, south))
    private fun region(id: String, vararg rings: List<AddressPolygons.Point>) = AddressPolygons.Region(id, listOf(rings.toList()))

    @Test fun selectsLocallyWithoutSendingCoordinate() {
        val regions = listOf(region("a", square(34.0, 35.0)), region("b", square(35.0, 36.0)))
        assertEquals(setOf("a"), AddressPolygons.select(regions, 31.5, 34.5))
        assertEquals(setOf("b"), AddressPolygons.select(regions, 31.5, 35.5))
    }
    @Test fun sharedBoundarySelectsBothRegions() {
        val regions = listOf(region("a", square(34.0, 35.0)), region("b", square(35.0, 36.0)))
        assertEquals(setOf("a", "b"), AddressPolygons.select(regions, 31.5, 35.0))
    }
    @Test fun holeAndDisconnectedPartsAreHandled() {
        val main = square(34.0, 36.0)
        val hole = square(34.5, 35.5, 31.25, 31.75)
        val region = AddressPolygons.Region("a", listOf(listOf(main, hole), listOf(square(37.0, 38.0))))
        assertTrue(AddressPolygons.select(listOf(region), 31.5, 35.0).isEmpty())
        assertEquals(setOf("a"), AddressPolygons.select(listOf(region), 31.5, 37.5))
        assertEquals(setOf("a"), AddressPolygons.select(listOf(region), 31.25, 35.0))
    }
    @Test fun outsideDoesNotInventRegion() {
        assertTrue(AddressPolygons.select(listOf(region("a", square(34.0, 35.0))), 30.0, 33.0).isEmpty())
    }
    @Test fun invalidFixAndInvalidRingRefuse() {
        assertThrows(IllegalArgumentException::class.java) { AddressPolygons.select(emptyList(), Double.NaN, 35.0) }
        assertThrows(IllegalArgumentException::class.java) { AddressPolygons.select(emptyList(), 91.0, 35.0) }
        assertThrows(IllegalArgumentException::class.java) { AddressPolygons.select(listOf(region("a", emptyList())), 31.5, 35.0) }
    }
}
