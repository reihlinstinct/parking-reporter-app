package io.github.reihlinstinct.reporter.municipal

import java.text.Normalizer
import kotlin.math.hypot

/**
 * Jerusalem street/address resolution, a faithful port of the helper
 * pipeline's api106.Addresses (helper 0.6.0, pinned 51dd5c0): same guards,
 * same distances, same error semantics, so an address the pipeline accepts is
 * an address the app accepts. One deliberate divergence (#483): explicit
 * street+house lookups also use address points whose short name is the tail of
 * exactly one street name (exact points win; tail points are a bounded
 * fallback); the helper has no such rule. Data is the pipeline's own
 * version-1 lookup file (streets + address points), bundled as an asset.
 */
class StreetDirectory private constructor(
    private val streets: Map<String, String>,
    private val aliases: Map<String, Set<String>>,
    private val points: List<Point>,
) {
    private data class Point(val x: Double, val y: Double, val house: String, val code: String?, val name: String, val latitude: Double, val longitude: Double,
        /** Street the point belongs to when its short name is the tail words of exactly one street name (e.g. point "פייר קניג", street "גנרל פייר קניג"). Used only for explicit street+house lookups. */
        val tailCode: String? = null)

    data class Resolved(
        val streetCode: String,
        val streetName: String,
        val house: String,
        val x: Int,
        val y: Int,
        val coordinateSource: String,
    )

    data class ConfirmableAddress(val address: String, val latitude: Double, val longitude: Double)

    /** Offline explicit address lookup, never device location or nearest-house guessing. */
    fun findConfirmedAddress(street: String, house: String): ConfirmableAddress {
        val resolved = resolve(street, house, null)
        val matches = housePointsFor(resolved.streetCode, resolved.house)
        require(matches.isNotEmpty())
        val point = matches.first()
        if (matches.any { hypot(it.x - point.x, it.y - point.y) > 1.0 })
            throw MunicipalInputException("This address has multiple location points. Confirm another exact address.")
        return ConfirmableAddress("${resolved.streetName} ${resolved.house}", point.latitude, point.longitude)
    }

    /** Nearest numbered address to the crossing of two streets; [streetNameA]/[streetNameB] are the crossing streets. */
    data class IntersectionAddress(
        val address: String, val latitude: Double, val longitude: Double,
        val streetNameA: String, val streetNameB: String,
    )

    /**
     * Offline lookup for a spot at the crossing of two streets (the city form only takes a street and
     * house number). The crossing is estimated from where the two streets' own address points come
     * closest; the result is the nearest numbered address of either street to that estimate. Refuses
     * unknown or ambiguous names, streets that do not meet, and streets that meet in several places.
     */
    fun findIntersectionAddress(streetA: String, streetB: String): IntersectionAddress {
        val codeA = aliases[normalize(streetA)]?.singleOrNull()
        val codeB = aliases[normalize(streetB)]?.singleOrNull()
        if (codeA == null || codeB == null || codeA == codeB) {
            throw MunicipalInputException("Street name has no unique municipal match. Use the exact Hebrew street names.")
        }
        val pointsA = points.filter { it.code == codeA }
        val pointsB = points.filter { it.code == codeB }
        val near = ArrayList<Pair<Point, Point>>()
        for (a in pointsA) for (b in pointsB) {
            if (hypot(a.x - b.x, a.y - b.y) <= INTERSECTION_PAIR_MAX_M) near.add(a to b)
        }
        val best = near.minByOrNull { (a, b) -> hypot(a.x - b.x, a.y - b.y) }
            ?: throw MunicipalInputException("These streets do not meet in the address data. Check the street names.")
        fun mid(pair: Pair<Point, Point>) = (pair.first.x + pair.second.x) / 2 to (pair.first.y + pair.second.y) / 2
        val (mx, my) = mid(best)
        if (near.any { val (x, y) = mid(it); hypot(x - mx, y - my) > INTERSECTION_SINGLE_AREA_M }) {
            throw MunicipalInputException("These streets meet in more than one place. Confirm an exact address.")
        }
        // The midpoint is equally far from both points of the closest pair, so no other address is nearer;
        // pick between the two deterministically (lower street code) so the order of the names never matters.
        val nearest = listOf(best.first, best.second).minByOrNull { it.code!!.toLongOrNull() ?: Long.MAX_VALUE }!!
        return IntersectionAddress("${streets[nearest.code!!]} ${nearest.house}", nearest.latitude, nearest.longitude,
            streets[codeA]!!, streets[codeB]!!)
    }

    /**
     * Points of a street. Exact-coded points always win. Tail-named points (see [Point.tailCode])
     * are a fallback: used alone when the street has no exact point, otherwise only when within
     * [TAIL_MAX_DISTANCE_M] of the street's exact points.
     */
    private fun streetPointsFor(streetCode: String): List<Point> {
        val exact = points.filter { it.code == streetCode }
        val tails = points.filter { it.code == null && it.tailCode == streetCode }
        if (exact.isEmpty()) return tails
        return exact + tails.filter { t -> exact.any { e -> hypot(t.x - e.x, t.y - e.y) <= TAIL_MAX_DISTANCE_M } }
    }

    /** Exact street+house points when any exist (behavior unchanged), else the bounded tail fallback. */
    private fun housePointsFor(streetCode: String, house: String): List<Point> {
        val wanted = normalize(house)
        val exact = points.filter { it.code == streetCode && normalize(it.house) == wanted }
        if (exact.isNotEmpty()) return exact
        return streetPointsFor(streetCode).filter { it.code == null && normalize(it.house) == wanted }
    }

    private fun normalize(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .map { HEBREW_FINALS[it] ?: it }
        return folded.filter { it.isLetterOrDigit() }.joinToString("")
    }

    /**
     * Resolves like the helper: an explicit street+house wins when it
     * uniquely matches; otherwise the photo GPS picks the nearest clear
     * address. Every ambiguous or distant case throws MunicipalInputException
     * with the helper's guidance instead of guessing.
     */
    fun resolve(streetName: String?, house: String?, gps: Pair<Double, Double>?): Resolved {
        val gpsXy = gps?.let { (lat, lon) ->
            val (x, y) = Itm.toItm(lat, lon)
            x.toDouble() to y.toDouble()
        }
        var code: String
        var resolvedHouse: String
        var x: Double
        var y: Double
        var source: String
        if (!streetName.isNullOrBlank()) {
            val codes = aliases[normalize(streetName)] ?: emptySet()
            if (codes.size != 1) {
                throw MunicipalInputException(
                    "Street name has no unique municipal match. Use the exact Hebrew street name.")
            }
            code = codes.single()
            resolvedHouse = house?.trim() ?: ""
            if (!HOUSE_NUMBER.matches(resolvedHouse) || resolvedHouse.length > HOUSE_NUMBER_MAX_LENGTH) {
                throw MunicipalInputException("Invalid house number.")
            }
            val housePoints = housePointsFor(code, resolvedHouse)
            if (gpsXy != null) {
                x = gpsXy.first; y = gpsXy.second
                if (housePoints.isNotEmpty() &&
                    housePoints.minOf { p -> hypot(p.x - x, p.y - y) } > 200) {
                    throw MunicipalInputException(
                        "The supplied address is more than 200 m from the photo GPS. Check the address.")
                }
                if (housePoints.isEmpty()) {
                    val streetPoints = streetPointsFor(code)
                    if (streetPoints.isNotEmpty() &&
                        streetPoints.minOf { p -> hypot(p.x - x, p.y - y) } > 200) {
                        throw MunicipalInputException(
                            "The supplied street is more than 200 m from the photo GPS. Check the street.")
                    }
                }
                source = "photo_gps"
            } else {
                if (housePoints.isEmpty()) {
                    throw MunicipalInputException(
                        "Photo has no usable GPS and this house number is absent from the address data.")
                }
                x = housePoints[0].x; y = housePoints[0].y
                if (housePoints.any { p -> hypot(p.x - x, p.y - y) > 100 }) {
                    throw MunicipalInputException(
                        "This address has multiple locations. Use a photo with GPS.")
                }
                source = "address_lookup"
            }
        } else {
            if (gpsXy == null) {
                throw MunicipalInputException(
                    "Photo has no usable GPS. Supply an address.")
            }
            x = gpsXy.first; y = gpsXy.second
            val nearby = mutableListOf<Triple<Double, Point, String>>()
            val seen = mutableSetOf<Pair<String, String>>()
            for (p in points.sortedBy { p -> (p.x - x) * (p.x - x) + (p.y - y) * (p.y - y) }) {
                val distance = hypot(p.x - x, p.y - y)
                if (distance > 150) break
                val identity = (p.code ?: normalize(p.name)) to p.house
                if (identity in seen) continue
                seen.add(identity)
                nearby.add(Triple(distance, p, p.name))
                if (nearby.size == 5) break
            }
            if (nearby.isEmpty() || nearby[0].second.code == null || nearby[0].first > 35 ||
                (nearby.size > 1 && nearby[1].first - nearby[0].first < 8)) {
                val choices = nearby.joinToString("; ") { (d, p, name) ->
                    "${p.code?.let { streets[it] } ?: name} ${p.house} (${d.toInt()} m)" }
                throw MunicipalInputException(
                    "GPS does not identify one clear address. Supply an address. Nearby: " +
                        choices.ifEmpty { "none" })
            }
            code = nearby[0].second.code!!
            resolvedHouse = nearby[0].second.house
            source = "photo_gps"
        }
        return Resolved(code, streets[code]!!, resolvedHouse, x.toInt(), y.toInt(), source)
    }

    companion object {
        private const val TAIL_MAX_DISTANCE_M = 300.0
        private const val INTERSECTION_PAIR_MAX_M = 60.0
        private const val INTERSECTION_SINGLE_AREA_M = 150.0
        private val HEBREW_FINALS = mapOf('ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ')

        private fun words(text: String): List<String> =
            text.split(Regex("[^\\p{L}\\p{N}]+")).map { StreetDirectoryHolder.normalizeStatic(it) }.filter { it.isNotEmpty() }

        /** The one street whose name ends with all the words of [pointName], or null when none or several do. */
        private fun tailMatch(pointName: String, streetWords: List<Pair<String, List<String>>>): String? {
            val tail = words(pointName)
            if (tail.isEmpty()) return null
            return streetWords.filter { (_, w) -> w.size > tail.size && w.takeLast(tail.size) == tail }
                .map { it.first }.singleOrNull()
        }

        private fun inJerusalem(lat: Double, lon: Double): Boolean =
            lat in 31.65..31.95 && lon in 35.05..35.35

        /** Loads the pipeline's version-1 lookup JSON (streets + addresses). */
        @Suppress("UNCHECKED_CAST")
        fun load(json: String): StreetDirectory {
            val data = PythonJson.loads(json) as? Map<String, Any?>
                ?: throw MunicipalInputException("Unsupported address lookup format.")
            if (data["version"] != 1L) throw MunicipalInputException("Unsupported address lookup format.")
            val streetRows = data["streets"] as? List<Any?>
                ?: throw MunicipalInputException("Unsupported address lookup format.")
            val streets = LinkedHashMap<String, String>()
            val aliases = LinkedHashMap<String, MutableSet<String>>()
            for (row in streetRows) {
                val street = row as? Map<String, Any?> ?: continue
                val code = (street["StreetCode"] as? Any)?.toString() ?: continue
                val name = street["StreetName"] as? String ?: continue
                streets[code] = name
                val names = mutableListOf<String>()
                (street["StreetName"] as? String)?.let { names.add(it) }
                (street["StreetArabicName"] as? String)?.let { names.add(it) }
                (street["aliases"] as? List<Any?>)?.filterIsInstance<String>()?.let { names.addAll(it) }
                for (n in names) {
                    val key = StreetDirectoryHolder.normalizeStatic(n)
                    if (key.isNotEmpty()) aliases.getOrPut(key) { mutableSetOf() }.add(code)
                }
            }
            val streetWords = streets.map { (c, n) -> c to words(n) }
            val points = mutableListOf<Point>()
            val addressRows = data["addresses"] as? List<Any?> ?: emptyList()
            for (row in addressRows) {
                val entry = row as? List<Any?> ?: continue
                if (entry.size < 4) continue
                val lat = (entry[0] as? Number)?.toDouble() ?: continue
                val lon = (entry[1] as? Number)?.toDouble() ?: continue
                val house = entry[2]?.toString() ?: continue
                val name = entry[3]?.toString() ?: continue
                if (!Regex("\\p{Nd}+[\\p{L}\\p{N} /\\-]*").matches(house)) continue
                if (!inJerusalem(lat, lon)) continue
                val (x, y) = Itm.toItm(lat, lon)
                val codes = aliases[StreetDirectoryHolder.normalizeStatic(name)] ?: emptySet()
                val exact = codes.singleOrNull()
                val tail = if (exact == null && codes.isEmpty()) tailMatch(name, streetWords) else null
                points.add(Point(x.toDouble(), y.toDouble(), house, exact, name, lat, lon, tail))
            }
            return StreetDirectory(streets, aliases, points)
        }
    }
}

private object StreetDirectoryHolder {
    private val HEBREW_FINALS = mapOf('ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ')
    fun normalizeStatic(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .map { HEBREW_FINALS[it] ?: it }
        return folded.filter { it.isLetterOrDigit() }.joinToString("")
    }
}
