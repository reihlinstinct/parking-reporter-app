package io.github.reihlinstinct.reporter.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Inactive on-disk container for the single review-recovery checkpoint (#358 slice 2).
 * Layout: magic, version, routing fields (the same values that form the envelope AAD), wrapped session key,
 * sealed envelope. The routing fields are UNTRUSTED when read: they may only reject, delete or route to
 * authentication. Any tampering of them breaks the envelope AAD, so they never authorize a decode.
 * Strict: bounded lengths, no trailing bytes, any malformation throws IOException. */
object ReviewRecoveryContainer {
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'R'.code.toByte(), 'R'.code.toByte(), 'C'.code.toByte())
    private const val VERSION = 1
    private const val MAX_FIELD_BYTES = 256
    const val MAX_WRAPPED_BYTES = 256
    const val MAX_FILE_BYTES = 8 + 4 * 80 + 40 + MAX_WRAPPED_BYTES + ReviewRecoveryEnvelope.MAX_ENVELOPE_BYTES + 1024

    class Parsed(val binding: ReviewRecoveryEnvelope.Binding, val wrappedKey: ByteArray, val envelope: ByteArray) {
        override fun toString() = "ReviewRecoveryContainer(redacted)"
    }

    fun encode(binding: ReviewRecoveryEnvelope.Binding, wrappedKey: ByteArray, envelope: ByteArray): ByteArray {
        require(wrappedKey.size in 1..MAX_WRAPPED_BYTES) { "Invalid recovery wrapped key" }
        require(envelope.size in ReviewRecoveryEnvelope.MIN_ENVELOPE_BYTES..ReviewRecoveryEnvelope.MAX_ENVELOPE_BYTES) {
            "Invalid recovery envelope"
        }
        val out = ByteArrayOutputStream(envelope.size + 512)
        DataOutputStream(out).use { d ->
            d.write(MAGIC); d.writeInt(VERSION)
            fun text(v: String) { val b = v.toByteArray(Charsets.UTF_8); require(b.size <= MAX_FIELD_BYTES); d.writeInt(b.size); d.write(b) }
            text(binding.sessionId); text(binding.reportId); text(binding.initialDigest); text(binding.originalHash)
            d.writeLong(binding.expectedRevision); d.writeInt(binding.appVersionCode); d.writeInt(binding.bootCount)
            d.writeLong(binding.createdElapsedMs); d.writeLong(binding.deadlineElapsedMs)
            d.writeInt(wrappedKey.size); d.write(wrappedKey)
            d.writeInt(envelope.size); d.write(envelope)
        }
        return out.toByteArray()
    }

    /** Throws IOException for every malformed input (also wraps Binding validation failures). */
    fun decode(bytes: ByteArray): Parsed {
        if (bytes.size > MAX_FILE_BYTES || bytes.size < 8) throw IOException("Invalid recovery container")
        try {
            val d = DataInputStream(ByteArrayInputStream(bytes))
            val magic = ByteArray(4); d.readFully(magic)
            if (!magic.contentEquals(MAGIC) || d.readInt() != VERSION) throw IOException("Invalid recovery container")
            fun text(): String {
                val n = d.readInt()
                if (n < 0 || n > MAX_FIELD_BYTES) throw IOException("Invalid recovery container")
                val b = ByteArray(n); d.readFully(b); return String(b, Charsets.UTF_8)
            }
            val session = text(); val report = text(); val initial = text(); val original = text()
            val binding = ReviewRecoveryEnvelope.Binding(session, report, initial, original,
                d.readLong(), d.readInt(), d.readInt(), d.readLong(), d.readLong())
            val wn = d.readInt()
            if (wn < 1 || wn > MAX_WRAPPED_BYTES) throw IOException("Invalid recovery container")
            val wrapped = ByteArray(wn); d.readFully(wrapped)
            val en = d.readInt()
            if (en < ReviewRecoveryEnvelope.MIN_ENVELOPE_BYTES || en > ReviewRecoveryEnvelope.MAX_ENVELOPE_BYTES) {
                throw IOException("Invalid recovery container")
            }
            val envelope = ByteArray(en); d.readFully(envelope)
            if (d.read() != -1) throw IOException("Invalid recovery container")
            return Parsed(binding, wrapped, envelope)
        } catch (e: IOException) {
            throw IOException("Invalid recovery container")
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid recovery container")
        }
    }
}
