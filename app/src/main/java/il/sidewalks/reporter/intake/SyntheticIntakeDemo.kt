package il.sidewalks.reporter.intake

import android.graphics.Bitmap
import com.google.crypto.tink.*
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.hybrid.HybridKeyTemplates
import com.google.crypto.tink.signature.SignatureConfig
import com.google.crypto.tink.signature.SignatureKeyTemplates
import il.sidewalks.reporter.core.*
import il.sidewalks.reporter.security.ReporterSettings
import java.io.ByteArrayOutputStream

/** Fully offline. No stored profiles, originals, enrollment, auth or network port accessed. */
object SyntheticIntakeDemo {
    fun run(): String {
        HybridConfig.register(); SignatureConfig.register()
        val receiver = KeysetHandle.generateNew(HybridKeyTemplates.ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM)
        val device = KeysetHandle.generateNew(SignatureKeyTemplates.ECDSA_P256)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val jpeg = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() }; bitmap.recycle()
        val draft = ReviewDraft(Evidence("synthetic-${java.util.UUID.randomUUID()}", EncryptedPackage.sha256(jpeg), "2026-01-01T12:00:00Z", 31.0, 35.0),
            "1234567", "רחוב בדיקה 1", "sidewalk_parking", "דיווח סינתטי בלבד", emptySet()).approve()
        val profile = ReporterSettings("synthetic", "reporter", "0000000", "000000000", "synthetic@example.invalid")
        val report = EncryptedPackage.reviewedReport(draft, profile, jpeg)
        val upload = EncryptedPackage.seal(report, jpeg, EncryptedPackage.approvalDigest(report), "synthetic-receiver", "synthetic-device",
            receiver.publicKeysetHandle.getPrimitive(HybridEncrypt::class.java), device.getPrimitive(PublicKeySign::class.java))
        var stored: UploadCheckpoint? = null
        val journal = object : UploadJournal {
            override fun reserve(upload: SealedUpload): UploadCheckpoint { check(stored == null); return UploadCheckpoint(upload.id, upload.digest, UploadPhase.PREPARED).also { stored = it } }
            override fun store(checkpoint: UploadCheckpoint) { stored = checkpoint }
        }
        var comments = 0
        val localPort = object : IntakeWritePort {
            override fun create(title: String, manifest: String) = 1L
            override fun comment(issue: Long, body: String) { comments++ }
        }
        val result = IntakeUploader(localPort, journal).send(upload, upload.approvalDigest)
        check(result.phase == UploadPhase.UPLOADED && comments == upload.count + 1)
        return "הבדיקה הסינתטית הצליחה: חבילה מוצפנת וחתומה, ${upload.count} חלקים. הכול מקומי, לא נשלח לרשת."
    }
}
