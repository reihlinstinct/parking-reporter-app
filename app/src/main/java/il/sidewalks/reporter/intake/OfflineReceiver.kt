package il.sidewalks.reporter.intake

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.PublicKeyVerify
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.Base64
import java.nio.charset.CodingErrorAction
import il.sidewalks.reporter.core.Evidence
import il.sidewalks.reporter.core.ReviewDraft

/** Offline reference validator only. No HTTP, key provisioning or report filing. */
object OfflineReceiver {
    data class Received(val report: JSONObject, val jpeg: ByteArray) {
        override fun toString() = "Received(redacted)"
    }
    private fun exact(json: JSONObject, names: Set<String>) = require(json.keys().asSequence().toSet() == names)
    private fun schema(json: JSONObject) = require(json.get("schema") is Number && json.getInt("schema") == 2)
    private fun parseCanonical(text: String): JSONObject {
        val json = JSONObject(text)
        require(EncryptedPackage.canonical(json) == text) // rejects duplicate keys/noncanonical encodings after parse
        return json
    }
    private fun validateReport(report: JSONObject) {
        listOf("original_sha256", "outgoing_sha256", "review_digest").forEach { field ->
            require(report.getString(field).matches(Regex("[a-f0-9]{64}")))
        }
        listOf("id", "plate", "address", "subject", "exact_text", "time").forEach { field ->
            require(report.get(field) is String && report.getString(field).length in 1..4000)
        }
        val reporter = report.getJSONObject("reporter")
        exact(reporter, setOf("first", "last", "phone", "id", "email"))
        reporter.keys().asSequence().forEach { field -> require(reporter.get(field) is String && reporter.getString(field).isNotBlank() && reporter.getString(field).length <= 256) }
        require(report.get("latitude") is Number && report.get("longitude") is Number)
        val draft = ReviewDraft(Evidence(report.getString("id"), report.getString("original_sha256"), null, null, null),
            report.getString("plate"), report.getString("address"), report.getString("subject"), report.getString("exact_text"), emptySet(),
            confirmedTime = report.getString("time"), confirmedLatitude = report.getDouble("latitude"), confirmedLongitude = report.getDouble("longitude"))
        require(draft.isReady())
    }
    private fun key(value: String) = require(value.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
    fun open(manifestText: String, chunks: List<String>, completionText: String, expectedIssue: Long,
             expectedReceiver: String, expectedDevice: String, decryptor: HybridDecrypt,
             enrolledVerifier: PublicKeyVerify): Received {
        require(expectedIssue > 0 && manifestText.length <= 2000 && completionText.length <= 2000)
        key(expectedReceiver); key(expectedDevice)
        val manifest = parseCanonical(manifestText); schema(manifest)
        exact(manifest, setOf("schema", "package", "key", "device", "bytes", "count", "sha256"))
        val id = manifest.getString("package"); key(id)
        require(manifest.getString("key") == expectedReceiver && manifest.getString("device") == expectedDevice)
        val count = manifest.getInt("count"); val size = manifest.getInt("bytes"); val digest = manifest.getString("sha256")
        require(count in 1..48 && size in 1..EncryptedPackage.MAX_BYTES && chunks.size == count)
        require(count == (size + EncryptedPackage.CHUNK_BYTES - 1) / EncryptedPackage.CHUNK_BYTES)
        require(digest.matches(Regex("[a-f0-9]{64}")))
        val completion = parseCanonical(completionText); schema(completion)
        exact(completion, setOf("schema", "package", "issue", "count", "sha256", "complete"))
        require(completion.getBoolean("complete") && completion.getString("package") == id &&
            completion.getLong("issue") == expectedIssue && completion.getInt("count") == count && completion.getString("sha256") == digest)
        val parts = arrayOfNulls<ByteArray>(count)
        chunks.forEach { text ->
            require(text.length <= 48_000)
            val chunk = parseCanonical(text); schema(chunk)
            exact(chunk, setOf("schema", "package", "issue", "index", "count", "sha256", "data"))
            require(chunk.getString("package") == id && chunk.getLong("issue") == expectedIssue &&
                chunk.getInt("count") == count && chunk.getString("sha256") == digest)
            val index = chunk.getInt("index"); require(index in 0 until count && parts[index] == null)
            val encoded = chunk.getString("data"); require(encoded.length <= 42_668)
            val decoded = Base64.getDecoder().decode(encoded)
            require(decoded.size == if (index == count - 1) size - index * EncryptedPackage.CHUNK_BYTES else EncryptedPackage.CHUNK_BYTES)
            parts[index] = decoded
        }
        val ciphertext = ByteArray(size); var offset = 0
        parts.forEach { p -> val bytes = requireNotNull(p); bytes.copyInto(ciphertext, offset); offset += bytes.size }
        require(EncryptedPackage.sha256(ciphertext) == digest)
        val signed = decryptor.decrypt(ciphertext, EncryptedPackage.context)
        try {
            require(signed.size in 16..EncryptedPackage.MAX_BYTES)
            val buffer = ByteBuffer.wrap(signed); val sigSize = buffer.int
            require(sigSize in 1..256 && buffer.remaining() > sigSize + 10)
            val signature = ByteArray(sigSize); buffer.get(signature)
            val plain = ByteArray(buffer.remaining()); buffer.get(plain)
            try {
                enrolledVerifier.verify(signature, EncryptedPackage.context + plain)
                val payload = ByteBuffer.wrap(plain); val magic = ByteArray(6); payload.get(magic)
                require(String(magic, Charsets.US_ASCII) == "PRPKG2")
                val headerSize = payload.int; require(headerSize in 1..EncryptedPackage.MAX_HEADER_BYTES && payload.remaining() > headerSize)
                val headerBytes = ByteArray(headerSize); payload.get(headerBytes)
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                val header = parseCanonical(decoder.decode(ByteBuffer.wrap(headerBytes)).toString())
                exact(header, setOf("report", "approval_digest"))
                val report = header.getJSONObject("report")
                exact(report, setOf("schema", "id", "original_sha256", "outgoing_sha256", "review_digest", "plate", "address", "subject", "exact_text", "time", "latitude", "longitude", "reporter"))
                require(report.getInt("schema") == 1 && report.getString("id") == id)
                require(header.getString("approval_digest") == EncryptedPackage.approvalDigest(report))
                val jpeg = ByteArray(payload.remaining()); payload.get(jpeg)
                require(jpeg.size in 4..1_100_000 && jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte() &&
                    jpeg[jpeg.lastIndex - 1] == 0xff.toByte() && jpeg.last() == 0xd9.toByte())
                require(report.getString("outgoing_sha256") == EncryptedPackage.sha256(jpeg))
                validateReport(report)
                return Received(report, jpeg)
            } finally { plain.fill(0) }
        } finally { signed.fill(0) }
    }
}
