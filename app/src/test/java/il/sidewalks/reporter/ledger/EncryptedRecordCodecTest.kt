package il.sidewalks.reporter.ledger

import il.sidewalks.reporter.security.LedgerRecordEnvelope
import org.junit.Assert.*
import org.junit.Test

class EncryptedRecordCodecTest {
    private val key = ByteArray(32) { it.toByte() }
    private val hash = "a".repeat(64)
    private fun readable(digest: String? = "ready", revision: Long = 3) =
        DecodedLedgerRecord.Readable("synthetic-id", hash, "originals/synthetic.jpg", revision,
            "{\"text\":\"טקסט סינתטי\"}", digest)
    private fun replace(row: EncryptedRecordRow, id: String = row.stableId,
        revision: Long = row.revision, draft: ByteArray = row.draftCiphertext(),
        digest: ByteArray? = row.digestCiphertext(), tombstone: Boolean = row.tombstoned) =
        EncryptedRecordRow(id, row.originalSha256, row.originalRelativePath, revision,
            draft, digest, tombstone)
    private fun fails(action: () -> Unit) {
        try { action(); fail("Must fail closed") } catch (e: Exception) { /* expected */ }
    }

    @Test fun roundTripNullAndPresentDigestPreservesGuardColumns() {
        for (digest in listOf(null, "", "ready")) {
            val source = readable(digest)
            val row = EncryptedRecordCodec.encode(source, key)
            val actual = EncryptedRecordCodec.decode(row, key) as DecodedLedgerRecord.Readable
            assertEquals(source.stableId, actual.stableId)
            assertEquals(source.originalSha256, actual.originalSha256)
            assertEquals(source.originalRelativePath, actual.originalRelativePath)
            assertEquals(source.revision, actual.revision)
            assertEquals(source.draftJson, actual.draftJson)
            assertEquals(digest, actual.reviewedDigest)
            assertFalse(String(row.draftCiphertext(), Charsets.ISO_8859_1).contains(source.draftJson))
            assertEquals(digest == null, row.digestCiphertext() == null)
        }
    }

    @Test fun badRowOrColumnOrRevisionOrKeyThrowsInsteadOfReturningPartialContent() {
        val row = EncryptedRecordCodec.encode(readable(), key)
        for (bad in listOf(replace(row, id = "other"), replace(row, revision = 4),
            replace(row, draft = row.digestCiphertext()!!),
            replace(row, digest = row.draftCiphertext()),
            replace(row, draft = row.draftCiphertext().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }))) {
            fails { EncryptedRecordCodec.decode(bad, key) }
        }
        fails { EncryptedRecordCodec.decode(row, ByteArray(32) { 99 }) }
    }

    @Test fun tombstonePreservesIdentityAndDoesNotDecryptOrNeedOldKey() {
        val row = EncryptedRecordRow("synthetic-id", hash, "originals/synthetic.jpg", 9,
            byteArrayOf(0), byteArrayOf(1), true)
        val actual = EncryptedRecordCodec.decode(row, byteArrayOf()) as DecodedLedgerRecord.Tombstone
        assertEquals(row.stableId, actual.stableId)
        assertEquals(hash, actual.originalSha256)
        assertEquals(row.originalRelativePath, actual.originalRelativePath)
        assertEquals(9L, actual.revision)
        assertArrayEquals(byteArrayOf(0), row.draftCiphertext())
        assertArrayEquals(byteArrayOf(1), row.digestCiphertext())
    }

    @Test fun rowOwnsCiphertextCopiesBothOnInputAndOutput() {
        val a = byteArrayOf(1, 2); val b = byteArrayOf(3, 4)
        val row = EncryptedRecordRow("synthetic-id", hash, "x", 0, a, b)
        a.fill(9); b.fill(9)
        row.draftCiphertext().fill(8); row.digestCiphertext()!!.fill(8)
        assertArrayEquals(byteArrayOf(1, 2), row.draftCiphertext())
        assertArrayEquals(byteArrayOf(3, 4), row.digestCiphertext())
    }

    @Test fun authenticatedMalformedUtf8StillFailsClosed() {
        val bad = LedgerRecordEnvelope.encrypt(byteArrayOf(0xc3.toByte(), 0x28), key,
            LedgerRecordEnvelope.Column.DRAFT_JSON, "synthetic-id", 3)
        fails { EncryptedRecordCodec.decode(EncryptedRecordRow("synthetic-id", hash, "x", 3, bad, null), key) }
        fails { EncryptedRecordCodec.encode(DecodedLedgerRecord.Readable("synthetic-id", hash, "x", 3, "\uD800", null), key) }
    }

    @Test fun invalidMetadataRejectedEvenForTombstone() {
        for (row in listOf(EncryptedRecordRow("", hash, "x", 0, byteArrayOf(), null, true),
            EncryptedRecordRow("x", "bad", "x", 0, byteArrayOf(), null, true),
            EncryptedRecordRow("x", hash, "", 0, byteArrayOf(), null, true),
            EncryptedRecordRow("x", hash, "x", -1, byteArrayOf(), null, true))) {
            fails { EncryptedRecordCodec.decode(row, key) }
        }
    }

    @Test fun freshEnvelopesAndRedactedDiagnostics() {
        val record = readable()
        val a = EncryptedRecordCodec.encode(record, key)
        val b = EncryptedRecordCodec.encode(record, key)
        assertFalse(a.draftCiphertext().contentEquals(b.draftCiphertext()))
        assertEquals("EncryptedRecordRow(redacted)", a.toString())
        assertEquals("ReadableLedgerRecord(redacted)", record.toString())
    }
}
