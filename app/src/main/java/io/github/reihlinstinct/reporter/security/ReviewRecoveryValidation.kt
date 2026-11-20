package io.github.reihlinstinct.reporter.security

import io.github.reihlinstinct.reporter.core.ReviewDraft
import java.io.File
import java.security.MessageDigest

/** Local-only restore admission, no decoded recovery fields used to choose a file. Call off Main with a fresh
 * ledger read and fresh submission-journal existence check. This neither authenticates nor authorizes Send.
 * A journal at ANY stage (including prepared) conservatively rejects recovery rather than guessing safety. */
object ReviewRecoveryValidation {
    fun matches(binding: ReviewRecoveryEnvelope.Binding, baseline: ReviewDraft, revision: Long,
        ledgerStableId: String, ledgerOriginalHash: String, originalRelativePath: String,
        noBackupDirectory: File, submissionArtifactExists: Boolean): Boolean = try {
        if (submissionArtifactExists || revision != binding.expectedRevision ||
            ledgerStableId != binding.reportId || ledgerOriginalHash != binding.originalHash ||
            baseline.evidence.stableId != ledgerStableId || baseline.evidence.originalSha256 != ledgerOriginalHash ||
            baseline.digest() != binding.initialDigest) false
        else {
            val root = noBackupDirectory.canonicalFile
            // Existing ledger path is a lead, not an authority to read outside app-owned originals.
            val originals = File(root, "originals").canonicalFile
            val original = File(root, originalRelativePath).canonicalFile
            if (!originals.path.startsWith(root.path + File.separator) ||
                !original.path.startsWith(originals.path + File.separator) || !original.isFile) false
            else {
                val digest = MessageDigest.getInstance("SHA-256")
                original.inputStream().use { input ->
                    val bytes = ByteArray(8192)
                    try {
                        while (true) {
                            val count = input.read(bytes)
                            if (count < 0) break
                            digest.update(bytes, 0, count)
                        }
                    } finally { bytes.fill(0) }
                }
                digest.digest().joinToString("") { "%02x".format(it) } == ledgerOriginalHash
            }
        }
    } catch (_: Exception) { false }
}
