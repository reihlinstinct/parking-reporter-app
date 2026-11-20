package io.github.reihlinstinct.reporter.security

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Policy tests with a software key fake. They are NOT Keystore, device-credential or process-death proof. */
class ReviewRecoveryControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeKeys : ReviewRecoveryKeys {
        val aliases = mutableMapOf<String, ByteArray>()
        var failUnwrap: ReviewRecoveryKeys.KeyException? = null
        override fun wrap(sessionId: String, dataKey: ByteArray): ByteArray {
            val kek = ByteArray(32).also { SecureRandom().nextBytes(it) }; aliases[sessionId] = kek
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"))
            return c.iv + c.doFinal(dataKey)
        }
        override fun unwrap(sessionId: String, wrapped: ByteArray): ByteArray {
            failUnwrap?.let { throw it }
            val kek = aliases[sessionId] ?: throw ReviewRecoveryKeys.KeyException(true, "missing")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, wrapped.copyOfRange(0, 12)))
            return c.doFinal(wrapped, 12, wrapped.size - 12)
        }
        override fun deleteAll(keep: String?) { aliases.keys.filter { it != keep }.forEach { aliases.remove(it) } }
    }

    private class Env(dir: File, val keys: FakeKeys = FakeKeys(), var now: Long = 10_000, var boot: Int? = 4) {
        var enabled = true; var secure = true; var unlocked = true
        data class Timer(val at: Long, val action: Runnable)
        val ioQueue = ArrayDeque<Runnable>(); val timers = mutableListOf<Timer>()
        val store = ReviewRecoveryStore(dir)
        val auth = ReviewRecoveryAuthRequest { now }
        val writer = ReviewRecoveryWriter(store, { now }, { boot }, { 197 }, { unlocked })
        var counter = 0
        val controller = ReviewRecoveryController({ enabled }, { secure }, { unlocked }, store, keys, writer, auth,
            { now }, { boot }, { 197 }, { (++counter).toString(16).padStart(32, '0') },
            { ioQueue.addLast(it) }, { delay, r -> val t = Timer(now + delay, r); timers.add(t); { timers.remove(t); Unit } })
        fun runTimers() { now += 300; val due = timers.filter { it.at <= now }; timers.removeAll(due.toSet()); due.forEach { it.action.run() } }
        fun runIo() { while (ioQueue.isNotEmpty()) ioQueue.removeFirst().run() }
        fun settle() { runTimers(); runIo() }
    }

    private val digest = "b".repeat(64); private val hash = "c".repeat(64)
    private fun snap(text: String = "synthetic second line", subject: String = "synthetic subject") =
        ReviewRecoveryCodec.Snapshot("1122334", "regular", false, "synthetic address", subject, text,
            setOf("address_needs_confirmation"), "2026-11-16T12:00:00Z", 31.75, 35.25, "CD 12-", 3, "31.75", "35.25",
            "raw street", "12a", "cross street", "saved street", "11", "7654321", true,
            true, true, true, "synthetic-tag", listOf(ReviewRecoveryCodec.Sentence("state.synthetic", "synthetic template")),
            true, "baseline street", setOf("suggested_address_needs_confirmation"))
    private fun start(e: Env): Boolean {
        val r = e.controller.beginStart("synthetic-1", digest, hash, 7) ?: return false
        return e.controller.completeStart(r, true, true)
    }
    private fun files(e: Env, dir: File) = dir.listFiles()!!.toList()

    @Test fun disabledOrNoSecureLockOrLockedCreatesNothing() {
        val e = Env(tmp.root); e.enabled = false
        assertNull(e.controller.beginStart("synthetic-1", digest, hash, 7))
        e.enabled = true; e.secure = false; assertNull(e.controller.beginStart("synthetic-1", digest, hash, 7))
        e.secure = true; e.unlocked = false; assertNull(e.controller.beginStart("synthetic-1", digest, hash, 7))
        e.unlocked = true; e.boot = null; assertNull(e.controller.beginStart("synthetic-1", digest, hash, 7))
        assertEquals(NOT_ACTIVE, e.controller.onEdit(snap())); assertTrue(files(e, tmp.root).isEmpty()); assertTrue(e.keys.aliases.isEmpty())
    }

    @Test fun cancelledOrReplayedCredentialResultStartsNothing() {
        val e = Env(tmp.root)
        val r = e.controller.beginStart("synthetic-1", digest, hash, 7)!!
        assertFalse(e.controller.completeStart(r, false, true)) // cancel consumes the ticket
        assertFalse(e.controller.completeStart(r, true, true)) // replay
        val r2 = e.controller.beginStart("synthetic-1", digest, hash, 7)!!
        e.now += ReviewRecoveryAuthRequest.TIMEOUT_MS
        assertFalse(e.controller.completeStart(r2, true, true)) // 2-minute prompt deadline
        assertFalse(e.controller.isActive); assertTrue(e.keys.aliases.isEmpty())
    }

    @Test fun autosaveWritesEncryptedAndRestoresAfterProcessDeathWithoutExtendingDeadline() {
        val e = Env(tmp.root)
        assertTrue(start(e))
        assertEquals(ReviewRecoveryController.EditResult.QUEUED, e.controller.onEdit(snap("first")))
        assertEquals(ReviewRecoveryController.EditResult.QUEUED, e.controller.onEdit(snap("synthetic final text")))
        e.settle()
        val bytes = File(tmp.root, "review-recovery.bin").readBytes()
        assertFalse("plaintext leaked", String(bytes, Charsets.ISO_8859_1).contains("synthetic subject"))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("synthetic final text"))
        // "Process death": brand-new controller, writer, auth holder; same disk and same (fake) keystore.
        e.now += 60_000
        val e2 = Env(tmp.root, e.keys, e.now, e.boot)
        e2.controller.startupSweep()
        assertEquals(1, e2.keys.aliases.size)
        val r = e2.controller.beginRestore()!!
        val got = e2.controller.completeRestore(r, true, true) { it.reportId == "synthetic-1" && it.expectedRevision == 7L }
        assertEquals("synthetic final text", got!!.text); assertEquals("CD 12-", got.plateRaw); assertEquals(3, got.plateEdits)
        e2.settle()
        val deadline = ReviewRecoveryContainer.decode(File(tmp.root, "review-recovery.bin").readBytes()).binding
        assertEquals(10_000 + ReviewRecoveryEnvelope.LIFETIME_MS, deadline.deadlineElapsedMs) // never extended
        e2.now = deadline.deadlineElapsedMs
        assertEquals(NOT_ACTIVE, e2.controller.onEdit(snap())); e2.runIo() // expired: terminal
        assertTrue(files(e2, tmp.root).isEmpty()); assertTrue(e2.keys.aliases.isEmpty())
    }

    @Test fun restoreCancelKeepsCheckpointButReturnsNothing() {
        val e = Env(tmp.root); start(e); e.controller.onEdit(snap()); e.settle()
        val e2 = Env(tmp.root, e.keys, e.now, e.boot)
        val r = e2.controller.beginRestore()!!
        assertNull(e2.controller.completeRestore(r, false, true) { true })
        assertTrue(File(tmp.root, "review-recovery.bin").exists()); assertFalse(e2.controller.isActive)
        assertNull(e2.controller.completeRestore(r, true, true) { true }) // consumed ticket cannot be replayed
    }

    @Test fun staleLedgerValidationDiscardsRecoveryOnly() {
        val e = Env(tmp.root); start(e); e.controller.onEdit(snap()); e.settle()
        val e2 = Env(tmp.root, e.keys, e.now, e.boot)
        val r = e2.controller.beginRestore()!!
        assertNull(e2.controller.completeRestore(r, true, true) { false })
        assertTrue(files(e2, tmp.root).isEmpty()); assertTrue(e2.keys.aliases.isEmpty())
    }

    @Test fun bootChangeOrWrongTimeDiscards() {
        val e = Env(tmp.root); start(e); e.controller.onEdit(snap()); e.settle()
        val e2 = Env(tmp.root, e.keys, e.now, boot = 5)
        assertNull(e2.controller.beginRestore()); assertTrue(files(e2, tmp.root).isEmpty())
    }

    @Test fun permanentKeyLossDiscardsTransientKeepsCiphertext() {
        val e = Env(tmp.root); start(e); e.controller.onEdit(snap()); e.settle()
        val e2 = Env(tmp.root, e.keys, e.now, e.boot)
        e2.keys.failUnwrap = ReviewRecoveryKeys.KeyException(false, "transient")
        assertNull(e2.controller.completeRestore(e2.controller.beginRestore()!!, true, true) { true })
        assertTrue(File(tmp.root, "review-recovery.bin").exists())
        e2.keys.failUnwrap = ReviewRecoveryKeys.KeyException(true, "invalidated")
        assertNull(e2.controller.completeRestore(e2.controller.beginRestore()!!, true, true) { true })
        assertTrue(files(e2, tmp.root).isEmpty())
    }

    @Test fun tamperedCiphertextAndTamperedRoutingFieldsAreRejectedAndDiscarded() {
        val e = Env(tmp.root); start(e); e.controller.onEdit(snap()); e.settle()
        val f = File(tmp.root, "review-recovery.bin"); val original = f.readBytes()
        val bytes = original.copyOf(); bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 1).toByte(); f.writeBytes(bytes)
        val e2 = Env(tmp.root, e.keys, e.now, e.boot)
        assertNull(e2.controller.completeRestore(e2.controller.beginRestore()!!, true, true) { true })
        assertTrue(files(e2, tmp.root).isEmpty())
        // routing field tamper: revision in the clear changes the AAD, so the decode fails
        val e3 = Env(tmp.root); start(e3); e3.controller.onEdit(snap()); e3.settle()
        val parsed = ReviewRecoveryContainer.decode(f.readBytes()); val b = parsed.binding
        val forged = ReviewRecoveryEnvelope.Binding(b.sessionId, b.reportId, b.initialDigest, b.originalHash, 8,
            b.appVersionCode, b.bootCount, b.createdElapsedMs, b.deadlineElapsedMs)
        f.writeBytes(ReviewRecoveryContainer.encode(forged, parsed.wrappedKey, parsed.envelope))
        val e4 = Env(tmp.root, e3.keys, e3.now, e3.boot)
        assertNull(e4.controller.completeRestore(e4.controller.beginRestore()!!, true, true) { true })
    }

    @Test fun terminalEventsDeleteAndLateFlushCannotResurrect() {
        val e = Env(tmp.root); start(e); e.controller.onEdit(snap()); e.runTimers() // flush queued, not yet run
        e.controller.terminal(); e.runIo()
        assertTrue(files(e, tmp.root).isEmpty()); assertTrue(e.keys.aliases.isEmpty()); assertFalse(e.controller.isActive)
        e.controller.terminal() // idempotent
    }

    @Test fun screenOffRevokesImmediatelyWithoutFinalWriteAndKeepsPriorCheckpoint() {
        val e = Env(tmp.root); start(e); e.controller.onEdit(snap("first")); e.settle()
        val committed = File(tmp.root, "review-recovery.bin").readBytes()
        e.controller.onEdit(snap("unflushed")); e.controller.revoke(); e.settle()
        assertArrayEquals(committed, File(tmp.root, "review-recovery.bin").readBytes())
        assertEquals(NOT_ACTIVE, e.controller.onEdit(snap()))
    }

    @Test fun processStopFlushesNewestEditThenClearsKey() {
        val e = Env(tmp.root); start(e); e.controller.onEdit(snap("newest edit")); e.controller.onProcessStop(); e.runIo()
        val e2 = Env(tmp.root, e.keys, e.now, e.boot)
        assertEquals("newest edit", e2.controller.completeRestore(e2.controller.beginRestore()!!, true, true) { true }!!.text)
        assertFalse(e.controller.isActive) // resuming in the same process needs a new credential result too
    }

    @Test fun overLimitInputStaysMemoryOnlyAndKeepsPreviousCheckpoint() {
        val e = Env(tmp.root); start(e); e.controller.onEdit(snap("ok")); e.settle()
        val committed = File(tmp.root, "review-recovery.bin").readBytes()
        assertEquals(ReviewRecoveryController.EditResult.MEMORY_ONLY_TOO_LARGE,
            e.controller.onEdit(snap(text = "x".repeat(ReviewRecoveryCodec.TEXT_BYTES + 1)))); e.settle()
        assertArrayEquals(committed, File(tmp.root, "review-recovery.bin").readBytes())
    }

    @Test fun newSessionReplacesOldAliasAndFile() {
        val e = Env(tmp.root); start(e); e.controller.onEdit(snap()); e.settle()
        start(e); assertEquals(1, e.keys.aliases.size)
        assertFalse(File(tmp.root, "review-recovery.bin").exists()) // new session starts empty until first edit commits
    }

    @Test fun processStopRejectsFurtherEditsBeforeIoRuns() {
        val e = Env(tmp.root); start(e); e.controller.onEdit(snap("before stop"))
        e.controller.onProcessStop()
        assertEquals(NOT_ACTIVE, e.controller.onEdit(snap("after stop")))
        e.runIo()
        val next = Env(tmp.root, e.keys, e.now, e.boot)
        assertEquals("before stop", next.controller.completeRestore(next.controller.beginRestore()!!, true, true) { true }!!.text)
    }

    @Test fun terminalFencesBeforeDeferredCleanup() {
        val e = Env(tmp.root); start(e); e.controller.onEdit(snap()); e.settle()
        e.controller.terminal()
        assertFalse(e.controller.isActive)
        assertEquals(NOT_ACTIVE, e.controller.onEdit(snap("late")))
        e.runIo(); assertTrue(files(e, tmp.root).isEmpty()); assertTrue(e.keys.aliases.isEmpty())
    }

    @Test fun writerResumeDoesNotDeleteLastCommittedCheckpoint() {
        val e = Env(tmp.root); start(e); e.controller.onEdit(snap()); e.settle()
        val file = File(tmp.root, ReviewRecoveryStore.NAME); val before = file.readBytes()
        val parsed = e.store.read()!!
        e.controller.revoke()
        e.writer.resume(parsed.binding)
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun absoluteExpiryRetiresWithoutAnotherKeystroke() {
        val e = Env(tmp.root); start(e); e.controller.onEdit(snap()); e.settle()
        e.now = 10_000 + ReviewRecoveryEnvelope.LIFETIME_MS
        e.runTimers(); e.runIo()
        assertFalse(e.controller.isActive); assertTrue(files(e, tmp.root).isEmpty()); assertTrue(e.keys.aliases.isEmpty())
    }

    @Test fun navigatingAfterRequestRejectsLateStartAndRestore() {
        val e = Env(tmp.root)
        val pending = e.controller.beginStart("synthetic-1", digest, hash, 7)!!
        e.controller.terminal(); e.runIo()
        assertFalse(e.controller.completeStart(pending, true, true))
        start(e); e.controller.onEdit(snap()); e.settle()
        val restore = e.controller.beginRestore()!!
        e.controller.terminal(); e.runIo()
        assertNull(e.controller.completeRestore(restore, true, true) { true })
        assertFalse(e.controller.isActive); assertTrue(e.keys.aliases.isEmpty())
    }

    @Test fun promptProcessStopDoesNotInvalidateFreshMatchingRestoreResult() {
        val e = Env(tmp.root); start(e); e.controller.onEdit(snap()); e.settle()
        val request = e.controller.beginRestore()!!
        e.controller.onProcessStop(); e.runIo()
        assertEquals("synthetic second line", e.controller.completeRestore(request, true, true) { true }!!.text)
    }

    private companion object { val NOT_ACTIVE = ReviewRecoveryController.EditResult.NOT_ACTIVE }
}
