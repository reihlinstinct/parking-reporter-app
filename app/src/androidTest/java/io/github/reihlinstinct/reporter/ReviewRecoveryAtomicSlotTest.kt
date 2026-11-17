package io.github.reihlinstinct.reporter

import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.security.ReviewRecoveryAtomicSlot
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/** Inactive fixed-slot adapter tests using synthetic ciphertext-shaped bytes only, no app UI/transport. */
class ReviewRecoveryAtomicSlotTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val base get() = File(context.noBackupFilesDir, "review-recovery-v1.enc")
    private lateinit var slot: ReviewRecoveryAtomicSlot
    @Before fun setup() { slot = ReviewRecoveryAtomicSlot(context); slot.deleteAll() }
    @After fun cleanup() { slot.deleteAll() }
    private fun bytes(marker: Int) = ByteArray(64) { marker.toByte() }.also { it[0] = 1 }

    @Test fun commitReadAndAbortPreservePriorCiphertext() {
        assertNull(slot.readCiphertext())
        val first = bytes(2); slot.stage(first).finish()
        assertArrayEquals(first, slot.readCiphertext())
        val abandoned = slot.stage(bytes(3)); abandoned.abort(); abandoned.abort()
        assertArrayEquals(first, slot.readCiphertext())
        val committed = slot.stage(bytes(4)); committed.finish(); committed.abort()
        assertArrayEquals(bytes(4), slot.readCiphertext())
        assertTrue(base.isFile)
    }
    @Test fun cleanupRemovesBaseNewAndLegacyBackup() {
        slot.stage(bytes(2)).finish()
        File(base.path + ".new").writeBytes(bytes(3))
        File(base.path + ".bak").writeBytes(bytes(4))
        slot.deleteAll()
        assertFalse(base.exists()); assertFalse(File(base.path + ".new").exists())
        assertFalse(File(base.path + ".bak").exists()); assertNull(slot.readCiphertext())
    }
    @Test fun interruptedReplacementKeepsPriorCommitAndLegacyBackupRecovers() {
        slot.stage(bytes(2)).finish()
        File(base.path + ".new").writeBytes(bytes(3))
        assertArrayEquals(bytes(2), ReviewRecoveryAtomicSlot(context).readCiphertext())
        base.renameTo(File(base.path + ".bak"))
        base.writeBytes(bytes(4))
        assertArrayEquals(bytes(2), ReviewRecoveryAtomicSlot(context).readCiphertext())
    }
    @Test fun oversizedAndMalformedFilesRejectWithoutReturningBytes() {
        for (value in listOf(ByteArray(0), ByteArray(64), ByteArray(256 * 1024 + 1).also { it[0] = 1 })) {
            base.writeBytes(value)
            try { slot.readCiphertext(); fail("Must reject") } catch (_: IOException) { }
            slot.deleteAll()
        }
    }
}
