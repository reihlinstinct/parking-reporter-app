package io.github.reihlinstinct.reporter.ledger

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Synthetic-only database, never the installed report-ledger.db. */
@Database(entities = [EncryptedReportEntity::class], version = 2, exportSchema = false)
abstract class SyntheticEncryptedDatabase : RoomDatabase() {
    abstract fun rows(): EncryptedReportDao
}

class EncryptedLedgerRepositoryTest {
    private val key = ByteArray(32) { it.toByte() }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun record(id: String = "a", hash: String = "a".repeat(64)) =
        DecodedLedgerRecord.Readable(id, hash, "originals/$id.jpg", 0,
            "synthetic private text $id", "synthetic digest")
    private suspend fun fails(action: suspend () -> Unit) {
        try { action(); fail("Must fail closed") } catch (e: Exception) { /* expected */ }
    }

    @Test fun encryptedStorageReopensAndPreservesDuplicateGuardAndCas() = runBlocking {
        val name = "synthetic-encrypted-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, SyntheticEncryptedDatabase::class.java, name).build()
        var db = open()
        try {
            var repository = EncryptedLedgerRepository(db.rows(), key)
            repository.reserve(record())
            fails { repository.reserve(record("different-id")) }
            assertEquals(1, repository.store("a", 0, "updated synthetic private text", null))
            assertEquals(0, repository.store("a", 0, "stale must not win", "x"))
            val stored = db.rows().find("a")!!
            assertEquals(1L, stored.revision)
            assertNull(stored.reviewedDigest)
            assertFalse(String(stored.draftJson, Charsets.ISO_8859_1).contains("updated synthetic"))
            repository.close(); db.close()
            db = open(); repository = EncryptedLedgerRepository(db.rows(), key)
            val reread = repository.findPhoto("a".repeat(64)) as DecodedLedgerRecord.Readable
            assertEquals("updated synthetic private text", reread.draftJson)
            assertEquals(1L, reread.revision)
            fails { repository.reserve(record("again")) }
            assertEquals(1, repository.all().size)
            repository.close()
            fails { repository.all() }; fails { repository.reserve(record("b", "b".repeat(64))) }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun corruptRowStopsWholeSessionAndCannotBeOverwrittenOrHidden() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SyntheticEncryptedDatabase::class.java).build()
        try {
            val repository = EncryptedLedgerRepository(db.rows(), key)
            repository.reserve(record("a")); repository.reserve(record("b", "b".repeat(64)))
            val before = db.rows().find("b")!!
            val bad = before.draftJson.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            assertEquals(1, db.rows().store("b", 0, bad, before.reviewedDigest))
            fails { repository.all() }
            fails { repository.find("a") }
            fails { repository.reserve(record("c", "c".repeat(64))) }
            fails { repository.store("a", 0, "must not write", null) }
            assertEquals(2, db.rows().all().size)
            assertEquals(0L, db.rows().find("a")!!.revision)
            val second = EncryptedLedgerRepository(db.rows(), key)
            fails { second.store("b", 1, "must not cover corruption", null) }
            assertArrayEquals(bad, db.rows().find("b")!!.draftJson)
            second.close(); repository.close()
        } finally { db.close() }
    }

    @Test fun tombstonesRemainPresentRefuseWritesAndStillBlockDuplicatesAcrossReopen() = runBlocking {
        val name = "synthetic-tombstone-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, SyntheticEncryptedDatabase::class.java, name).build()
        var db = open()
        try {
            // Synthetic fixture represents recovery output, not recovery execution.
            db.rows().reserve(EncryptedReportEntity("a", "a".repeat(64), "originals/a.jpg", 4,
                byteArrayOf(0), byteArrayOf(1), true))
            db.close(); db = open()
            val repository = EncryptedLedgerRepository(db.rows(), key)
            assertTrue(repository.find("a") is DecodedLedgerRecord.Tombstone)
            assertTrue(repository.findPhoto("a".repeat(64)) is DecodedLedgerRecord.Tombstone)
            assertEquals(setOf("originals/a.jpg"), repository.all().map { it.originalRelativePath }.toSet())
            fails { repository.reserve(record("new-id")) }
            assertEquals(0, repository.store("a", 4, "must not resurrect", "x"))
            assertEquals(0, db.rows().store("a", 4, byteArrayOf(9), null))
            assertArrayEquals(byteArrayOf(0), db.rows().find("a")!!.draftJson)
            assertEquals(4L, db.rows().find("a")!!.revision)
            repository.close()
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun keyCopyWrongKeyAndRevisionOverflowFailClosed() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SyntheticEncryptedDatabase::class.java).build()
        try {
            val supplied = key.copyOf()
            val repository = EncryptedLedgerRepository(db.rows(), supplied)
            supplied.fill(0)
            repository.reserve(record())
            assertEquals(record().draftJson, (repository.find("a") as DecodedLedgerRecord.Readable).draftJson)
            val independent = EncryptedLedgerRepository(db.rows(), key)
            assertEquals(record().draftJson, (independent.find("a") as DecodedLedgerRecord.Readable).draftJson)
            independent.close()
            fails { repository.store("a", Long.MAX_VALUE, "overflow", null) }
            assertEquals(0L, db.rows().find("a")!!.revision)
            val wrong = EncryptedLedgerRepository(db.rows(), ByteArray(32) { 99 })
            fails { wrong.find("a") }
            fails { wrong.reserve(record("b", "b".repeat(64))) }
            assertEquals(1, db.rows().all().size)
            repository.close(); wrong.close()
        } finally { db.close() }
    }
    @Test fun staleDaoStoreReturnsZeroAndPreservesEveryEncryptedByte() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SyntheticEncryptedDatabase::class.java).build()
        try {
            val repository = EncryptedLedgerRepository(db.rows(), key)
            repository.reserve(record())
            assertEquals(1, repository.store("a", 0, "winning update", "winner"))
            val before = db.rows().find("a")!!
            assertEquals(0, db.rows().store("a", 0, byteArrayOf(9), byteArrayOf(8)))
            assertEquals(0, db.rows().store("a", 2, byteArrayOf(9), byteArrayOf(8)))
            val after = db.rows().find("a")!!
            assertEquals(before.revision, after.revision)
            assertArrayEquals(before.draftJson, after.draftJson)
            assertArrayEquals(before.reviewedDigest, after.reviewedDigest)
            repository.close()
        } finally { db.close() }
    }

    @Test fun secondRepositoryWinningBetweenFindAndStoreCannotBeOverwritten() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SyntheticEncryptedDatabase::class.java).build()
        try {
            val dao = db.rows()
            val winner = EncryptedLedgerRepository(dao, key)
            winner.reserve(record())
            var interleaved = false
            var winningRow: EncryptedReportEntity? = null
            val racingDao = object : EncryptedReportDao by dao {
                override suspend fun find(id: String): EncryptedReportEntity? {
                    val stale = dao.find(id)
                    if (!interleaved) {
                        interleaved = true
                        assertEquals(1, winner.store(id, 0, "winning interleaved text", "winner"))
                        winningRow = dao.find(id)
                    }
                    return stale
                }
            }
            val loser = EncryptedLedgerRepository(racingDao, key)
            assertEquals(0, loser.store("a", 0, "loser must not win", "loser"))
            assertTrue(interleaved)
            val actual = dao.find("a")!!
            assertEquals(1L, actual.revision)
            assertArrayEquals(winningRow!!.draftJson, actual.draftJson)
            assertArrayEquals(winningRow!!.reviewedDigest, actual.reviewedDigest)
            assertEquals("winning interleaved text", (winner.find("a") as DecodedLedgerRecord.Readable).draftJson)
            loser.close(); winner.close()
        } finally { db.close() }
    }

    @Test fun closeBeforeFirstWriteRefusesReserveAndStore() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SyntheticEncryptedDatabase::class.java).build()
        try {
            val repository = EncryptedLedgerRepository(db.rows(), key)
            repository.close()
            fails { repository.reserve(record()) }
            fails { repository.store("a", 0, "must not write", null) }
            assertTrue(db.rows().all().isEmpty())
        } finally { db.close() }
    }

}
