package io.github.reihlinstinct.reporter.security

import java.security.SecureRandom

/** Inactive process-scoped, one-use request identity, NOT credential verification or Send authority.
 * Future Android wiring must own one instance outside Activity/remember state, call complete only
 * from its own credential RESULT_OK callback, and separately enforce Keystore/ledger/lock policies.
 * No Bundle/disk serialization. Losing this instance on process death rejects redelivered results. */
class ReviewRecoveryAuthRequest(private val elapsedMs: () -> Long) {
    enum class Operation { START_SESSION, RESTORE_SESSION }
    class Ticket internal constructor(
        internal val nonce: String,
        internal val sessionId: String,
        internal val reportId: String,
        internal val operation: Operation,
        internal val createdMs: Long,
        internal val deadlineMs: Long,
    ) { override fun toString() = "ReviewRecoveryAuthTicket(redacted)" }
    private var pending: Ticket? = null

    /** Replaces any earlier prompt identity. No unwrap permission until complete succeeds. */
    @Synchronized fun begin(binding: ReviewRecoveryEnvelope.Binding, operation: Operation): Ticket {
        pending = null
        val now = elapsedMs()
        require(now >= 0 && now <= Long.MAX_VALUE - TIMEOUT_MS) { "Invalid recovery auth time" }
        val random = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = random.joinToString("") { "%02x".format(it) }
        random.fill(0)
        return Ticket(nonce, binding.sessionId, binding.reportId, operation, now, now + TIMEOUT_MS)
            .also { pending = it }
    }

    /** Call on navigation, session replacement or explicit cancellation, never rotation/process ON_STOP.
     * ON_STOP still revokes the *writer/key* separately, including when a credential prompt covers the app. */
    @Synchronized fun invalidate() { pending = null }

    /** Matching callbacks consume before any caller can unwrap, even canceled/locked/late callbacks.
     * A stale callback cannot consume a newer prompt. Ticket identity is process-local, not just strings.
     * Foreground/unlocked booleans are caller checks, not facts established by this pure class. */
    @Synchronized fun complete(ticket: Ticket, binding: ReviewRecoveryEnvelope.Binding,
        operation: Operation, credentialResultOk: Boolean, foreground: Boolean, unlocked: Boolean): Boolean {
        val current = pending ?: return false
        if (current !== ticket) return false
        pending = null
        val now = elapsedMs()
        return credentialResultOk && foreground && unlocked &&
            now >= current.createdMs && now < current.deadlineMs &&
            binding.sessionId == current.sessionId && binding.reportId == current.reportId &&
            operation == current.operation
    }
    companion object { const val TIMEOUT_MS = 2 * 60 * 1000L }
}
