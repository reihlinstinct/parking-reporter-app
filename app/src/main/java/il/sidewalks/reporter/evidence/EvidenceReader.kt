package il.sidewalks.reporter.evidence

import androidx.exifinterface.media.ExifInterface
import il.sidewalks.reporter.core.Evidence
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** File is the immutable original, never a compressed preview. */
class EvidenceReader {
    fun read(original: File, stableId: String = UUID.randomUUID().toString()): Evidence {
        val digest = MessageDigest.getInstance("SHA-256")
        original.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val exif = try { ExifInterface(original) } catch (_: Exception) { null }
        val coordinates = exif?.latLong
        val rawTime = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        val time = try { rawTime?.let { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")).toString() } } catch (_: Exception) { null }
        // EXIF time has no guaranteed timezone; UI must ask before review.
        return Evidence(stableId, hash, time, coordinates?.get(0), coordinates?.get(1))
    }
}
