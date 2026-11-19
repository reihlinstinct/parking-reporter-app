package io.github.reihlinstinct.reporter.municipal

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Network receives only a static region-file ID, never GPS, reporter data or an address string. */
class OnlineStreetDirectory(private val context: Context) {
    private val directory = java.io.File(context.noBackupFilesDir, "address-data")
    private val imported = AddressFileCache(directory)
    private val index by lazy {
        context.assets.open("address-index.json").use { input ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == INDEX_SHA256) { "Address index mismatch" }
        }
        context.assets.open("address-index.json").bufferedReader(Charsets.UTF_8).use {
            AddressShardIndex(it.readText())
        }
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .build()

    fun prepare() { if (!imported.available()) index }
    fun importLocal(input: InputStream) = imported.store(input)
    fun clear() {
        imported.clear()
        if (directory.isDirectory) directory.listFiles()?.filter { it.name.startsWith("region-") }?.forEach {
            check(it.delete() || !it.exists()) { "Address cache removal failed" }
        }
    }
    /** Refresh clears cached region files. The next lookup downloads only its required regions. */
    fun refresh() { clear(); prepare() }

    fun loadForStreets(names: List<String>): StreetDirectory {
        if (imported.available()) return BundledStreetDirectory.load { imported.verifiedFile().inputStream() }
        return load(index.forStreets(names))
    }

    @Suppress("UNCHECKED_CAST")
    fun loadForLocation(latitude: Double, longitude: Double): StreetDirectory {
        if (imported.available()) return BundledStreetDirectory.load { imported.verifiedFile().inputStream() }
        val ids = index.candidates(latitude, longitude)
        require(ids.isNotEmpty()) { "Location has no address-data region" }
        val documents = ids.associateWith { document(it) }
        val regions = documents.map { (id, root) -> AddressPolygons.Region(id, polygons(root)) }
        require(AddressPolygons.select(regions, latitude, longitude).isNotEmpty()) {
            "Location outside CBS2022 coverage; supply a manual address"
        }
        val rows = documents.values.flatMap { it["addresses"] as List<Any?> }.distinct()
        return StreetDirectory.loadRows(index.streets, rows.asSequence())
    }

    @Suppress("UNCHECKED_CAST")
    private fun polygons(root: Map<String, Any?>): List<List<List<AddressPolygons.Point>>> =
        (root["polygons"] as List<List<List<List<Number>>>>).map { polygon -> polygon.map { ring ->
            ring.map { point -> require(point.size == 2); AddressPolygons.Point(point[0].toDouble(), point[1].toDouble()) }
        } }

    @Suppress("UNCHECKED_CAST")
    private fun document(id: String): Map<String, Any?> {
        val shard = index.shards.single { it.id == id }
        val cache = AddressFileCache(directory, "region-$id.json", shard.sha256)
        if (!cache.available()) {
            val request = Request.Builder().url(BASE_URL + id + ".json").get().build()
            client.newCall(request).execute().use { response ->
                check(response.code == 200) { "Address region unavailable" }
                val body = checkNotNull(response.body) { "Address region unavailable" }
                require(body.contentLength() <= AddressFileCache.MAX_BYTES) { "Address region too large" }
                body.byteStream().use { cache.store(it) }
            }
        }
        val root = PythonJson.loads(cache.verifiedFile().readText(Charsets.UTF_8)) as Map<String, Any?>
        require(root["version"] == 1L)
        return root
    }

    @Suppress("UNCHECKED_CAST")
    private fun load(ids: Set<String>): StreetDirectory {
        val rows = ids.sorted().flatMap { document(it)["addresses"] as List<Any?> }.distinct()
        return StreetDirectory.loadRows(index.streets, rows.asSequence())
    }

    companion object {
        // Fixed reviewed repository, not a user-controlled endpoint. Per-file index hashes pin bytes.
        const val INDEX_SHA256 = "3d56c9386db6d48d655ce72141bc224da1233f6ba90374ba7f2471f084ef5dd8"
        const val BASE_URL = "https://raw.githubusercontent.com/reihlinstinct/parking-reporter-app/main/tools/address-lookup/data/v1/"
    }
}
