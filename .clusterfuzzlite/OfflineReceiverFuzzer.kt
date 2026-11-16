import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.PublicKeyVerify
import io.github.reihlinstinct.reporter.intake.EncryptedPackage
import io.github.reihlinstinct.reporter.intake.OfflineReceiver
import org.json.JSONObject
import java.util.Base64

/**
 * Jazzer target for the PRPKG2 offline receiver. Synthetic only: the crypto is replaced by an identity
 * decryptor and an accepting verifier, so the fuzzer reaches the parsing and validation code that runs
 * after decryption. The documented rejection is IllegalArgumentException; anything else is a finding.
 *
 * Input byte 0 selects the mode. Odd: the rest, split on 0x00, is manifest, chunks..., completion as raw text.
 * Even: the rest is the decrypted signed blob, wrapped into a well-formed manifest, chunks and completion.
 */
object OfflineReceiverFuzzer {
    private const val RECEIVER = "receiver"
    private const val DEVICE = "device"
    private const val ISSUE = 1L
    private val identity = HybridDecrypt { ciphertext, _ -> ciphertext }
    private val accept = PublicKeyVerify { _, _ -> }

    @JvmStatic
    fun fuzzerTestOneInput(input: ByteArray) {
        if (input.isEmpty() || input.size > 100_001) return
        val rest = input.copyOfRange(1, input.size)
        try {
            if (input[0].toInt() and 1 == 1) raw(rest) else structured(rest)
        } catch (expected: IllegalArgumentException) {
            // the documented rejection
        }
    }

    /** Build smoke test: the generated golden seed must reach successful report validation. */
    @JvmStatic
    fun main(args: Array<String>) {
        val input = java.io.File(args.single()).readBytes()
        check(structured(input.copyOfRange(1, input.size)).report.getString("id") == "synthetic-id")
    }

    private fun raw(rest: ByteArray) {
        val parts = mutableListOf<String>()
        var start = 0
        for (i in 0..rest.size) {
            if (i == rest.size || rest[i] == 0.toByte()) {
                parts.add(String(rest, start, i - start, Charsets.UTF_8))
                start = i + 1
            }
        }
        if (parts.size < 3) return
        OfflineReceiver.open(parts.first(), parts.subList(1, parts.size - 1).toList(), parts.last(), ISSUE, RECEIVER, DEVICE, identity, accept)
    }

    private fun structured(signed: ByteArray): OfflineReceiver.Received {
        require(signed.isNotEmpty() && signed.size <= 100_000)
        val chunkBytes = EncryptedPackage.CHUNK_BYTES
        val count = (signed.size + chunkBytes - 1) / chunkBytes
        val digest = EncryptedPackage.sha256(signed)
        val id = "synthetic-id"
        val chunks = (0 until count).map { i ->
            val slice = signed.copyOfRange(i * chunkBytes, minOf(signed.size, (i + 1) * chunkBytes))
            EncryptedPackage.canonical(JSONObject().put("schema", 2).put("package", id).put("issue", ISSUE).put("index", i)
                .put("count", count).put("sha256", digest).put("data", Base64.getEncoder().encodeToString(slice)))
        }
        val manifest = EncryptedPackage.canonical(JSONObject().put("schema", 2).put("package", id).put("key", RECEIVER)
            .put("device", DEVICE).put("bytes", signed.size).put("count", count).put("sha256", digest))
        val done = EncryptedPackage.canonical(JSONObject().put("schema", 2).put("package", id).put("issue", ISSUE)
            .put("count", count).put("sha256", digest).put("complete", true))
        return OfflineReceiver.open(manifest, chunks, done, ISSUE, RECEIVER, DEVICE, identity, accept)
    }
}
