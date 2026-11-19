package io.github.reihlinstinct.reporter.municipal

import java.text.Normalizer

/** A pinned index carries all aliases, so partial point downloads cannot hide name ambiguity. */
class AddressShardIndex(json: String) {
    @Suppress("UNCHECKED_CAST")
    private val root = PythonJson.loads(json) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val streets = root["streets"] as List<Any?>
    data class Shard(val id: String, val sha256: String, val region: AddressPolygons.Region, val codes: Set<String>)
    @Suppress("UNCHECKED_CAST")
    val shards: List<Shard> = (root["regions"] as List<Map<String, Any?>>).map { row ->
        val id = row["id"] as String
        val hash = row["sha256"] as String
        require(id.matches(Regex("[a-z0-9-]+")) && hash.matches(Regex("[a-f0-9]{64}")))
        val polygons = (row["polygons"] as List<List<List<List<Number>>>>).map { polygon ->
            polygon.map { ring -> ring.map { point ->
                require(point.size == 2)
                AddressPolygons.Point(point[0].toDouble(), point[1].toDouble())
            } }
        }
        Shard(id, hash, AddressPolygons.Region(id, polygons), (row["streetCodes"] as List<String>).toSet())
    }
    init {
        require(root["version"] == 1L && shards.isNotEmpty() && shards.size <= 1024)
        require(shards.map { it.id }.distinct().size == shards.size)
        require(streets.size <= 10000)
        // Validate every polygon even before a coordinate is supplied.
        AddressPolygons.select(shards.map { it.region }, 0.0, 0.0)
    }

    @Suppress("UNCHECKED_CAST")
    fun forStreets(names: List<String>): Set<String> {
        val codes = names.map { wanted ->
            val matching = streets.map { it as Map<String, Any?> }.filter { row ->
                val aliases = listOfNotNull(row["StreetName"] as? String, row["StreetArabicName"] as? String) +
                    ((row["aliases"] as? List<*>)?.filterIsInstance<String>() ?: emptyList())
                aliases.any { normalize(it) == normalize(wanted) }
            }.map { it["StreetCode"].toString() }.toSet()
            require(codesSingle(matching)) { "Street has no unique address-data match" }
            matching.single()
        }.toSet()
        val selected = shards.filter { it.codes.any { code -> code in codes } }.map { it.id }.toSet()
        require(selected.isNotEmpty()) { "Street has no address data" }
        return selected
    }

    fun forLocation(latitude: Double, longitude: Double): Set<String> {
        val selected = AddressPolygons.select(shards.map { it.region }, latitude, longitude)
        require(selected.isNotEmpty()) { "Location has no address-data region" }
        // The resolver compares up to150m, including across a polygon boundary.
        // A conservative0.003degree bounding-box halo exceeds150m throughout Jerusalem.
        val neighbors = shards.filter { shard -> shard.region.polygons.any { polygon ->
            val points = polygon.flatten()
            longitude >= points.minOf { it.longitude } - 0.003 &&
                longitude <= points.maxOf { it.longitude } + 0.003 &&
                latitude >= points.minOf { it.latitude } - 0.003 &&
                latitude <= points.maxOf { it.latitude } + 0.003
        } }.map { it.id }
        return selected + neighbors
    }

    private fun codesSingle(codes: Set<String>) = codes.size == 1
    private fun normalize(text: String): String {
        val finals = mapOf('ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ')
        return Normalizer.normalize(text, Normalizer.Form.NFKC).map { finals[it] ?: it }
            .filter { it.isLetterOrDigit() }.joinToString("")
    }
}
