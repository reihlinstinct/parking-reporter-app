package io.github.reihlinstinct.reporter.security

import java.util.concurrent.atomic.AtomicLong

/** Inactive serialized commit boundary, not a key/auth owner. One process-wide instance per fixed slot.
 * Caller captures immutable state on Main and calls [capture] on that same thread, in snapshot order:
 * token allocation is non-blocking (volatile session read + atomic counter) and never touches the I/O
 * mutex, so the token order always follows the snapshot order. Encryption runs off Main, then commits
 * only through this boundary. The port must persist only ciphertext; [start], [commit], [revoke] and
 * [retire] may block on file I/O and must run on I/O, never Main.
 * No debounce/background flush/Keystore/lifecycle wiring exists yet. */
class ReviewRecoveryWriter(
    private val slot: Slot,
    private val elapsedMs: () -> Long,
    private val bootCount: () -> Int?,
    private val appVersionCode: () -> Int,
    private val deviceUnlocked: () -> Boolean,
) {
    interface Slot {
        /** Returns a private staged-write handle. Must preserve prior committed bytes on failure.
         * Must not retain [ciphertext] after the returned [Pending]'s finish/abort returns: the
         * boundary zeroes the buffer immediately afterwards, so a port that writes asynchronously
         * or keeps a reference would persist zeros. Copy the bytes before returning. */
        fun stage(ciphertext: ByteArray): Pending
        fun deleteAll()
    }
    interface Pending {
        /** Must make staged ciphertext durable or throw, preserving prior valid data on failure. */
        fun finish()
        /** Idempotent; must be a no-op after successful finish, not roll back committed data. */
        fun abort()
    }
    class Session internal constructor(internal val binding: ReviewRecoveryEnvelope.Binding) {
        override fun toString() = "ReviewRecoveryWriterSession(redacted)"
    }
    class Edit internal constructor(internal val session: Session, internal val sequence: Long) {
        override fun toString() = "ReviewRecoveryWriterEdit(redacted)"
    }
    private val mutex = Any()
    @Volatile private var session: Session? = null
    private val latestSequence = AtomicLong(0)
    private var committedSequence = 0L

    /** New session after the future auth owner succeeds. Retires previous writer before deletion.
     * A failed cleanup leaves no writable session, never reuses the previous key/deadline.
     * The token counter resets before the new session is published, so a capture that sees the new
     * session also sees the reset counter; a stale in-flight capture of the previous session can
     * only burn a token, never commit, because [commit] re-checks session identity. */
    fun start(binding: ReviewRecoveryEnvelope.Binding): Session = synchronized(mutex) {
        session = null
        slot.deleteAll()
        check(fresh(binding) && deviceUnlocked()) { "Recovery writer unavailable" }
        committedSequence = 0
        Session(binding).also {
            latestSequence.set(0)
            session = it
        }
    }

    /** Re-authorized existing checkpoint. The controller has already authenticated/decrypted and validated
     * the ledger. Unlike replacement, a failed resume/write must retain the previous committed file. */
    internal fun resume(binding: ReviewRecoveryEnvelope.Binding): Session = synchronized(mutex) {
        session = null
        check(fresh(binding) && deviceUnlocked()) { "Recovery writer unavailable" }
        committedSequence = 0
        Session(binding).also { latestSequence.set(0); session = it }
    }

    /** Main-safe immediate fence. A final write already inside finish may complete, but the serialized
     * I/O retire runs after it and deletes it. No new capture/commit can start after this fence. */
    fun fence() { session = null }

    /** Every captured edit gets a new token before encryption, fencing older encryptions immediately.
     * Non-blocking: never waits on the I/O mutex, so it is safe on Main. Must be called on the
     * snapshot thread, in snapshot order, so the token order follows the snapshot order. */
    fun capture(current: Session): Edit {
        check(session === current && fresh(current.binding) && deviceUnlocked()) { "Recovery writer unavailable" }
        val sequence = latestSequence.incrementAndGet()
        check(sequence > 0L) { "Recovery writer exhausted" }
        return Edit(current, sequence)
    }

    /** Owns and clears the supplied ciphertext buffer on every path. Never accepts plaintext.
     * Stage and final checks/finish share the terminal-cleanup mutex, so deletion cannot race finish.
     * OS lock changes cannot be atomic with app I/O; recheck immediately before finish. The token
     * check reads the atomic counter under the mutex, so any newer [capture], from any thread,
     * fences this commit even while it is staged. */
    fun commit(edit: Edit, ciphertext: ByteArray): Boolean {
        try {
            require(ciphertext.size in ReviewRecoveryEnvelope.MIN_ENVELOPE_BYTES..ReviewRecoveryEnvelope.MAX_ENVELOPE_BYTES &&
                ciphertext[0] == 1.toByte()) { "Invalid recovery ciphertext" }
            return synchronized(mutex) {
                fun eligible() = session === edit.session && edit.sequence == latestSequence.get() &&
                    edit.sequence > committedSequence && fresh(edit.session.binding) && deviceUnlocked()
                if (!eligible()) return@synchronized false
                val pending = slot.stage(ciphertext)
                try {
                    if (!eligible()) { pending.abort(); return@synchronized false }
                    pending.finish()
                    committedSequence = edit.sequence
                    true
                } catch (failure: Throwable) {
                    try { pending.abort() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    throw failure
                }
            }
        } finally { ciphertext.fill(0) }
    }

    /** Screen-off/background key revocation: fences queued writes, keeps last committed ciphertext.
     * Caller separately cancels jobs, clears key/plaintext and eventually destroys the wrapping alias. */
    fun revoke() = synchronized(mutex) { session = null }

    /** Save/Discard/Send/navigation/expiry: invalidate BEFORE cleanup, even if deletion throws. */
    fun retire() = synchronized(mutex) { session = null; slot.deleteAll() }

    private fun fresh(binding: ReviewRecoveryEnvelope.Binding) = ReviewRecoveryEnvelope.isFresh(
        binding, elapsedMs(), bootCount(), appVersionCode())
}
