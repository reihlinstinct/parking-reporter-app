package io.github.reihlinstinct.reporter.security

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ReviewRecoveryWriterTest {
    private var now = 2000L
    private var unlocked = true
    private var boot: Int? = 4
    private var version = 197
    private class MemorySlot : ReviewRecoveryWriter.Slot {
        var committed: ByteArray? = null
        var failStage = false; var failFinish = false; var failDelete = false
        var abortCount = 0
        var afterStage: () -> Unit = {}
        override fun stage(ciphertext: ByteArray): ReviewRecoveryWriter.Pending {
            if (failStage) throw IOException("synthetic write failure")
            val staged = ciphertext.copyOf(); afterStage()
            return object : ReviewRecoveryWriter.Pending {
                override fun finish() {
                    if (failFinish) throw IOException("synthetic finish failure")
                    committed = staged.copyOf()
                }
                override fun abort() { abortCount++; staged.fill(0) }
            }
        }
        override fun deleteAll() { if (failDelete) throw IOException("synthetic delete failure"); committed = null }
    }
    private val slot = MemorySlot()
    private val writer = ReviewRecoveryWriter(slot, { now }, { boot }, { version }, { unlocked })
    private fun binding() = ReviewRecoveryEnvelope.Binding("a".repeat(32), "synthetic-1", "b".repeat(64),
        "c".repeat(64), 7, 197, 4, 1000, 901000)
    private fun bytes(marker: Int) = ByteArray(ReviewRecoveryEnvelope.MIN_ENVELOPE_BYTES) { marker.toByte() }.also { it[0] = 1 }
    private fun rejectsIo(block: () -> Unit) { try { block(); fail("Expected IOException") } catch (_: IOException) { } }

    @Test fun olderEncryptionCannotReplaceNewerCapturedEdit() {
        val session = writer.start(binding()); val old = writer.capture(session); val new = writer.capture(session)
        val newer = bytes(3); assertTrue(writer.commit(new, newer)); assertTrue(newer.all { it == 0.toByte() })
        val stale = bytes(2); assertFalse(writer.commit(old, stale)); assertTrue(stale.all { it == 0.toByte() })
        assertEquals(3.toByte(), slot.committed!![1])
    }
    @Test fun newestCaptureImmediatelyFencesOlderUncommittedEncryption() {
        val session = writer.start(binding()); val first = writer.capture(session); val latest = writer.capture(session)
        assertFalse(writer.commit(first, bytes(2))); assertNull(slot.committed)
        assertTrue(writer.commit(latest, bytes(3)))
    }
    @Test fun tokenOrderFollowsSnapshotOrderAcrossCommitThreads() {
        // Snapshots A then B are captured on the snapshot thread; their commits race on an I/O pool
        // in reverse order. The newer snapshot's ciphertext must still win.
        val session = writer.start(binding())
        val older = writer.capture(session); val newer = writer.capture(session)
        val olderFirst = CountDownLatch(1); val olderDone = CountDownLatch(1)
        val olderResult = AtomicBoolean(true); val newerResult = AtomicBoolean(false)
        val olderThread = Thread { olderResult.set(writer.commit(older, bytes(2))); olderFirst.countDown(); olderDone.countDown() }
        val newerThread = Thread { olderFirst.await(); newerResult.set(writer.commit(newer, bytes(3))) }
        newerThread.start(); olderThread.start(); olderDone.await(); newerThread.join()
        assertFalse(olderResult.get()); assertTrue(newerResult.get())
        assertEquals(3.toByte(), slot.committed!![1])
    }
    @Test fun captureNeverBlocksOnCommitIo() {
        // A commit parked in stage() holds the I/O mutex; capture() must still return promptly,
        // so it can run on Main together with the snapshot.
        val session = writer.start(binding()); val committed = writer.capture(session)
        val staged = CountDownLatch(1); val release = CountDownLatch(1)
        slot.afterStage = { staged.countDown(); release.await() }
        val committing = Thread { writer.commit(committed, bytes(2)) }
        committing.start(); staged.await()
        val captured = java.util.concurrent.atomic.AtomicReference<ReviewRecoveryWriter.Edit?>()
        val capturing = Thread { captured.set(writer.capture(session)) }
        capturing.start(); capturing.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse("capture() blocked on the commit I/O mutex", capturing.isAlive)
        assertNotNull(captured.get())
        release.countDown(); committing.join()
        assertTrue(writer.commit(captured.get()!!, bytes(3)))
        assertEquals(3.toByte(), slot.committed!![1])
    }
    @Test fun duplicateSuccessfulCommitIsRejected() {
        val session = writer.start(binding()); val edit = writer.capture(session)
        assertTrue(writer.commit(edit, bytes(2))); assertFalse(writer.commit(edit, bytes(3)))
        assertEquals(2.toByte(), slot.committed!![1])
    }
    @Test fun terminalDeletionAndReplacementFenceQueuedWrites() {
        val first = writer.start(binding()); val queued = writer.capture(first)
        writer.retire(); assertFalse(writer.commit(queued, bytes(2))); assertNull(slot.committed)
        val second = writer.start(binding()); val current = writer.capture(second)
        assertFalse(writer.commit(queued, bytes(2))); assertTrue(writer.commit(current, bytes(3)))
    }
    @Test fun revokeKeepsLastCommittedButPreventsFurtherWrites() {
        val session = writer.start(binding()); assertTrue(writer.commit(writer.capture(session), bytes(2)))
        val queued = writer.capture(session); writer.revoke()
        assertFalse(writer.commit(queued, bytes(3))); assertEquals(2.toByte(), slot.committed!![1])
    }
    @Test fun lockExpiryBootAndVersionRecheckedAfterStaging() {
        for (variant in 0..3) {
            now = 2000; unlocked = true; boot = 4; version = 197
            val session = writer.start(binding()); val edit = writer.capture(session)
            val abortsBefore = slot.abortCount
            slot.afterStage = { when(variant) { 0 -> unlocked = false; 1 -> now = 901000; 2 -> boot = null; 3 -> version = 198 } }
            assertFalse(writer.commit(edit, bytes(2))); assertNull(slot.committed)
            assertEquals("ineligible-after-stage commit must abort the staged write", abortsBefore + 1, slot.abortCount)
            slot.afterStage = {}
        }
    }
    @Test fun stageAndFinishFailureKeepPriorCommittedAndClearBuffer() {
        val session = writer.start(binding()); assertTrue(writer.commit(writer.capture(session), bytes(2)))
        for (stage in listOf(true, false)) {
            slot.failStage = stage; slot.failFinish = !stage
            val buffer = bytes(3); rejectsIo { writer.commit(writer.capture(session), buffer) }
            assertTrue(buffer.all { it == 0.toByte() }); assertEquals(2.toByte(), slot.committed!![1])
        }
    }
    @Test fun deletionFailureStillInvalidatesQueuedWriter() {
        val session = writer.start(binding()); val edit = writer.capture(session)
        slot.failDelete = true; rejectsIo { writer.retire() }; slot.failDelete = false
        assertFalse(writer.commit(edit, bytes(2)))
    }
    @Test fun startFailureStillRetiresPreviousWriter() {
        val first = writer.start(binding()); val queued = writer.capture(first)
        slot.failDelete = true; rejectsIo { writer.start(binding()) }; slot.failDelete = false
        assertFalse("failed start must leave no writable session", writer.commit(queued, bytes(2)))
        val second = writer.start(binding()); assertTrue(writer.commit(writer.capture(second), bytes(3)))
    }
    @Test fun startClearsEarlierCommittedSlot() {
        val first = writer.start(binding()); assertTrue(writer.commit(writer.capture(first), bytes(2)))
        assertEquals(2.toByte(), slot.committed!![1])
        writer.start(binding()); assertNull("start must clear the earlier committed slot", slot.committed)
    }
    @Test fun finalCommitAndTerminalDeletionUseSameMutex() {
        val session = writer.start(binding()); val edit = writer.capture(session)
        val staged = CountDownLatch(1); val release = CountDownLatch(1); val retiring = CountDownLatch(1)
        val retired = AtomicBoolean(false)
        slot.afterStage = { staged.countDown(); release.await() }
        val wrote = AtomicBoolean(false)
        val writing = Thread { wrote.set(writer.commit(edit, bytes(2))) }
        writing.start(); staged.await()
        val deleting = Thread { retiring.countDown(); writer.retire(); retired.set(true) }
        deleting.start(); retiring.await()
        // Deterministic contention: a synchronized waiter reports BLOCKED, so spin until the
        // deleting thread is parked on the writer mutex before releasing the staged write.
        // With the mutex shared this is always reached; without it retire() finishes inside the
        // stage window, the thread dies RUNNABLE/TERMINATED and this test fails every time.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (deleting.state != Thread.State.BLOCKED) {
            if (System.nanoTime() > deadline) fail("deleter never blocked on the writer mutex")
            Thread.yield()
        }
        release.countDown(); writing.join(); deleting.join()
        assertTrue(wrote.get()); assertTrue(retired.get()); assertNull(slot.committed)
        assertFalse(writer.commit(edit, bytes(3)))
    }
}
