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

    fun prepare() { index.suggestStreets("") } // Warm the index-only directory on the caller's IO dispatcher.
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
        return index.loadForStreets(names, ::document)
    }

    fun suggestStreets(query: String, limit: Int = 5) = index.suggestStreets(query, limit)

    fun loadForLocation(latitude: Double, longitude: Double): StreetDirectory {
        if (imported.available()) return BundledStreetDirectory.load { imported.verifiedFile().inputStream() }
        return index.loadForLocation(latitude, longitude, ::document)
    }

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

    companion object {
        // Fixed reviewed repository, not a user-controlled endpoint. Per-file index hashes pin bytes.
        const val INDEX_SHA256 = "3d56c9386db6d48d655ce72141bc224da1233f6ba90374ba7f2471f084ef5dd8"
        const val BASE_URL = "https://raw.githubusercontent.com/reihlinstinct/parking-reporter-app/main/tools/address-lookup/data/v1/"
    }
}
