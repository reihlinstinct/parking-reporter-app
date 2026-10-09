package il.sidewalks.reporter.intake

import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.PublicKeySign
import il.sidewalks.reporter.core.ReviewDraft
import il.sidewalks.reporter.security.ReporterSettings
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64

/** Versioned preview protocol. Keys must be pinned/enrolled separately before any live use. */
object EncryptedPackage {
    const val MAX_BYTES = 1_536_000
    const val CHUNK_BYTES = 32_000
    const val MAX_HEADER_BYTES = 16_000
    val context: ByteArray get() = "parking-intake-tink-v1".toByteArray(Charsets.UTF_8)
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    fun canonical(value: JSONObject): String = value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
        val v = value.get(key)
        JSONObject.quote(key) + ":" + when (v) {
            is JSONObject -> canonical(v)
            is String -> JSONObject.quote(v)
            is Boolean, is Number -> v.toString()
            JSONObject.NULL -> "null"
            else -> error("Unsupported canonical value")
        }
    }
    fun reviewedReport(draft: ReviewDraft, reporter: ReporterSettings, jpeg: ByteArray): JSONObject {
        require(draft.isApproved()) { "Review required" }
        require(jpeg.size in 4..1_100_000 && jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte() &&
            jpeg[jpeg.lastIndex - 1] == 0xff.toByte() && jpeg.last() == 0xd9.toByte()) { "Bounded JPEG required" }
        require(listOf(reporter.firstName, reporter.lastName, reporter.phone, reporter.idNumber, reporter.email).all { it.isNotBlank() && it.length <= 256 })
        return JSONObject().put("schema", 1).put("id", draft.evidence.stableId)
            .put("original_sha256", draft.evidence.originalSha256).put("outgoing_sha256", sha256(jpeg))
            .put("review_digest", draft.digest()).put("plate", draft.plate).put("address", draft.address)
            .put("subject", draft.subject).put("exact_text", draft.exactHebrewText).put("time", draft.confirmedTime)
            .put("latitude", draft.confirmedLatitude).put("longitude", draft.confirmedLongitude)
            .put("reporter", JSONObject().put("first", reporter.firstName).put("last", reporter.lastName)
                .put("phone", reporter.phone).put("id", reporter.idNumber).put("email", reporter.email))
    }
    /** Full report/profile/JPEG digest must be explicitly confirmed, not inherited from local draft review. */
    fun approvalDigest(report: JSONObject): String = sha256(canonical(report).toByteArray(Charsets.UTF_8))
    fun seal(report: JSONObject, jpeg: ByteArray, exactApproval: String, receiverKeyId: String,
             deviceKeyId: String, receiver: HybridEncrypt, signer: PublicKeySign): SealedUpload {
        require(receiverKeyId.matches(Regex("[a-zA-Z0-9_-]{1,80}")) && deviceKeyId.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        require(exactApproval == approvalDigest(report))
        require(report.getString("outgoing_sha256") == sha256(jpeg))
        val header = canonical(JSONObject().put("report", report).put("approval_digest", exactApproval))
            .toByteArray(Charsets.UTF_8)
        require(header.size <= MAX_HEADER_BYTES)
        val plain = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { stream ->
            stream.write("PRPKG2".toByteArray()); stream.writeInt(header.size); stream.write(header); stream.write(jpeg)
        } }.toByteArray()
        try {
            val signature = signer.sign(context + plain)
            val signed = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { stream ->
                stream.writeInt(signature.size); stream.write(signature); stream.write(plain)
            } }.toByteArray()
            try {
                val ciphertext = receiver.encrypt(signed, context)
                require(ciphertext.size <= MAX_BYTES)
                return SealedUpload(report.getString("id"), receiverKeyId, deviceKeyId, exactApproval, ciphertext)
            } finally { signed.fill(0) }
        } finally { plain.fill(0) }
    }
}

class SealedUpload(val id: String, val receiverKeyId: String, val deviceKeyId: String,
                   val approvalDigest: String, bytes: ByteArray) {
    private val data = bytes.copyOf()
    val digest = EncryptedPackage.sha256(data)
    val count = (data.size + EncryptedPackage.CHUNK_BYTES - 1) / EncryptedPackage.CHUNK_BYTES
    init { require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))); require(data.isNotEmpty() && data.size <= EncryptedPackage.MAX_BYTES && count in 1..48) }
    fun manifest(): String = EncryptedPackage.canonical(JSONObject().put("schema", 2).put("package", id)
        .put("key", receiverKeyId).put("device", deviceKeyId).put("bytes", data.size).put("count", count).put("sha256", digest))
    fun chunk(index: Int, issue: Long): String {
        require(index in 0 until count && issue > 0)
        val bytes = data.copyOfRange(index * EncryptedPackage.CHUNK_BYTES, minOf((index + 1) * EncryptedPackage.CHUNK_BYTES, data.size))
        return EncryptedPackage.canonical(JSONObject().put("schema", 2).put("package", id).put("issue", issue)
            .put("index", index).put("count", count).put("sha256", digest).put("data", Base64.getEncoder().encodeToString(bytes)))
    }
    fun completion(issue: Long): String = EncryptedPackage.canonical(JSONObject().put("schema", 2)
        .put("package", id).put("issue", issue).put("count", count).put("sha256", digest).put("complete", true))
    override fun toString(): String = "SealedUpload(redacted)"
}
