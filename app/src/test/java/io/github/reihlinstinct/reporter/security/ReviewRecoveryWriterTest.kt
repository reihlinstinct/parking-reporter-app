package io.github.reihlinstinct.reporter.security

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class ReviewRecoveryWriterTest {
    private var now = 2000L
    private var unlocked = true
    private var boot: Int? = 4
    private var version = 197
    private class MemorySlot : ReviewRecoveryWriter.Slot {
        var committed: ByteArray? = null
        var failStage = false; var failFinish = false; var failDelete = false
        var afterStage: () -> Unit = {}
        override fun stage(ciphertext: ByteArray): ReviewRecoveryWriter.Pending {
            if (failStage) throw IOException("synthetic write failure")
            val staged = ciphertext.copyOf(); afterStage()
            return object : ReviewRecoveryWriter.Pending {
                override fun finish() {
                    if (failFinish) throw IOException("synthetic finish failure")
                    committed = staged.copyOf()
                }
                override fun abort() { staged.fill(0) }
            }
        }
        override fun deleteAll() { if (failDelete) throw IOException("synthetic delete failure"); committed = null }
    }
    private val slot = MemorySlot()
    private val writer = ReviewRecoveryWriter(slot, { now }, { boot }, { version }, { unlocked })
    private fun binding() = ReviewRecoveryEnvelope.Binding("a".repeat(32), "synthetic-1", "b".repeat(64),
        "c".repeat(64), 7, 197, 4, 1000, 901000)
    private fun bytes(marker: Int) = ByteArray(30) { marker.toByte() }.also { it[0] = 1 }
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
            slot.afterStage = { when(variant) { 0 -> unlocked = false; 1 -> now = 901000; 2 -> boot = null; 3 -> version = 198 } }
            assertFalse(writer.commit(edit, bytes(2))); assertNull(slot.committed)
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
        // Don't infer lock ordering from sleeps; release the blocked write, then join both.
        release.countDown(); writing.join(); deleting.join()
        assertTrue(wrote.get()); assertTrue(retired.get()); assertNull(slot.committed)
        assertFalse(writer.commit(edit, bytes(3)))
    }
}
