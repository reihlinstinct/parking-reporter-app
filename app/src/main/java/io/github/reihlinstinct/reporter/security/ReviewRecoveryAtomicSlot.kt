package io.github.reihlinstinct.reporter.security

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException

/** Inactive Android ciphertext slot. One instance owned by the process-wide ReviewRecoveryWriter.
 * Blocking I/O only, never Main. No keys, auth, automatic startup, schema/header or production caller.
 * Caller must authenticate and validate all binding/retention state before using readCiphertext().
 * The fixed path excludes identifying names and lives outside backup/transfer roots. */
class ReviewRecoveryAtomicSlot(context: Context) : ReviewRecoveryWriter.Slot {
    private val base = File(context.applicationContext.noBackupFilesDir, FILE_NAME)
    private val atomic = AtomicFile(base)
    private var pending = false

    /** Must run under the same owner mutex as commit/deletion; rejects excess input before allocation.
     * openRead may recover AtomicFile's legacy .bak and remove stale .new; not a retention/auth grant. */
    fun readCiphertext(): ByteArray? {
        check(!pending) { "Recovery write in progress" }
        if (!base.exists() && !File(base.path + ".bak").exists()) return null
        return atomic.openRead().use { input ->
            val bytes = input.readBytesBounded(ReviewRecoveryEnvelope.MAX_ENVELOPE_BYTES)
            if (bytes.size !in 30..ReviewRecoveryEnvelope.MAX_ENVELOPE_BYTES || bytes[0] != 1.toByte()) {
                bytes.fill(0)
                throw IOException("Invalid recovery ciphertext")
            }
            bytes
        }
    }

    override fun stage(ciphertext: ByteArray): ReviewRecoveryWriter.Pending {
        check(!pending) { "Recovery write in progress" }
        require(ciphertext.size in 30..ReviewRecoveryEnvelope.MAX_ENVELOPE_BYTES && ciphertext[0] == 1.toByte()) {
            "Invalid recovery ciphertext"
        }
        // Keep only encrypted previous bytes for rollback if finishWrite silently fails. No plaintext.
        val previous = readCiphertext()
        val expected = ciphertext.copyOf()
        val stream = try { atomic.startWrite() } catch (failure: Throwable) {
            previous?.fill(0); expected.fill(0); throw failure
        }
        pending = true
        try { stream.write(ciphertext); stream.fd.sync() }
        catch (failure: Throwable) {
            try { atomic.failWrite(stream) } finally {
                pending = false; previous?.fill(0); expected.fill(0)
            }
            throw failure
        }
        return object : ReviewRecoveryWriter.Pending {
            private var resolved = false
            override fun finish() {
                check(!resolved) { "Recovery write already resolved" }
                // AtomicFile.finishWrite logs some failures rather than throwing. Check the resulting
                // bytes before reporting success. A failed verification never claims a durable backup.
                try {
                    atomic.finishWrite(stream)
                    resolved = true; pending = false
                    val actual = readCiphertext()
                    try {
                        if (actual == null || !actual.contentEquals(expected)) throw IOException("Recovery commit unverified")
                    } finally { actual?.fill(0) }
                } catch (failure: Throwable) {
                    // Restore only encrypted prior contents. Failure to restore is material and
                    // propagated, never disguised as a successful backup or deletion.
                    try {
                        if (previous == null) deleteAll()
                        else {
                            val rollback = atomic.startWrite()
                            try { rollback.write(previous); rollback.fd.sync(); atomic.finishWrite(rollback) }
                            catch (restore: Throwable) { atomic.failWrite(rollback); throw restore }
                            val restored = readCiphertext()
                            try {
                                if (restored == null || !restored.contentEquals(previous)) throw IOException("Recovery rollback unverified")
                            } finally { restored?.fill(0) }
                        }
                    } catch (restore: Throwable) { failure.addSuppressed(restore) }
                    throw failure
                } finally { previous?.fill(0); expected.fill(0) }
            }
            override fun abort() {
                if (!resolved) {
                    try { atomic.failWrite(stream) }
                    finally { resolved = true; pending = false; previous?.fill(0); expected.fill(0) }
                }
            }
        }
    }

    override fun deleteAll() {
        check(!pending) { "Recovery write in progress" }
        var failed = false
        for (file in listOf(base, File(base.path + ".new"), File(base.path + ".bak"))) {
            if (file.exists() && !file.delete()) failed = true
        }
        if (failed) throw IOException("Recovery cleanup incomplete")
    }

    private fun java.io.InputStream.readBytesBounded(max: Int): ByteArray {
        // Fixed cap, independent of the untrusted file's declared length or later growth.
        val buffer = ByteArray(max + 1)
        return try {
            var n = 0
            while (n < buffer.size) {
                val read = read(buffer, n, buffer.size - n)
                if (read < 0) break
                if (read == 0) throw IOException("Recovery read stalled")
                n += read
            }
            if (n > max) throw IOException("Recovery ciphertext too large")
            buffer.copyOf(n)
        } finally { buffer.fill(0) }
    }
    companion object { internal const val FILE_NAME = "review-recovery-v1.enc" }
}
