package io.github.reihlinstinct.reporter.municipal

import kotlin.math.abs

/** WGS84 polygon routing only. Coordinates stay local; the result is an opaque shard ID. */
object AddressPolygons {
    data class Point(val longitude: Double, val latitude: Double)
    data class Region(val id: String, val polygons: List<List<List<Point>>>)

    fun select(regions: List<Region>, latitude: Double, longitude: Double): Set<String> {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        val point = Point(longitude, latitude)
        return regions.filter { region -> region.polygons.any { contains(it, point) } }.map { it.id }.toSet()
    }

    /** Outer and hole boundaries are included, so shared edges never silently select one region. */
    private fun contains(rings: List<List<Point>>, point: Point): Boolean {
        require(rings.isNotEmpty())
        if (rings.any { onBoundary(it, point) }) return true
        return inside(rings.first(), point) && rings.drop(1).none { inside(it, point) }
    }

    private fun onBoundary(ring: List<Point>, p: Point): Boolean {
        validate(ring)
        return ring.indices.any { index ->
            val a = ring[index]
            val b = ring[(index + 1) % ring.size]
            val cross = (p.latitude - a.latitude) * (b.longitude - a.longitude) -
                (p.longitude - a.longitude) * (b.latitude - a.latitude)
            abs(cross) <= 1e-12 && p.longitude >= minOf(a.longitude, b.longitude) - 1e-12 &&
                p.longitude <= maxOf(a.longitude, b.longitude) + 1e-12 &&
                p.latitude >= minOf(a.latitude, b.latitude) - 1e-12 &&
                p.latitude <= maxOf(a.latitude, b.latitude) + 1e-12
        }
    }

    private fun inside(ring: List<Point>, p: Point): Boolean {
        validate(ring)
        var result = false
        for (index in ring.indices) {
            val a = ring[index]
            val b = ring[(index + 1) % ring.size]
            if ((a.latitude > p.latitude) != (b.latitude > p.latitude) &&
                p.longitude < (b.longitude - a.longitude) * (p.latitude - a.latitude) /
                (b.latitude - a.latitude) + a.longitude) result = !result
        }
        return result
    }

    private fun validate(ring: List<Point>) {
        require(ring.size >= 3 && ring.distinct().size >= 3)
        require(ring.all { it.latitude.isFinite() && it.longitude.isFinite() &&
            it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 })
    }
}
