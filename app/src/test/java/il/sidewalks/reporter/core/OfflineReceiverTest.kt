package il.sidewalks.reporter.core

import com.google.crypto.tink.*
import com.google.crypto.tink.hybrid.*
import com.google.crypto.tink.signature.*
import il.sidewalks.reporter.intake.*
import il.sidewalks.reporter.security.ReporterSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflineReceiverTest {
    private class Fixture {
        init { HybridConfig.register(); SignatureConfig.register() }
        val receiver = KeysetHandle.generateNew(HybridKeyTemplates.ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM)
        val device = KeysetHandle.generateNew(SignatureKeyTemplates.ECDSA_P256)
        val jpeg = byteArrayOf(-1, -40) + ByteArray(68_000) { 4 } + byteArrayOf(-1, -39)
        val report = EncryptedPackage.reviewedReport(
            ReviewDraft(Evidence("synthetic-id", "a".repeat(64), "2026-01-01T12:00:00Z", 31.0, 35.0),
                "1234567", "רחוב בדיקה 1", "sidewalk_parking", "בדיקה", emptySet()).approve(),
            ReporterSettings("synthetic", "reporter", "0000000", "000000000", "synthetic@example.invalid"), jpeg)
        val upload = EncryptedPackage.seal(report, jpeg, EncryptedPackage.approvalDigest(report), "test-receiver", "test-device",
            receiver.publicKeysetHandle.getPrimitive(HybridEncrypt::class.java), device.getPrimitive(PublicKeySign::class.java))
        fun open(parts: List<String> = (0 until upload.count).map { upload.chunk(it, 7) }, issue: Long = 7,
                 key: String = "test-receiver", sender: String = "test-device", complete: String = upload.completion(7),
                 verifier: PublicKeyVerify = device.publicKeysetHandle.getPrimitive(PublicKeyVerify::class.java)) =
            OfflineReceiver.open(upload.manifest(), parts, complete, issue, key, sender,
                receiver.getPrimitive(HybridDecrypt::class.java), verifier)
    }
    @Test fun senderAndOfflineReceiverProtocolRoundtrip() {
        val f = Fixture(); val r = f.open((0 until f.upload.count).reversed().map { f.upload.chunk(it, 7) })
        assertArrayEquals(f.jpeg, r.jpeg)
        assertEquals("synthetic-id", r.report.getString("id"))
        assertEquals("Received(redacted)", r.toString())
    }
    @Test fun wrongIssueKeysAndCompletionRejected() {
        val f = Fixture()
        assertThrows(IllegalArgumentException::class.java) { f.open(issue = 8) }
        assertThrows(IllegalArgumentException::class.java) { f.open(key = "stranger") }
        assertThrows(IllegalArgumentException::class.java) { f.open(sender = "stranger") }
        val done = EncryptedPackage.canonical(JSONObject(f.upload.completion(7)).put("complete", false))
        assertThrows(IllegalArgumentException::class.java) { f.open(complete = done) }
    }
    @Test fun missingDuplicateAndCorruptChunksRejected() {
        val f = Fixture(); val chunks = (0 until f.upload.count).map { f.upload.chunk(it, 7) }
        assertThrows(IllegalArgumentException::class.java) { f.open(chunks.dropLast(1)) }
        assertThrows(IllegalArgumentException::class.java) { f.open(List(chunks.size) { chunks[0] }) }
        val bad = chunks.toMutableList(); val j = JSONObject(bad[0]); val data = j.getString("data")
        bad[0] = EncryptedPackage.canonical(j.put("data", (if (data[0] == 'A') "B" else "A") + data.substring(1)))
        assertThrows(IllegalArgumentException::class.java) { f.open(bad) }
    }
    @Test fun duplicateJsonKeyAndWhitespaceAreRejected() {
        val f = Fixture(); val done = f.upload.completion(7)
        assertThrows(RuntimeException::class.java) { f.open(complete = done.replaceFirst("{", "{\"count\":" + f.upload.count + ",")) }
        assertThrows(IllegalArgumentException::class.java) { f.open(complete = " " + done) }
    }
    @Test fun strangerSignatureDoesNotCountAsEnrollment() {
        val f = Fixture(); val stranger = KeysetHandle.generateNew(SignatureKeyTemplates.ECDSA_P256)
        assertThrows(java.security.GeneralSecurityException::class.java) {
            f.open(verifier = stranger.publicKeysetHandle.getPrimitive(PublicKeyVerify::class.java))
        }
    }
}
