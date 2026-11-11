package il.sidewalks.reporter.ledger

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Inactive session boundary. Factory/lifecycle wiring and production DB activation
 * are deliberately absent. close() waits for an operation before zeroing the owned
 * key. One authentication/decoding failure stops ALL subsequent operations.
 * No destructive reset, row delete, tombstone recovery or key replacement API. */
class EncryptedLedgerRepository(private val dao: EncryptedReportDao, dataKey: ByteArray) {
    private val mutex = Mutex()
    private val key = dataKey.copyOf().also { require(it.size == 32) { "Invalid ledger key size" } }
    private var stopped = false

    private fun ready() { check(!stopped) { "Ledger session is stopped" } }
    private fun decode(entity: EncryptedReportEntity): DecodedLedgerRecord = try {
        EncryptedRecordCodec.decode(entity.storageRow(), key)
    } catch (e: Exception) {
        stopped = true
        key.fill(0)
        // Never propagate a record, path or content via an error message.
        throw IllegalStateException("Ledger authentication or decoding failed")
    }

    suspend fun close() = mutex.withLock { stopped = true; key.fill(0) }

    suspend fun all(): List<DecodedLedgerRecord> = mutex.withLock {
        ready()
        // No filtering or partial success on a bad row. Callers must not sweep
        // evidence unless this complete result has returned successfully.
        dao.all().map(::decode)
    }
    suspend fun find(id: String): DecodedLedgerRecord? = mutex.withLock {
        ready(); dao.find(id)?.let(::decode)
    }
    suspend fun findPhoto(hash: String): DecodedLedgerRecord? = mutex.withLock {
        ready(); dao.findPhoto(hash)?.let(::decode)
    }
    suspend fun reserve(record: DecodedLedgerRecord.Readable) = mutex.withLock {
        ready()
        // SQL ABORT + unique originalSha256 is authoritative. Do not implement
        // uniqueness as a decrypt/find/insert sequence vulnerable to races.
        dao.reserve(EncryptedReportEntity.from(EncryptedRecordCodec.encode(record, key)))
    }
    suspend fun store(id: String, expectedRevision: Long, json: String, digest: String?): Int = mutex.withLock {
        ready()
        require(expectedRevision in 0 until Long.MAX_VALUE) { "Invalid expected revision" }
        val current = dao.find(id) ?: return@withLock 0
        if (current.tombstoned || current.revision != expectedRevision) return@withLock 0
        // Authenticate the old row before replacing it. A write must not hide
        // corruption, and any failure stops later reserve/store calls too.
        decode(current)
        val next = EncryptedRecordCodec.encode(DecodedLedgerRecord.Readable(id,
            current.originalSha256, current.originalRelativePath,
            expectedRevision + 1, json, digest), key)
        // Another session may have won after find(). SQL CAS still protects it.
        dao.store(id, expectedRevision, next.draftCiphertext(), next.digestCiphertext())
    }
}
