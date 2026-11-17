package io.github.reihlinstinct.reporter.security

/** Inactive serialized commit boundary, not a key/auth owner. One process-wide instance per fixed slot.
 * Caller captures immutable state on Main, encrypts off Main, then commits only through this boundary.
 * The port must persist only ciphertext; all methods may block and must run on I/O, never Main.
 * No debounce/background flush/Keystore/lifecycle wiring exists yet. */
class ReviewRecoveryWriter(
    private val slot: Slot,
    private val elapsedMs: () -> Long,
    private val bootCount: () -> Int?,
    private val appVersionCode: () -> Int,
    private val deviceUnlocked: () -> Boolean,
) {
    interface Slot {
        /** Returns a private staged-write handle. Must preserve prior committed bytes on failure. */
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
    private var session: Session? = null
    private var latestSequence = 0L
    private var committedSequence = 0L

    /** New session after the future auth owner succeeds. Retires previous writer before deletion.
     * A failed cleanup leaves no writable session, never reuses the previous key/deadline. */
    fun start(binding: ReviewRecoveryEnvelope.Binding): Session = synchronized(mutex) {
        session = null
        slot.deleteAll()
        check(fresh(binding) && deviceUnlocked()) { "Recovery writer unavailable" }
        latestSequence = 0
        committedSequence = 0
        Session(binding).also { session = it }
    }

    /** Every captured edit gets a new token before encryption, fencing older encryptions immediately. */
    fun capture(current: Session): Edit = synchronized(mutex) {
        check(session === current && fresh(current.binding) && deviceUnlocked()) { "Recovery writer unavailable" }
        check(latestSequence < Long.MAX_VALUE) { "Recovery writer exhausted" }
        Edit(current, ++latestSequence)
    }

    /** Owns and clears the supplied ciphertext buffer on every path. Never accepts plaintext.
     * Stage and final checks/finish share the terminal-cleanup mutex, so deletion cannot race finish.
     * OS lock changes cannot be atomic with app I/O; recheck immediately before finish. */
    fun commit(edit: Edit, ciphertext: ByteArray): Boolean {
        try {
            require(ciphertext.size in 30..ReviewRecoveryEnvelope.MAX_ENVELOPE_BYTES && ciphertext[0] == 1.toByte()) {
                "Invalid recovery ciphertext"
            }
            return synchronized(mutex) {
                fun eligible() = session === edit.session && edit.sequence == latestSequence && edit.sequence > committedSequence &&
                    fresh(edit.session.binding) && deviceUnlocked()
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
