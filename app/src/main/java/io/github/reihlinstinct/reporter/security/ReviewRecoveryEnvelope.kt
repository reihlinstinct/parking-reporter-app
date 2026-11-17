package io.github.reihlinstinct.reporter.security

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Inactive recovery primitive (#358). No files, Keystore, UI, auth or production callers.
 * This authenticates opaque bytes, NOT a validated ReviewRecoverySnapshot or permission to restore/send.
 * SecretKeySpec and Cipher may copy keys internally; clearing caller arrays cannot promise provider-copy erasure.
 * A future caller must validate the strict snapshot schema and obtain fresh in-app auth separately. */
object ReviewRecoveryEnvelope {
    const val LIFETIME_MS = 15 * 60 * 1000L
    const val MAX_PAYLOAD_BYTES = 256 * 1024 - 29
    const val MAX_ENVELOPE_BYTES = 256 * 1024
    private const val VERSION: Byte = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    /** Smallest valid envelope: version byte + nonce + tag + at least one payload byte. */
    internal const val MIN_ENVELOPE_BYTES = 1 + NONCE_BYTES + TAG_BYTES + 1

    /** Immutable authenticated identity/retention data; no path or authorization state. */
    class Binding(
        val sessionId: String,
        val reportId: String,
        val initialDigest: String,
        val originalHash: String,
        val expectedRevision: Long,
        val appVersionCode: Int,
        val bootCount: Int,
        val createdElapsedMs: Long,
        val deadlineElapsedMs: Long,
    ) {
        init {
            require(sessionId.matches(Regex("[0-9a-f]{32}"))) { "Invalid recovery session" }
            require(reportId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid recovery report" }
            require(initialDigest.matches(Regex("[0-9a-f]{64}")) && originalHash.matches(Regex("[0-9a-f]{64}"))) {
                "Invalid recovery digest"
            }
            require(expectedRevision >= 0 && appVersionCode > 0 && bootCount >= 0) { "Invalid recovery binding" }
            require(createdElapsedMs >= 0 && createdElapsedMs <= Long.MAX_VALUE - LIFETIME_MS &&
                deadlineElapsedMs == createdElapsedMs + LIFETIME_MS) { "Invalid recovery lifetime" }
        }
        override fun toString() = "ReviewRecoveryBinding(redacted)"
    }

    /** Not an auth gate: only same-boot/version absolute retention. Unavailable boot count fails closed. */
    fun isFresh(binding: Binding, nowElapsedMs: Long, bootCount: Int?, appVersionCode: Int): Boolean =
        bootCount != null && bootCount == binding.bootCount && appVersionCode == binding.appVersionCode &&
            nowElapsedMs >= binding.createdElapsedMs && nowElapsedMs < binding.deadlineElapsedMs

    private fun aad(binding: Binding): ByteArray = ByteArrayOutputStream().use { out ->
        DataOutputStream(out).use { data ->
            fun field(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                data.writeInt(bytes.size)
                data.write(bytes)
            }
            field("parking-reporter/review-recovery/snapshot/v1")
            data.writeByte(VERSION.toInt())
            field(binding.sessionId); field(binding.reportId)
            field(binding.initialDigest); field(binding.originalHash)
            data.writeLong(binding.expectedRevision); data.writeInt(binding.appVersionCode)
            data.writeInt(binding.bootCount); data.writeLong(binding.createdElapsedMs)
            data.writeLong(binding.deadlineElapsedMs)
        }
        out.toByteArray()
    }

    fun seal(payload: ByteArray, key: ByteArray, binding: Binding,
             nowElapsedMs: Long, bootCount: Int?, appVersionCode: Int): ByteArray {
        require(key.size == 32) { "Invalid recovery key" }
        require(payload.size in 1..MAX_PAYLOAD_BYTES) { "Invalid recovery payload size" }
        require(isFresh(binding, nowElapsedMs, bootCount, appVersionCode)) { "Recovery expired or mismatched" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        cipher.updateAAD(aad(binding))
        val encrypted = cipher.doFinal(payload)
        check(cipher.iv.size == NONCE_BYTES) { "Invalid recovery nonce" }
        return ByteBuffer.allocate(1 + NONCE_BYTES + encrypted.size)
            .put(VERSION).put(cipher.iv).put(encrypted).array()
    }

    fun open(envelope: ByteArray, key: ByteArray, binding: Binding,
             nowElapsedMs: Long, bootCount: Int?, appVersionCode: Int): ByteArray {
        require(key.size == 32) { "Invalid recovery key" }
        require(envelope.size in MIN_ENVELOPE_BYTES..MAX_ENVELOPE_BYTES && envelope[0] == VERSION) {
            "Invalid recovery envelope"
        }
        require(isFresh(binding, nowElapsedMs, bootCount, appVersionCode)) { "Recovery expired or mismatched" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, envelope.copyOfRange(1, 1 + NONCE_BYTES)))
        cipher.updateAAD(aad(binding))
        return cipher.doFinal(envelope.copyOfRange(1 + NONCE_BYTES, envelope.size))
    }
}
