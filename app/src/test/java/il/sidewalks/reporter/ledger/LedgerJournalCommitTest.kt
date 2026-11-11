package il.sidewalks.reporter.ledger

import org.junit.Assert.*
import org.junit.Test

class LedgerJournalCommitTest {
    private val key = ByteArray(32) { it.toByte() }
    private val fresh = LedgerJournalEnvelope.Origin.FRESH
    private val migrated = LedgerJournalEnvelope.Origin.MIGRATED
    private val creating = LedgerJournalEnvelope.Phase.FRESH_CREATING
    private val migrating = LedgerJournalEnvelope.Phase.MIGRATING
    private val cleaning = LedgerJournalEnvelope.Phase.CLEANING
    private val complete = LedgerJournalEnvelope.Phase.COMPLETE
    private fun entry(origin: LedgerJournalEnvelope.Origin, phase: LedgerJournalEnvelope.Phase) =
        LedgerJournalEnvelope.Entry(origin, phase)
    private fun fails(action: () -> Unit) {
        try { action(); fail("Must fail closed") } catch (e: Exception) { /* expected */ }
    }

    /** Logical protocol fake, not a claim about actual filesystem crash durability. */
    private class Port : LedgerJournalPort {
        var committed: ByteArray? = null
        var temp: ByteArray? = null
        var locked = false
        var stopAfter: String? = null
        var corruptReadback = false
        var missingReadback = false
        val events = mutableListOf<String>()
        private fun step(name: String) { check(locked); events += name; if (stopAfter == name) error("synthetic fault") }
        override fun <T> exclusive(block: () -> T): T {
            check(!locked); locked = true
            return try { block() } finally { locked = false }
        }
        override fun readCommitted(): ByteArray? {
            step("read")
            if (events.contains("dir-sync") && missingReadback) return null
            return committed?.copyOf()?.also {
                if (events.contains("dir-sync") && corruptReadback) it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
        }
        override fun writeTempAndSync(bytes: ByteArray) { temp = bytes.copyOf(); step("temp-sync") }
        override fun renameTempToCommitted() { committed = requireNotNull(temp); temp = null; step("rename") }
        override fun syncParentDirectory() { step("dir-sync") }
    }

    @Test fun exactOrderLockReadbackAndAllowedForwardSequence() {
        val p = Port(); val store = LedgerJournalCommit(p)
        store.commit(entry(migrated, migrating), key)
        assertEquals(listOf("read", "temp-sync", "rename", "dir-sync", "read"), p.events)
        p.events.clear()
        store.commit(entry(migrated, cleaning), key)
        store.commit(entry(migrated, complete), key)
        val result = store.read(key)!!
        assertEquals(migrated, result.origin); assertEquals(complete, result.phase)
        assertFalse(p.locked)
        // Same-state write is safe when caller retries after an uncertain journal
        // receipt; it never means retrying a municipal submission.
        store.commit(entry(migrated, complete), key)
    }

    @Test fun everyDurabilityFailureThrowsRetainsAuthenticatedOldOrNewAndUnlocks() {
        for (fault in listOf("read", "temp-sync", "rename", "dir-sync")) {
            val p = Port(); val store = LedgerJournalCommit(p)
            store.commit(entry(migrated, migrating), key)
            p.events.clear(); p.stopAfter = fault
            fails { store.commit(entry(migrated, cleaning), key) }
            assertFalse(p.locked)
            p.stopAfter = null
            val recovered = store.read(key)!!
            assertEquals(migrated, recovered.origin)
            assertEquals(if (fault == "rename" || fault == "dir-sync") cleaning else migrating, recovered.phase)
        }
    }

    @Test fun backwardsSkipAndOriginChangeDoNotWrite() {
        val p = Port(); val store = LedgerJournalCommit(p)
        fails { store.commit(entry(migrated, complete), key) }
        assertNull(p.committed)
        store.commit(entry(migrated, migrating), key)
        for (next in listOf(entry(migrated, complete), entry(fresh, creating))) {
            val before = p.committed!!.copyOf(); p.events.clear()
            fails { store.commit(next, key) }
            assertEquals(listOf("read"), p.events)
            assertArrayEquals(before, p.committed)
        }
        store.commit(entry(migrated, cleaning), key)
        fails { store.commit(entry(migrated, migrating), key) }
        store.commit(entry(migrated, complete), key)
        fails { store.commit(entry(migrated, cleaning), key) }
    }

    @Test fun freshSequenceAndMissingCorruptReadbackRefuseSuccess() {
        for (missing in listOf(false, true)) {
            val p = Port(); val store = LedgerJournalCommit(p)
            store.commit(entry(fresh, creating), key)
            if (missing) p.missingReadback = true else p.corruptReadback = true
            fails { store.commit(entry(fresh, complete), key) }
            assertFalse(p.locked)
        }
    }

    @Test fun corruptOrWrongKeyExistingJournalNeverOverwritten() {
        val p = Port(); val store = LedgerJournalCommit(p)
        store.commit(entry(migrated, migrating), key)
        val before = p.committed!!.copyOf(); p.events.clear()
        fails { store.commit(entry(migrated, cleaning), ByteArray(32) { 99 }) }
        assertArrayEquals(before, p.committed); assertEquals(listOf("read"), p.events)
        p.committed = before.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val corrupt = p.committed!!.copyOf(); p.events.clear()
        fails { store.commit(entry(migrated, cleaning), key) }
        assertArrayEquals(corrupt, p.committed); assertEquals(listOf("read"), p.events)
    }
}
