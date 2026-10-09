package il.sidewalks.reporter.evidence

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import il.sidewalks.reporter.intake.EncryptedPackage
import java.io.ByteArrayOutputStream
import java.io.File

class OutgoingImage(val jpeg: ByteArray, val originalHash: String, val hash: String,
                    val width: Int, val height: Int) {
    override fun toString() = "OutgoingImage(redacted)"
}
/** Separate derivative only. Never rewrites original. User must review this exact image before packaging. */
object OutgoingEvidence {
    fun prepare(original: File): OutgoingImage {
        val evidence = EvidenceReader().read(original, "outgoing-hash-check")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.path, bounds)
        require(bounds.outWidth in 1..20_000 && bounds.outHeight in 1..20_000)
        var sample = 1
        while (bounds.outWidth / sample > 2560 || bounds.outHeight / sample > 2560) sample *= 2
        var bitmap = BitmapFactory.decodeFile(original.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Cannot decode image")
        val orientation = ExifInterface(original).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        try {
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) { bitmap.recycle(); bitmap = rotated }
            }
            var quality = 90
            var jpeg: ByteArray
            do {
                jpeg = ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)); out.toByteArray() }
                quality -= 10
            } while (jpeg.size > 1_100_000 && quality >= 60)
            require(jpeg.size <= 1_100_000) { "Image too large; do not reduce evidence further silently" }
            return OutgoingImage(jpeg, evidence.originalSha256, EncryptedPackage.sha256(jpeg), bitmap.width, bitmap.height)
        } finally { bitmap.recycle() }
    }
}
