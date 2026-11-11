package il.sidewalks.reporter.ledger

/** Inactive durability port. An implementation MUST hold same-process and OS
 * exclusive locks for the WHOLE block, implement strict bounded reads and
 * create a private temp without overwriting unrelated data. No adapter exists
 * in production yet. Exceptions are failures, never logged-and-ignored success.
 * A crash before directory fsync may expose old OR new journal on restart. */
interface LedgerJournalPort {
    fun <T> exclusive(block: () -> T): T
    fun readCommitted(): ByteArray?
    fun writeTempAndSync(bytes: ByteArray)
    fun renameTempToCommitted()
    fun syncParentDirectory()
}

/** Inactive authenticated commit protocol. No file I/O adapter, migration,
 * custody, recovery, rollback, reset or production caller. key comes from
 * independently authenticated custody; this protocol grants no key creation. */
class LedgerJournalCommit(private val port: LedgerJournalPort, instanceId: ByteArray) {
    // Snapshot independently authenticated custody identity; caller mutation
    // cannot retarget an existing store to another ledger instance.
    private val instance = instanceId.copyOf().also {
        require(it.size == 32) { "Invalid ledger instance identity" }
    }
    fun read(key: ByteArray): LedgerJournalEnvelope.Entry? = port.exclusive {
        port.readCommitted()?.let { bytes ->
            try {
                val entry = LedgerJournalEnvelope.open(bytes, key, instance)
                // A preceding uncertain rename may be visible but not durable.
                // Establish directory durability under the same lock before
                // exposing its phase. A sync failure must fail this read.
                port.syncParentDirectory()
                entry
            } finally { bytes.fill(0) }
        }
    }

    /** Idempotent same-state commit allowed; backwards/origin changes refused.
     * Source/schema proof still belongs to the migration factory, not this API. */
    fun commit(next: LedgerJournalEnvelope.Entry, key: ByteArray) = port.exclusive {
        val oldBytes = port.readCommitted()
        val old = oldBytes?.let {
            try { LedgerJournalEnvelope.open(it, key, instance) } finally { it.fill(0) }
        }
        check(validTransition(old, next)) { "Invalid ledger journal transition" }
        val envelope = LedgerJournalEnvelope.seal(next, key, instance)
        try {
            port.writeTempAndSync(envelope)
            port.renameTempToCommitted()
            port.syncParentDirectory()
            val readback = port.readCommitted() ?: error("Ledger journal read-back missing")
            try {
                val actual = LedgerJournalEnvelope.open(readback, key, instance)
                check(java.security.MessageDigest.isEqual(envelope, readback) &&
                    actual.origin == next.origin && actual.phase == next.phase) {
                    "Ledger journal read-back mismatch"
                }
            } finally { readback.fill(0) }
        } finally { envelope.fill(0) }
    }

    private fun validTransition(old: LedgerJournalEnvelope.Entry?, next: LedgerJournalEnvelope.Entry): Boolean {
        if (old == null) return next.phase == LedgerJournalEnvelope.Phase.FRESH_CREATING ||
            next.phase == LedgerJournalEnvelope.Phase.MIGRATING
        if (old.origin != next.origin) return false
        if (old.phase == next.phase) return true
        return when (old.phase) {
            LedgerJournalEnvelope.Phase.FRESH_CREATING -> next.phase == LedgerJournalEnvelope.Phase.COMPLETE
            LedgerJournalEnvelope.Phase.MIGRATING -> next.phase == LedgerJournalEnvelope.Phase.CLEANING
            LedgerJournalEnvelope.Phase.CLEANING -> next.phase == LedgerJournalEnvelope.Phase.COMPLETE
            LedgerJournalEnvelope.Phase.COMPLETE -> false
        }
    }
}
