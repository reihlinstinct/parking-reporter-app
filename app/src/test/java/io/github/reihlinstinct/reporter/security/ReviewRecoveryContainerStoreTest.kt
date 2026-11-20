package io.github.reihlinstinct.reporter.security

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ReviewRecoveryContainerStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun binding(created: Long = 1000) = ReviewRecoveryEnvelope.Binding("a".repeat(32), "synthetic-1",
        "b".repeat(64), "c".repeat(64), 7, 197, 4, created, created + ReviewRecoveryEnvelope.LIFETIME_MS)
    private fun env(marker: Int = 9) = ByteArray(ReviewRecoveryEnvelope.MIN_ENVELOPE_BYTES + 10) { marker.toByte() }.also { it[0] = 1 }
    private val wrapped = ByteArray(60) { 5 }
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected IOException") } catch (_: IOException) { } }

    @Test fun containerRoundTripsAndRejectsEveryMalformation() {
        val bytes = ReviewRecoveryContainer.encode(binding(), wrapped, env())
        val p = ReviewRecoveryContainer.decode(bytes)
        assertEquals("synthetic-1", p.binding.reportId); assertArrayEquals(wrapped, p.wrappedKey); assertArrayEquals(env(), p.envelope)
        rejects { ReviewRecoveryContainer.decode(bytes + 0) }
        rejects { ReviewRecoveryContainer.decode(bytes.copyOf(bytes.size - 1)) }
        rejects { ReviewRecoveryContainer.decode(bytes.copyOf().also { it[0] = 0 }) }
        rejects { ReviewRecoveryContainer.decode(bytes.copyOf().also { it[7] = 2 }) } // version
        rejects { ReviewRecoveryContainer.decode(ByteArray(ReviewRecoveryContainer.MAX_FILE_BYTES + 1)) }
        rejects { ReviewRecoveryContainer.decode(ByteArray(3)) }
    }

    @Test fun commitIsAtomicAndFailureKeepsPriorCheckpoint() {
        val store = ReviewRecoveryStore(tmp.root)
        store.begin(binding(), wrapped)
        store.stage(env(1)).finish()
        assertArrayEquals(env(1), store.read()!!.envelope)
        val pending = store.stage(env(2))
        assertArrayEquals("staged data is not yet committed", env(1), store.read()!!.envelope)
        pending.abort(); pending.abort() // idempotent
        assertArrayEquals(env(1), store.read()!!.envelope)
        assertFalse(File(tmp.root, "review-recovery.bin.new").exists())
        store.stage(env(3)).finish()
        assertArrayEquals(env(3), store.read()!!.envelope)
    }

    @Test fun stageWithoutSessionIsRejectedAndDeleteAllRemovesEveryForm() {
        val store = ReviewRecoveryStore(tmp.root)
        try { store.stage(env()); fail() } catch (_: IllegalStateException) { }
        store.begin(binding(), wrapped); store.stage(env()).finish()
        File(tmp.root, "review-recovery.bin.new").writeBytes(byteArrayOf(1))
        File(tmp.root, "review-recovery.bin.bak").writeBytes(byteArrayOf(1))
        store.deleteAll()
        assertEquals(0, tmp.root.listFiles()!!.size)
        assertNull(store.read())
    }

    @Test fun sweepDeletesExpiredWrongBootWrongVersionCorruptAndLeftovers() {
        val store = ReviewRecoveryStore(tmp.root)
        store.begin(binding(), wrapped); store.stage(env()).finish()
        File(tmp.root, "review-recovery.bin.new").writeBytes(byteArrayOf(1))
        assertEquals("a".repeat(32), store.sweep(2000, 4, 197))
        assertFalse(File(tmp.root, "review-recovery.bin.new").exists())
        assertNull(store.sweep(1000 + ReviewRecoveryEnvelope.LIFETIME_MS, 4, 197)); assertNull(store.read())
        store.begin(binding(), wrapped); store.stage(env()).finish()
        assertNull(store.sweep(2000, 5, 197)); assertEquals(0, tmp.root.listFiles()!!.size)
        store.begin(binding(), wrapped); store.stage(env()).finish()
        assertNull(store.sweep(2000, 4, 198)); assertEquals(0, tmp.root.listFiles()!!.size)
        store.begin(binding(), wrapped); store.stage(env()).finish()
        assertNull(store.sweep(2000, null, 197)); assertEquals(0, tmp.root.listFiles()!!.size)
        File(tmp.root, "review-recovery.bin").writeBytes(ByteArray(40) { 3 })
        assertNull(store.sweep(2000, 4, 197)); assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test fun storeIsAWriterSlotAndNeverReplacesNewerWithOlder() {
        val store = ReviewRecoveryStore(tmp.root)
        var now = 2000L
        val writer = ReviewRecoveryWriter(store, { now }, { 4 }, { 197 }, { true })
        val s = writer.start(binding()); store.begin(binding(), wrapped)
        val old = writer.capture(s); val newer = writer.capture(s)
        assertFalse(writer.commit(old, env(1)))
        assertTrue(writer.commit(newer, env(2)))
        assertArrayEquals(env(2), store.read()!!.envelope)
        writer.retire(); assertNull(store.read())
    }
}
