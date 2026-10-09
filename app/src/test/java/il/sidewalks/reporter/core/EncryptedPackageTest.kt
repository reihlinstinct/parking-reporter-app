package il.sidewalks.reporter.core

import com.google.crypto.tink.*
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.hybrid.HybridKeyTemplates
import com.google.crypto.tink.signature.SignatureConfig
import com.google.crypto.tink.signature.SignatureKeyTemplates
import il.sidewalks.reporter.intake.*
import il.sidewalks.reporter.security.ReporterSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64

class EncryptedPackageTest {
    private fun draft() = ReviewDraft(Evidence("synthetic-id", "a".repeat(64), "2026-01-01T12:00:00Z", 31.0, 35.0),
        "1234567", "רחוב בדיקה 1", "sidewalk_parking", "דיווח בדיקה", emptySet()).approve()
    private val reporter = ReporterSettings("synthetic-first", "synthetic-last", "0000000", "000000000", "synthetic@example.invalid")
    private val photo = byteArrayOf(0xff.toByte(), 0xd8.toByte()) + ByteArray(68_000) { 4 } + byteArrayOf(0xff.toByte(), 0xd9.toByte())
    private fun seal(): Triple<SealedUpload, KeysetHandle, KeysetHandle> {
        HybridConfig.register(); SignatureConfig.register()
        val receiver = KeysetHandle.generateNew(HybridKeyTemplates.ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM)
        val device = KeysetHandle.generateNew(SignatureKeyTemplates.ECDSA_P256)
        val report = EncryptedPackage.reviewedReport(draft(), reporter, photo)
        return Triple(EncryptedPackage.seal(report, photo, EncryptedPackage.approvalDigest(report), "synthetic-receiver", "synthetic-device",
            receiver.publicKeysetHandle.getPrimitive(HybridEncrypt::class.java), device.getPrimitive(PublicKeySign::class.java)), receiver, device)
    }
    private fun bytes(upload: SealedUpload): ByteArray = (0 until upload.count).fold(byteArrayOf()) { acc, index ->
        acc + Base64.getDecoder().decode(JSONObject(upload.chunk(index, 1)).getString("data"))
    }
    @Test fun tinkRoundtripAndEnrolledSignatureCoverExactPackage() {
        val (upload, receiver, device) = seal()
        val ciphertext = bytes(upload)
        assertFalse(String(ciphertext).contains("synthetic@example.invalid"))
        val signed = receiver.getPrimitive(HybridDecrypt::class.java).decrypt(ciphertext, EncryptedPackage.context)
        val buffer = ByteBuffer.wrap(signed); val sigSize = buffer.int; val signature = ByteArray(sigSize); buffer.get(signature)
        val plain = ByteArray(buffer.remaining()); buffer.get(plain)
        device.publicKeysetHandle.getPrimitive(PublicKeyVerify::class.java).verify(signature, EncryptedPackage.context + plain)
        val packageBuffer = ByteBuffer.wrap(plain); val magic = ByteArray(6); packageBuffer.get(magic); assertEquals("PRPKG2", String(magic))
        val header = ByteArray(packageBuffer.int); packageBuffer.get(header)
        val json = JSONObject(String(header, Charsets.UTF_8)); assertEquals(upload.approvalDigest, json.getString("approval_digest"))
        val decodedPhoto = ByteArray(packageBuffer.remaining()); packageBuffer.get(decodedPhoto); assertArrayEquals(photo, decodedPhoto)
        ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
        assertThrows(java.security.GeneralSecurityException::class.java) { receiver.getPrimitive(HybridDecrypt::class.java).decrypt(ciphertext, EncryptedPackage.context) }
        plain[plain.lastIndex] = 5
        assertThrows(java.security.GeneralSecurityException::class.java) { device.publicKeysetHandle.getPrimitive(PublicKeyVerify::class.java).verify(signature, EncryptedPackage.context + plain) }
    }
    @Test fun approvalBindsProfileAndOutgoingPhotoSeparatelyFromLocalReview() {
        val r = EncryptedPackage.reviewedReport(draft(), reporter, photo); val digest = EncryptedPackage.approvalDigest(r)
        r.getJSONObject("reporter").put("phone", "synthetic-change")
        assertNotEquals(digest, EncryptedPackage.approvalDigest(r))
        assertThrows(IllegalArgumentException::class.java) { EncryptedPackage.reviewedReport(draft().edit(), reporter, photo) }
        assertThrows(IllegalArgumentException::class.java) { EncryptedPackage.reviewedReport(draft(), reporter, ByteArray(1_100_001)) }
    }
    @Test fun canonicalOrderDoesNotDependOnInsertionOrder() {
        assertEquals(EncryptedPackage.canonical(JSONObject().put("b", "שלום").put("a", 1)),
            EncryptedPackage.canonical(JSONObject().put("a", 1).put("b", "שלום")))
    }
    @Test fun encryptedChunkTransportCommitsLastAndPrewritesJournal() {
        val upload = seal().first; val journal = MemoryJournal(); val messages = mutableListOf<String>()
        val port = object : IntakeWritePort {
            override fun create(title: String, manifest: String): Long { assertEquals(UploadPhase.CREATING, journal.state?.phase); assertFalse(manifest.contains("synthetic@example.invalid")); return 7 }
            override fun comment(issue: Long, body: String) { messages += body; assertTrue(journal.state?.phase in listOf(UploadPhase.CHUNKING, UploadPhase.COMMITTING)) }
        }
        assertEquals(UploadPhase.UPLOADED, IntakeUploader(port, journal).send(upload, upload.approvalDigest).phase)
        assertEquals(upload.count + 1, messages.size); assertTrue(JSONObject(messages.last()).getBoolean("complete"))
        assertThrows(IllegalStateException::class.java) { IntakeUploader(port, journal).send(upload, upload.approvalDigest) }
    }
    @Test fun everyWriteFaultStopsWithoutRetryOrCompletionMarker() {
        val upload = seal().first
        for (fault in 0..upload.count) {
            val journal = MemoryJournal(); var writes = 0; var completed = false
            val port = object : IntakeWritePort {
                override fun create(title: String, manifest: String): Long { if (writes++ == fault) error("synthetic timeout"); return 7 }
                override fun comment(issue: Long, body: String) { if (writes++ == fault) error("synthetic timeout"); if (body.contains("\"complete\"")) completed = true }
            }
            val error = assertThrows(IntakeUploadFailure::class.java) { IntakeUploader(port, journal).send(upload, upload.approvalDigest) }
            assertTrue(error.uncertain); assertEquals(UploadPhase.UNCERTAIN, journal.state?.phase); assertFalse(completed)
            assertEquals(fault + 1, writes)
        }
    }
    @Test fun checkpointFailureBeforeFirstWriteMakesNoNetworkCall() {
        val upload = seal().first; var writes = 0
        val journal = object : UploadJournal {
            override fun reserve(upload: SealedUpload) = UploadCheckpoint(upload.id, upload.digest, UploadPhase.PREPARED)
            override fun store(checkpoint: UploadCheckpoint) { error("synthetic storage failure") }
        }
        val port = object : IntakeWritePort { override fun create(title: String, manifest: String): Long { writes++;return 1 }; override fun comment(issue: Long, body: String) { writes++ } }
        assertFalse(assertThrows(IntakeUploadFailure::class.java) { IntakeUploader(port, journal).send(upload, upload.approvalDigest) }.uncertain)
        assertEquals(0, writes)
    }
    private class MemoryJournal : UploadJournal {
        var state: UploadCheckpoint? = null
        override fun reserve(upload: SealedUpload): UploadCheckpoint { check(state == null); return UploadCheckpoint(upload.id, upload.digest, UploadPhase.PREPARED).also { state = it } }
        override fun store(checkpoint: UploadCheckpoint) { state = checkpoint }
    }
}
