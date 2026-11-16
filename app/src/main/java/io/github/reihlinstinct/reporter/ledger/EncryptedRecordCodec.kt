package io.github.reihlinstinct.reporter.ledger

import io.github.reihlinstinct.reporter.security.LedgerRecordEnvelope
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Inactive v2 storage boundary. No Room entity, opener, migration or production caller.
 * This type preserves duplicate-guard identity independently of decryptable content.
 * Callers must stop the whole session on any decode exception, never skip a row. */
class EncryptedRecordRow(
    val stableId: String,
    val originalSha256: String,
    val originalRelativePath: String,
    val revision: Long,
    draftCiphertext: ByteArray,
    digestCiphertext: ByteArray?,
    val tombstoned: Boolean = false,
) {
    private val draftBytes = draftCiphertext.copyOf()
    private val digestBytes = digestCiphertext?.copyOf()
    fun draftCiphertext(): ByteArray = draftBytes.copyOf()
    fun digestCiphertext(): ByteArray? = digestBytes?.copyOf()
    override fun toString() = "EncryptedRecordRow(redacted)"
}

sealed interface DecodedLedgerRecord {
    val stableId: String
    val originalSha256: String
    val originalRelativePath: String
    val revision: Long

    class Readable(
        override val stableId: String,
        override val originalSha256: String,
        override val originalRelativePath: String,
        override val revision: Long,
        val draftJson: String,
        val reviewedDigest: String?,
    ) : DecodedLedgerRecord {
        override fun toString() = "ReadableLedgerRecord(redacted)"
    }

    /** No draft/digest fields. This is an existing record, never missing/null. */
    class Tombstone(
        override val stableId: String,
        override val originalSha256: String,
        override val originalRelativePath: String,
        override val revision: Long,
    ) : DecodedLedgerRecord {
        override fun toString() = "TombstonedLedgerRecord(redacted)"
    }
}

/** Stateless conversion, not a key owner. Session/factory owns the data-key lifetime. */
object EncryptedRecordCodec {
    private fun validate(stableId: String, hash: String, path: String, revision: Long) {
        require(stableId.isNotBlank() && '\u0000' !in stableId) { "Invalid record identity" }
        require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid original hash" }
        require(path.isNotBlank() && '\u0000' !in path) { "Invalid original path" }
        require(revision >= 0) { "Invalid revision" }
    }

    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    fun encode(record: DecodedLedgerRecord.Readable, key: ByteArray): EncryptedRecordRow {
        validate(record.stableId, record.originalSha256, record.originalRelativePath, record.revision)
        fun encrypt(text: String, column: LedgerRecordEnvelope.Column): ByteArray {
            val bytes = text.toByteArray(Charsets.UTF_8)
            try {
                // Strict round-trip also rejects unpaired UTF-16 surrogates.
                require(utf8(bytes) == text) { "Invalid ledger text encoding" }
                return LedgerRecordEnvelope.encrypt(bytes, key, column, record.stableId, record.revision)
            } finally { bytes.fill(0) }
        }
        return EncryptedRecordRow(record.stableId, record.originalSha256,
            record.originalRelativePath, record.revision,
            encrypt(record.draftJson, LedgerRecordEnvelope.Column.DRAFT_JSON),
            record.reviewedDigest?.let { encrypt(it, LedgerRecordEnvelope.Column.REVIEWED_DIGEST) })
    }

    fun decode(row: EncryptedRecordRow, key: ByteArray): DecodedLedgerRecord {
        validate(row.stableId, row.originalSha256, row.originalRelativePath, row.revision)
        if (row.tombstoned) return DecodedLedgerRecord.Tombstone(
            row.stableId, row.originalSha256, row.originalRelativePath, row.revision)
        fun decrypt(bytes: ByteArray, column: LedgerRecordEnvelope.Column): String {
            val plaintext = LedgerRecordEnvelope.decrypt(bytes, key, column, row.stableId, row.revision)
            return try { utf8(plaintext) } finally { plaintext.fill(0) }
        }
        return DecodedLedgerRecord.Readable(row.stableId, row.originalSha256,
            row.originalRelativePath, row.revision,
            decrypt(row.draftCiphertext(), LedgerRecordEnvelope.Column.DRAFT_JSON),
            row.digestCiphertext()?.let { decrypt(it, LedgerRecordEnvelope.Column.REVIEWED_DIGEST) })
    }
}
