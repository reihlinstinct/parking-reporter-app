package io.github.reihlinstinct.reporter.municipal

import java.text.Normalizer

/** A pinned index carries all aliases, so partial point downloads cannot hide name ambiguity. */
class AddressShardIndex(json: String) {
    @Suppress("UNCHECKED_CAST")
    private val root = PythonJson.loads(json) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val streets = root["streets"] as List<Any?>
    data class Shard(val id: String, val sha256: String, val bbox: List<Double>?, val codes: Set<String>)
    @Suppress("UNCHECKED_CAST")
    val shards: List<Shard> = (root["regions"] as List<Map<String, Any?>>).map { row ->
        val id = row["id"] as String
        val hash = row["sha256"] as String
        require(id.matches(Regex("[a-z0-9-]+")) && hash.matches(Regex("[a-f0-9]{64}")))
        val bbox = (row["bbox"] as? List<Number>)?.map { it.toDouble() }
        if (bbox != null) require(bbox.size == 4 && bbox.all { it.isFinite() } &&
            bbox[0] <= bbox[2] && bbox[1] <= bbox[3])
        Shard(id, hash, bbox, (row["streetCodes"] as List<String>).toSet())
    }
    init {
        require(root["version"] == 1L && shards.isNotEmpty() && shards.size <= 1024)
        require(shards.map { it.id }.distinct().size == shards.size)
        require(streets.size <= 10000)
    }

    @Suppress("UNCHECKED_CAST")
    fun forStreets(names: List<String>): Set<String> {
        val codes = names.map { wanted ->
            val matching = streets.map { it as Map<String, Any?> }.filter { row ->
                val aliases = listOfNotNull(row["StreetName"] as? String, row["StreetArabicName"] as? String) +
                    ((row["aliases"] as? List<*>)?.filterIsInstance<String>() ?: emptyList())
                aliases.any { normalize(it) == normalize(wanted) }
            }.map { it["StreetCode"].toString() }.toSet()
            require(matching.size == 1) { "Street has no unique address-data match" }
            matching.single()
        }.toSet()
        val selected = shards.filter { it.codes.any { code -> code in codes } }.map { it.id }.toSet()
        require(selected.isNotEmpty()) { "Street has no address data" }
        return selected
    }

    /** Only candidates, never a claim of point-in-polygon membership. Full geometry is in each shard. */
    fun candidates(latitude: Double, longitude: Double): Set<String> {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        // Conservative halo exceeds resolver150m neighbor radius throughout Jerusalem.
        return shards.filter { shard -> shard.bbox?.let { b ->
            longitude >= b[0] - 0.003 && longitude <= b[2] + 0.003 &&
                latitude >= b[1] - 0.003 && latitude <= b[3] + 0.003
        } ?: false }.map { it.id }.toSet()
    }

    private fun normalize(text: String): String {
        val finals = mapOf('ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ')
        return Normalizer.normalize(text, Normalizer.Form.NFKC).map { finals[it] ?: it }
            .filter { it.isLetterOrDigit() }.joinToString("")
    }
}
