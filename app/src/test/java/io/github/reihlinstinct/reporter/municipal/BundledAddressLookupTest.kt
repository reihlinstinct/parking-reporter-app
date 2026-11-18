package io.github.reihlinstinct.reporter.municipal

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Required bundled default resolves a real Jerusalem street without provisioning. */
class BundledAddressLookupTest {
    @Test fun bundledLookupLoadsAndResolves() {
        val file = File("src/main/assets/addresses.json")
        org.junit.Assert.assertTrue("required bundled address asset missing", file.isFile)
        val directory = StreetDirectory.load(file.readText(Charsets.UTF_8))
        val resolved = directory.resolve("אגריפס", "12", null)
        org.junit.Assert.assertEquals("613", resolved.streetCode)
        org.junit.Assert.assertEquals("אגריפס", resolved.streetName)
        org.junit.Assert.assertEquals("12", resolved.house)
        org.junit.Assert.assertEquals("address_lookup", resolved.coordinateSource)
        // pyproj EPSG:2039 for the pipeline's own lookup point.
        assertTrue(kotlin.math.abs(resolved.x - 220579) <= 1)
        assertTrue(kotlin.math.abs(resolved.y - 632269) <= 1)
    }
}
