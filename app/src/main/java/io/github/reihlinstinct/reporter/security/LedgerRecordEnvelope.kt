package io.github.reihlinstinct.reporter.security

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Pure record envelope from #88's reviewed design. NOT wired into Room or custody.
 * The caller owns key lifetime and must stop the entire ledger on any decrypt failure.
 * Neither this envelope nor successful decryption authorizes migration or recovery. */
object LedgerRecordEnvelope {
    enum class Column(val wireName: String) { DRAFT_JSON("draftJson"), REVIEWED_DIGEST("reviewedDigest") }
    private const val VERSION: Byte = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private val domain = "parking-reporter/ledger-record/v1".toByteArray(Charsets.UTF_8)

    private fun aad(column: Column, stableId: String, revision: Long): ByteArray {
        require(stableId.isNotBlank() && '\u0000' !in stableId) { "Invalid ledger record identity" }
        require(revision >= 0) { "Invalid ledger revision" }
        val name = column.wireName.toByteArray(Charsets.UTF_8)
        val id = stableId.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(domain.size + name.size + id.size + 3 + Long.SIZE_BYTES)
            .put(domain).put(0.toByte()).put(name).put(0.toByte()).put(id).put(0.toByte())
            .putLong(revision).array()
    }

    fun encrypt(plaintext: ByteArray, dataKey: ByteArray, column: Column,
                stableId: String, revision: Long): ByteArray {
        require(dataKey.size == 32) { "Invalid ledger key size" }
        val associated = aad(column, stableId, revision)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey, "AES"))
        cipher.updateAAD(associated)
        val encrypted = cipher.doFinal(plaintext)
        check(cipher.iv.size == NONCE_BYTES) { "Invalid ledger nonce" }
        return ByteBuffer.allocate(1 + NONCE_BYTES + encrypted.size)
            .put(VERSION).put(cipher.iv).put(encrypted).array()
    }

    fun decrypt(envelope: ByteArray, dataKey: ByteArray, column: Column,
                stableId: String, revision: Long): ByteArray {
        require(dataKey.size == 32) { "Invalid ledger key size" }
        require(envelope.size >= 1 + NONCE_BYTES + TAG_BYTES && envelope[0] == VERSION) {
            "Invalid ledger record envelope"
        }
        val associated = aad(column, stableId, revision)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKey, "AES"),
            GCMParameterSpec(128, envelope.copyOfRange(1, 1 + NONCE_BYTES)))
        cipher.updateAAD(associated)
        return cipher.doFinal(envelope.copyOfRange(1 + NONCE_BYTES, envelope.size))
    }
}
