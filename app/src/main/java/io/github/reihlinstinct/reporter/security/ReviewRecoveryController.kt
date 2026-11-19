package io.github.reihlinstinct.reporter.security

import java.io.IOException
import java.security.SecureRandom

/** UNCOMPILED, inactive, disabled by default (#358 slice 3). Session, autosave and restore policy with every
 * platform dependency injected, so it is unit-testable on the JVM. No UI, no copy, no Bundle, no Send.
 * Nothing here marks a report approved or sends anything. When [enabled] is false no key, file or alias is
 * created. Threading: [onEdit], [terminal], [revoke], [onProcessStop], [beginStart], [beginRestore] run on Main
 * and never block on file I/O; [completeStart]/[completeRestore]/[startupSweep] do I/O and must run off Main.
 * Memory-only fallbacks: no secure lock, auth cancel, Keystore failure, over-limit input. */
class ReviewRecoveryController(
    private val enabled: () -> Boolean,
    private val deviceSecure: () -> Boolean,
    private val deviceUnlocked: () -> Boolean,
    private val store: ReviewRecoveryStore,
    private val keys: ReviewRecoveryKeys,
    private val writer: ReviewRecoveryWriter,
    private val auth: ReviewRecoveryAuthRequest,
    private val elapsedMs: () -> Long,
    private val bootCount: () -> Int?,
    private val appVersionCode: () -> Int,
    private val newSessionId: () -> String = ::randomSessionId,
    /** Runs a task off Main, in order. */
    private val io: (Runnable) -> Unit,
    /** Schedules a task after a delay and returns its canceller. Debounce is a policy, 300 ms in production. */
    private val schedule: (Long, Runnable) -> (() -> Unit),
    private val debounceMs: Long = DEBOUNCE_MS,
    private val stopGraceMs: Long = STOP_GRACE_MS,
) {
    enum class EditResult { NOT_ACTIVE, QUEUED, MEMORY_ONLY_TOO_LARGE, MEMORY_ONLY_FAILED }
    class StartRequest internal constructor(internal val ticket: ReviewRecoveryAuthRequest.Ticket,
        internal val binding: ReviewRecoveryEnvelope.Binding)
    class RestoreRequest internal constructor(internal val ticket: ReviewRecoveryAuthRequest.Ticket,
        internal val binding: ReviewRecoveryEnvelope.Binding)

    private val lock = Any()
    private var session: ReviewRecoveryWriter.Session? = null
    private var binding: ReviewRecoveryEnvelope.Binding? = null
    private var key: ByteArray? = null
    private var pendingPlain: ByteArray? = null
    private var pendingEdit: ReviewRecoveryWriter.Edit? = null
    private var cancelDebounce: (() -> Unit)? = null
    @Volatile var lastWriteFailed = false; private set
    val isActive: Boolean get() = synchronized(lock) { session != null }

    /** Step 1 of a new session. Null means stay memory-only (disabled, no secure lock, or locked). The caller
     * launches its own confirm-device-credential flow with the returned request and reports the result to
     * [completeStart]. */
    fun beginStart(reportId: String, initialDigest: String, originalHash: String, revision: Long): StartRequest? {
        if (!enabled() || !deviceSecure() || !deviceUnlocked()) return null
        val boot = bootCount() ?: return null
        val now = elapsedMs()
        val b = try {
            ReviewRecoveryEnvelope.Binding(newSessionId(), reportId, initialDigest, originalHash, revision,
                appVersionCode(), boot, now, now + ReviewRecoveryEnvelope.LIFETIME_MS)
        } catch (e: IllegalArgumentException) { return null }
        return StartRequest(auth.begin(b, ReviewRecoveryAuthRequest.Operation.START_SESSION), b)
    }

    /** Step 2, off Main, only from the app's own RESULT_OK callback. Replaces any earlier session first. */
    fun completeStart(request: StartRequest, credentialResultOk: Boolean, foreground: Boolean): Boolean {
        if (!auth.complete(request.ticket, request.binding, ReviewRecoveryAuthRequest.Operation.START_SESSION,
                credentialResultOk, foreground, deviceUnlocked())) return false
        if (!enabled()) return false
        endSession(deleteArtifacts = true)
        val dataKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            keys.deleteAll()
            val wrapped = keys.wrap(request.binding.sessionId, dataKey)
            val s = writer.start(request.binding)
            store.begin(request.binding, wrapped)
            synchronized(lock) { session = s; binding = request.binding; key = dataKey.copyOf() }
            lastWriteFailed = false
            return true
        } catch (e: Throwable) {
            endSession(deleteArtifacts = true)
            return false
        } finally { dataKey.fill(0) }
    }

    /** Main thread, in snapshot order. Encodes immediately, captures a fencing token, then debounces the write.
     * Over-limit or failing input keeps the previous committed checkpoint and the user keeps editing in memory. */
    fun onEdit(snapshot: ReviewRecoveryCodec.Snapshot): EditResult {
        val s: ReviewRecoveryWriter.Session
        synchronized(lock) { s = session ?: return EditResult.NOT_ACTIVE }
        val b = synchronized(lock) { binding } ?: return EditResult.NOT_ACTIVE
        if (!ReviewRecoveryEnvelope.isFresh(b, elapsedMs(), bootCount(), appVersionCode())) {
            terminal(); return EditResult.NOT_ACTIVE
        }
        val plain = try { ReviewRecoveryCodec.encode(snapshot) }
            catch (e: IllegalArgumentException) { return EditResult.MEMORY_ONLY_TOO_LARGE }
        val edit = try { writer.capture(s) } catch (e: IllegalStateException) {
            plain.fill(0); return EditResult.MEMORY_ONLY_FAILED
        }
        synchronized(lock) {
            if (session !== s) { plain.fill(0); return EditResult.NOT_ACTIVE }
            pendingPlain?.fill(0); pendingPlain = plain; pendingEdit = edit
            cancelDebounce?.invoke()
            cancelDebounce = schedule(debounceMs, Runnable { io(Runnable { flush() }) })
        }
        return EditResult.QUEUED
    }

    /** I/O thread. Seals the newest pending state with its own key copy and commits through the writer. A stale or
     * revoked flush is rejected by the writer; nothing here can outlive a terminal event. */
    private fun flush() {
        val edit: ReviewRecoveryWriter.Edit; val plain: ByteArray; val k: ByteArray; val b: ReviewRecoveryEnvelope.Binding
        synchronized(lock) {
            edit = pendingEdit ?: return; plain = pendingPlain ?: return
            k = key?.copyOf() ?: run { pendingPlain?.fill(0); pendingPlain = null; pendingEdit = null; return }
            b = binding ?: return
            pendingPlain = null; pendingEdit = null
        }
        try {
            val sealed = ReviewRecoveryEnvelope.seal(plain, k, b, elapsedMs(), bootCount(), appVersionCode())
            if (writer.commit(edit, sealed)) lastWriteFailed = false // false = fenced/stale, not a failure
        } catch (e: Throwable) {
            lastWriteFailed = true // previous valid checkpoint stays within the original deadline; text is not logged
        } finally { plain.fill(0); k.fill(0) }
    }

    /** Process-foreground ON_STOP (also while a credential prompt covers the app). NOT for rotation: check
     * isChangingConfigurations first. Fences new edits at once, flushes the newest captured state on I/O within
     * [stopGraceMs], then clears the key. Does not invalidate an outstanding auth ticket. */
    fun onProcessStop() {
        synchronized(lock) { if (session == null) return; cancelDebounce?.invoke(); cancelDebounce = null }
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        io(Runnable { try { flush() } finally { done.set(true); revoke() } })
        schedule(stopGraceMs, Runnable { if (!done.get()) revoke() }) // timeout: fence and clear key even if I/O is stuck
    }

    /** Screen-off or lock: stricter than stop. Clears the key and fences the writer immediately; no final write.
     * Keeps the last committed ciphertext. */
    fun revoke() {
        synchronized(lock) {
            cancelDebounce?.invoke(); cancelDebounce = null
            pendingPlain?.fill(0); pendingPlain = null; pendingEdit = null
            key?.fill(0); key = null; session = null
        }
        writer.revoke()
    }

    /** Explicit Save success, Discard, leaving the report, replacement, Send start, expiry. Invalidates the
     * generation before deleting, cancels queued work, deletes the file and the alias. Safe to call twice.
     * Send start must not be blocked if deletion fails; deletion errors are swallowed here and the restore path
     * independently rejects stale checkpoints. */
    fun terminal() { auth.invalidate(); endSession(deleteArtifacts = true) }

    private fun endSession(deleteArtifacts: Boolean) {
        revoke()
        synchronized(lock) { binding = null }
        if (deleteArtifacts) {
            try { writer.retire() } catch (e: Throwable) { /* fenced already; sweep retries */ }
            try { store.deleteAll() } catch (e: Throwable) { }
            try { keys.deleteAll() } catch (e: Throwable) { }
        }
    }

    /** App entry/startup, off Main. Deletes expired/corrupt/mismatched artifacts and orphan aliases. Never opens a report. */
    fun startupSweep() {
        val live = try { store.sweep(elapsedMs(), bootCount(), appVersionCode()) } catch (e: Throwable) { null }
        try { keys.deleteAll(keep = live) } catch (e: Throwable) { }
    }

    /** Explicit review open. Null when there is nothing restorable or recovery is off. Does not decrypt. */
    fun beginRestore(): RestoreRequest? {
        if (!enabled() || !deviceSecure()) return null
        val parsed = store.read() ?: return null
        if (!ReviewRecoveryEnvelope.isFresh(parsed.binding, elapsedMs(), bootCount(), appVersionCode())) {
            endSession(deleteArtifacts = true); return null
        }
        return RestoreRequest(auth.begin(parsed.binding, ReviewRecoveryAuthRequest.Operation.RESTORE_SESSION), parsed.binding)
    }

    /** Off Main, only from the app's own RESULT_OK callback. [validate] must re-read the saved ledger row and
     * require the exact revision, initial digest and original hash through the canonical-path validation, and must
     * return false if submission state is started/partial/uncertain/submitted. Any failure discards recovery only;
     * the saved report is untouched. The returned snapshot is an unapproved user edit, never permission to send.
     * Continues the SAME session and deadline (no extension) with the same wrapped key, and rewrites the
     * restored state immediately. */
    fun completeRestore(request: RestoreRequest, credentialResultOk: Boolean, foreground: Boolean,
        validate: (ReviewRecoveryEnvelope.Binding) -> Boolean): ReviewRecoveryCodec.Snapshot? {
        if (!auth.complete(request.ticket, request.binding, ReviewRecoveryAuthRequest.Operation.RESTORE_SESSION,
                credentialResultOk, foreground, deviceUnlocked())) return null // cancel/late/stale: keep memory-only
        if (!enabled() || !deviceUnlocked()) return null
        val parsed = store.read() ?: return null
        val b = parsed.binding
        if (b.sessionId != request.binding.sessionId ||
            !ReviewRecoveryEnvelope.isFresh(b, elapsedMs(), bootCount(), appVersionCode())) {
            discard(); return null
        }
        if (!(try { validate(b) } catch (e: Throwable) { false })) { discard(); return null }
        var dataKey: ByteArray? = null; var plain: ByteArray? = null
        try {
            dataKey = try { keys.unwrap(b.sessionId, parsed.wrappedKey) }
                catch (e: ReviewRecoveryKeys.KeyException) { if (e.permanent) discard(); return null }
            plain = try { ReviewRecoveryEnvelope.open(parsed.envelope, dataKey, b, elapsedMs(), bootCount(), appVersionCode()) }
                catch (e: Exception) { discard(); return null }
            val snapshot = try { ReviewRecoveryCodec.decode(plain) }
                catch (e: IllegalArgumentException) { discard(); return null }
                catch (e: IOException) { discard(); return null }
            val s = try { writer.start(b) } catch (e: Throwable) { discard(); return null }
            store.begin(b, parsed.wrappedKey)
            synchronized(lock) { session = s; binding = b; key = dataKey.copyOf() }
            lastWriteFailed = false
            val edit = writer.capture(s)
            val sealed = ReviewRecoveryEnvelope.seal(plain, dataKey, b, elapsedMs(), bootCount(), appVersionCode())
            if (!writer.commit(edit, sealed)) lastWriteFailed = true
            return snapshot
        } catch (e: Throwable) {
            endSession(deleteArtifacts = false); return null
        } finally { dataKey?.fill(0); plain?.fill(0) }
    }

    private fun discard() = endSession(deleteArtifacts = true)

    companion object {
        const val DEBOUNCE_MS = 300L
        const val STOP_GRACE_MS = 1000L
        fun randomSessionId(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
    }
}
