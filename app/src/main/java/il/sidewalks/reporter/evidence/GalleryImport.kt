package il.sidewalks.reporter.evidence

import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Gallery originals are copied byte-for-byte into the no-backup originals
 * directory; the picked image is never re-encoded. The system photo picker
 * requires no permissions.
 */
object GalleryImport {
    fun copyToOriginals(open: () -> InputStream?, originals: File, name: String = "${UUID.randomUUID()}.jpg"): File {
        check(originals.mkdirs() || originals.isDirectory) { "originals directory unavailable" }
        val target = File(originals, name)
        val temp = File(originals, "$name.tmp")
        try {
            requireNotNull(open()) { "gallery image unreadable" }.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            check(temp.renameTo(target)) { "gallery import failed" }
            return target
        } finally {
            temp.delete()
        }
    }

    /**
     * Deletes a copied original that never reached the ledger (for example a
     * refused duplicate reserve), so no EXIF-carrying file stays orphaned in
     * originals/. Idempotent: true when the file is gone afterwards.
     */
    fun deleteOrphaned(file: File): Boolean = file.delete() || !file.exists()

    /**
     * Deletes every original the ledger does not reference (for example a
     * copy whose import was interrupted by rotation or a process kill).
     * Returns the number of files removed; never touches referenced names.
     */
    fun sweepUnreferenced(originals: File, referencedNames: Set<String>): Int {
        val files = originals.listFiles() ?: return 0
        var removed = 0
        for (file in files) {
            if (file.isFile && file.name !in referencedNames && file.delete()) removed++
        }
        return removed
    }
}
