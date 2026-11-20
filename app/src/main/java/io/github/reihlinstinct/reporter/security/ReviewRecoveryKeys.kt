package io.github.reihlinstinct.reporter.security

/** Inactive port for the per-session wrapping key (#358 slice 2). The data key itself is random 32 bytes generated
 * by the controller. Production implementation: [AndroidReviewRecoveryKeys]. Tests use a software fake. */
interface ReviewRecoveryKeys {
    /** Wrap [dataKey] under a fresh per-session alias. Needs recent in-app credential result on the real impl. */
    fun wrap(sessionId: String, dataKey: ByteArray): ByteArray
    /** Unwrap. Throws [KeyException] (permanent = true when the key is gone or invalidated). */
    fun unwrap(sessionId: String, wrapped: ByteArray): ByteArray
    /** Delete every recovery alias, or every one except [keep]. Idempotent. */
    fun deleteAll(keep: String? = null)

    /** [permanent] means the checkpoint is unusable and must be discarded; otherwise keep it and allow retry. */
    class KeyException(val permanent: Boolean, message: String) : Exception(message)
}
