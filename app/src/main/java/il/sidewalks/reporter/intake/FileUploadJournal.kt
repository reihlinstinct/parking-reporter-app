package il.sidewalks.reporter.intake

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Stores ciphertext metadata only; uncertain/pre-write states never resume automatically. */
class FileUploadJournal(directory: File) : UploadJournal {
    private val root = File(directory, "intake-checkpoints").apply { check(mkdirs() || isDirectory) }
    private fun file(id: String): AtomicFile {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        return AtomicFile(File(root, "$id.json"))
    }
    @Synchronized override fun reserve(upload: SealedUpload): UploadCheckpoint {
        val lockFile = File(root, "journal.lock")
        return java.io.RandomAccessFile(lockFile, "rw").channel.use { channel ->
        channel.lock().use {
        val target = file(upload.id)
        check(!target.baseFile.exists()) { "Existing upload requires reconciliation" }
        // Global digest duplicates are blocked even under different package IDs.
        root.listFiles()?.filter { it.extension == "json" }?.forEach {
            check(JSONObject(AtomicFile(it).readFully().toString(Charsets.UTF_8)).getString("digest") != upload.digest)
        }
        UploadCheckpoint(upload.id, upload.digest, UploadPhase.PREPARED).also(::store)
        }
        }
    }
    @Synchronized override fun store(checkpoint: UploadCheckpoint) {
        val target = file(checkpoint.packageId)
        val json = JSONObject().put("package", checkpoint.packageId).put("digest", checkpoint.digest)
            .put("phase", checkpoint.phase.name).put("issue", checkpoint.issue ?: JSONObject.NULL).put("next", checkpoint.nextChunk)
        val stream = target.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); target.finishWrite(stream) }
        catch (error: Exception) { target.failWrite(stream); throw error }
    }
}
