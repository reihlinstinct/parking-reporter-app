package il.sidewalks.reporter.ledger

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Inactive strict authenticated journal codec, NOT a durable journal store.
 * No file I/O, custody creation, migration, recovery or production callers.
 * A future store must sync/rename/directory-sync and authenticate read-back.
 * Authentication does not prove a database's schema, emptiness or source identity. */
object LedgerJournalEnvelope {
    private const val VERSION: Byte = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private val aad = "parking-reporter/ledger-migration-journal/v1".toByteArray(Charsets.UTF_8)

    enum class Origin(val code: Byte) { FRESH(1), MIGRATED(2) }
    enum class Phase(val code: Byte) { FRESH_CREATING(1), MIGRATING(2), CLEANING(3), COMPLETE(4) }
    class Entry(val origin: Origin, val phase: Phase) {
        init {
            require(phase == Phase.COMPLETE ||
                (origin == Origin.FRESH && phase == Phase.FRESH_CREATING) ||
                (origin == Origin.MIGRATED && (phase == Phase.MIGRATING || phase == Phase.CLEANING))) {
                "Contradictory ledger journal"
            }
        }
        override fun toString() = "LedgerJournalEntry(redacted)"
    }

    fun seal(entry: Entry, key: ByteArray): ByteArray {
        require(key.size == 32) { "Invalid ledger key size" }
        val bytes = byteArrayOf(entry.origin.code, entry.phase.code)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(bytes)
            check(cipher.iv.size == NONCE_BYTES) { "Invalid journal nonce" }
            return ByteBuffer.allocate(1 + NONCE_BYTES + ciphertext.size)
                .put(VERSION).put(cipher.iv).put(ciphertext).array()
        } finally { bytes.fill(0) }
    }

    fun open(envelope: ByteArray, key: ByteArray): Entry {
        require(key.size == 32) { "Invalid ledger key size" }
        // Payload is exactly two enum bytes. Unknown versions, appended bytes,
        // truncated tags and extra future fields all fail closed.
        require(envelope.size == 1 + NONCE_BYTES + 2 + TAG_BYTES && envelope[0] == VERSION) {
            "Invalid ledger journal envelope"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, envelope.copyOfRange(1, 1 + NONCE_BYTES)))
        cipher.updateAAD(aad)
        val bytes = cipher.doFinal(envelope.copyOfRange(1 + NONCE_BYTES, envelope.size))
        try {
            require(bytes.size == 2) { "Invalid journal payload" }
            val origin = Origin.values().singleOrNull { it.code == bytes[0] }
                ?: error("Invalid journal origin")
            val phase = Phase.values().singleOrNull { it.code == bytes[1] }
                ?: error("Invalid journal phase")
            return Entry(origin, phase)
        } finally { bytes.fill(0) }
    }
}
