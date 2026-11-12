package io.github.reihlinstinct.reporter.registry

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Presence is not permission to park. Missing/unknown type is never invented. */
sealed interface DisabilityLookupResult {
    data class Found(val type: DisabilityTagType) : DisabilityLookupResult
    data object NotFound : DisabilityLookupResult
    data class Error(val kind: LookupErrorKind) : DisabilityLookupResult
}

/** Public read-only CKAN lookup. Off-main-thread only. No logging, retries or persistent plates. */
class DisabilityRegistryClient(
    baseUrl: String = VehicleRegistryConfig.DEFAULT_BASE_URL,
    private val client: OkHttpClient = boundedClient(),
    clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val intervalMillis: Long = 1000,
) {
    private val endpoint = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
        .addPathSegments(VehicleRegistryClient.SEARCH_PATH.removePrefix("/")).build()
    private val cache = TtlLruCache<PlateNumber, DisabilityLookupResult>(128, 300_000, clockMillis)
    private val limiter = MinIntervalRateLimiter(intervalMillis, clockMillis)
    fun lookup(raw: String): DisabilityLookupResult {
        val plate = PlateNumber.parse(raw) ?: return DisabilityLookupResult.Error(LookupErrorKind.INVALID_PLATE)
        cache.get(plate)?.let { return it }
        if (!limiter.tryAcquire()) return DisabilityLookupResult.Error(LookupErrorKind.RATE_LIMITED)
        val result = fetch(plate)
        if (result is DisabilityLookupResult.Found || result is DisabilityLookupResult.NotFound) cache.put(plate, result)
        return result
    }
    private fun fetch(plate: PlateNumber): DisabilityLookupResult {
        val url = endpoint.newBuilder().addQueryParameter("resource_id", RESOURCE)
            .addQueryParameter("filters", JSONObject().put(PLATE, plate.digits.toLong()).toString())
            .addQueryParameter("fields", "$PLATE,$TYPE").addQueryParameter("limit", "2").build()
        val body = try {
            client.newCall(Request.Builder().url(url).get().header("Accept", "application/json").build()).execute().use { response ->
                if (!response.isSuccessful) return DisabilityLookupResult.Error(LookupErrorKind.HTTP)
                val bytes = response.body.byteStream().use { stream ->
                    val buffer = ByteArray(65_537); var count = 0
                    while (count < buffer.size) {
                        val n = stream.read(buffer, count, buffer.size - count)
                        if (n < 0) break
                        count += n
                    }
                    buffer.copyOf(count)
                }
                if (bytes.size > 65_536) return DisabilityLookupResult.Error(LookupErrorKind.MALFORMED_RESPONSE)
                String(bytes, Charsets.UTF_8)
            }
        } catch (_: IOException) { return DisabilityLookupResult.Error(LookupErrorKind.NETWORK) }
        return try {
            val root = JSONObject(body)
            if (root.opt("success") != true) return DisabilityLookupResult.Error(LookupErrorKind.API_FAILURE)
            val records = root.getJSONObject("result").getJSONArray("records")
            if (records.length() == 0) return DisabilityLookupResult.NotFound
            // Ambiguous duplicates are not a trustworthy negative or type selection.
            require(records.length() == 1)
            val record = records.getJSONObject(0)
            val number = record.opt(PLATE)
            require((if (number is Number) number.toLong().toString() else number?.toString()) == plate.digits)
            val code = record.opt(TYPE)
            // CKAN currently emits integer codes. Unknown strings/fractions remain unknown.
            val type = if (code is Number && code.toDouble() == code.toInt().toDouble()) code.toInt() else null
            DisabilityLookupResult.Found(DisabilityTagType.fromRegistry(type))
        } catch (_: RuntimeException) { DisabilityLookupResult.Error(LookupErrorKind.MALFORMED_RESPONSE) }
    }
    companion object {
        const val RESOURCE = "c8b9f9c8-4612-4068-934f-d4acd2e3c06e"
        const val PLATE = "MISPAR RECHEV"
        const val TYPE = "SUG TAV"
        fun boundedClient(): OkHttpClient = VehicleRegistryClient.safeClient().newBuilder()
            .connectTimeout(1200, TimeUnit.MILLISECONDS).readTimeout(1500, TimeUnit.MILLISECONDS)
            .callTimeout(1800, TimeUnit.MILLISECONDS).build()
    }
}
