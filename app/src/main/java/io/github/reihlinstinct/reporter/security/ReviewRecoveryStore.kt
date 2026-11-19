package io.github.reihlinstinct.reporter.security

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** Inactive file adapter for [ReviewRecoveryWriter.Slot] (#358 slice 2). Pass `context.noBackupFilesDir`.
 * One fixed filename (no report id or address in names). Never writes plaintext. Commit = fsync'd `.new`
 * then rename over the base file, so a failed stage/finish leaves the prior committed checkpoint.
 * Unlink is logical deletion only; the per-session Keystore alias is the cryptographic deletion boundary.
 * The writer's mutex serializes stage/finish/deleteAll; [read] and [sweep] must run on the I/O thread and are
 * also synchronized on this store. */
class ReviewRecoveryStore(private val dir: File) : ReviewRecoveryWriter.Slot {
    private val base = File(dir, NAME)
    private val staging = File(dir, "$NAME.new")
    private val legacyBackup = File(dir, "$NAME.bak")
    private var binding: ReviewRecoveryEnvelope.Binding? = null
    private var wrapped: ByteArray? = null

    /** Registers the session identity and wrapped key that every staged checkpoint is stored with. */
    @Synchronized fun begin(binding: ReviewRecoveryEnvelope.Binding, wrappedKey: ByteArray) {
        this.binding = binding; this.wrapped = wrappedKey.copyOf()
    }

    @Synchronized override fun stage(ciphertext: ByteArray): ReviewRecoveryWriter.Pending {
        val b = checkNotNull(binding) { "Recovery store has no session" }
        val w = checkNotNull(wrapped)
        val bytes = ReviewRecoveryContainer.encode(b, w, ciphertext) // copies the ciphertext
        try {
            FileOutputStream(staging).use { out -> out.write(bytes); out.fd.sync() }
        } catch (e: IOException) {
            staging.delete(); throw e
        } finally { bytes.fill(0) }
        return object : ReviewRecoveryWriter.Pending {
            override fun finish() = synchronized(this@ReviewRecoveryStore) {
                if (!staging.renameTo(base)) { staging.delete(); throw IOException("Recovery commit failed") }
            }
            override fun abort() = synchronized(this@ReviewRecoveryStore) { staging.delete(); Unit }
        }
    }

    @Synchronized override fun deleteAll() {
        binding = null; wrapped?.fill(0); wrapped = null
        val failed = listOf(base, staging, legacyBackup).filter { it.exists() && !it.delete() }
        if (failed.isNotEmpty()) throw IOException("Recovery cleanup failed")
    }

    /** Null when there is no committed checkpoint or it is unreadable/oversized/corrupt (caller discards). */
    @Synchronized fun read(): ReviewRecoveryContainer.Parsed? {
        if (!base.isFile) return null
        if (base.length() > ReviewRecoveryContainer.MAX_FILE_BYTES) return null
        return try {
            ReviewRecoveryContainer.decode(FileInputStream(base).use { it.readBytes() })
        } catch (e: IOException) { null }
    }

    /** Startup/entry sweep. Never opens a report. Returns the live session id to keep, or null after deleting
     * expired, corrupt, wrong-boot, wrong-version or orphaned artifacts. */
    @Synchronized fun sweep(nowElapsedMs: Long, bootCount: Int?, appVersionCode: Int): String? {
        staging.delete(); legacyBackup.delete() // never-committed or legacy leftovers
        if (!base.exists()) return null
        val parsed = read()
        if (parsed == null || !ReviewRecoveryEnvelope.isFresh(parsed.binding, nowElapsedMs, bootCount, appVersionCode)) {
            deleteAll(); return null
        }
        return parsed.binding.sessionId
    }

    companion object { const val NAME = "review-recovery.bin" }
}
