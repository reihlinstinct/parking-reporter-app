package il.sidewalks.reporter.security

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Plaintext record of WHICH municipal credential fields are unset - field IDs only,
 *  never values - so the review screen can disable send without an unlock-gated
 *  settings read. A missing file means "unknown" (installs from before this record
 *  existed) and keeps the old answer-after-unlock behavior until the next save. */
class SettingsProfileStore(private val file: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "settings-profile.txt"))

    /** Atomic write (as SecureSettings does): a failed save never leaves a
     *  half-written record, and the previous answer stays intact. */
    fun save(missing: List<String>) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(missing.joinToString("\n").toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    fun read(): List<String>? =
        if (!file.isFile) null else file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
}
