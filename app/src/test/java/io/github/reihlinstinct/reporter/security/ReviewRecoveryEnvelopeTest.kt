package io.github.reihlinstinct.reporter.security

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.AEADBadTagException

class ReviewRecoveryEnvelopeTest {
    private val key = ByteArray(32) { it.toByte() }
    private val payload = "synthetic opaque snapshot".toByteArray()
    private fun binding(session: String = "a".repeat(32), report: String = "synthetic-1",
        digest: String = "b".repeat(64), hash: String = "c".repeat(64), revision: Long = 7,
        version: Int = 197, boot: Int = 4, created: Long = 1000, deadline: Long = created + ReviewRecoveryEnvelope.LIFETIME_MS
    ) = ReviewRecoveryEnvelope.Binding(session, report, digest, hash, revision, version, boot, created, deadline)
    private fun seal(b: ReviewRecoveryEnvelope.Binding = binding(), p: ByteArray = payload) =
        ReviewRecoveryEnvelope.seal(p, key, b, 2000, 4, 197)
    private fun open(bytes: ByteArray, b: ReviewRecoveryEnvelope.Binding = binding(), k: ByteArray = key) =
        ReviewRecoveryEnvelope.open(bytes, k, b, 2000, 4, 197)
    private inline fun <reified T : Exception> rejectsAs(block: () -> Unit) {
        try { block(); fail("Must reject with ${T::class.java.simpleName}") }
        catch (actual: Exception) { assertTrue("Unexpected exception type", actual is T) }
    }
    private fun rejects(block: () -> Unit) = rejectsAs<IllegalArgumentException>(block)
    private fun rejectsTag(block: () -> Unit) = rejectsAs<AEADBadTagException>(block)

    @Test fun roundTripAndFreshNonce() {
        val first = seal(); val second = seal()
        assertArrayEquals(payload, open(first)); assertArrayEquals(payload, open(second))
        assertFalse(first.contentEquals(second))
    }
    @Test fun exactExpiryBoundaryAndNoLifetimeExtension() {
        val b = binding()
        assertTrue(ReviewRecoveryEnvelope.isFresh(b, b.createdElapsedMs, 4, 197))
        assertTrue(ReviewRecoveryEnvelope.isFresh(b, b.deadlineElapsedMs - 1, 4, 197))
        assertFalse(ReviewRecoveryEnvelope.isFresh(b, b.deadlineElapsedMs, 4, 197))
        assertFalse(ReviewRecoveryEnvelope.isFresh(b, b.createdElapsedMs - 1, 4, 197))
        val bytes = seal(b)
        rejects { ReviewRecoveryEnvelope.open(bytes, key, b, b.deadlineElapsedMs, 4, 197) }
        rejects { ReviewRecoveryEnvelope.seal(payload, key, b, b.deadlineElapsedMs, 4, 197) }
        assertEquals(901000L, b.deadlineElapsedMs)
    }
    @Test fun bootUnavailableChangedOrVersionChangedFailsClosed() {
        val b = binding()
        assertFalse(ReviewRecoveryEnvelope.isFresh(b, 2000, null, 197))
        assertFalse(ReviewRecoveryEnvelope.isFresh(b, 2000, 5, 197))
        assertFalse(ReviewRecoveryEnvelope.isFresh(b, 2000, 4, 198))
    }
    @Test fun sealAndOpenEnforceCurrentBootAndVersion() {
        val b = binding(); val bytes = seal()
        for ((boot, version) in listOf(null to 197, 5 to 197, 4 to 198)) {
            rejects { ReviewRecoveryEnvelope.seal(payload, key, b, 2000, boot, version) }
            rejects { ReviewRecoveryEnvelope.open(bytes, key, b, 2000, boot, version) }
        }
    }
    @Test fun everyBindingFieldIsAuthenticatedEvenWhenFresh() {
        val bytes = seal()
        val variants = listOf(binding(session = "d".repeat(32)), binding(report = "synthetic-2"),
            binding(digest = "d".repeat(64)), binding(hash = "d".repeat(64)), binding(revision = 8),
            binding(version = 198), binding(boot = 5), binding(created = 1001))
        variants.forEach { b -> rejectsTag {
            ReviewRecoveryEnvelope.open(bytes, key, b, 2000, b.bootCount, b.appVersionCode)
        } }
    }
    @Test fun ciphertextNonceTagAndVersionTamperingRejected() {
        val bytes = seal()
        rejects { open(bytes.copyOf().also { it[0] = 2 }) }
        for (index in listOf(1, 12, 13, bytes.lastIndex)) {
            rejectsTag { open(bytes.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }) }
        }
        rejectsTag { open(bytes + byteArrayOf(0)) }
        rejectsTag { open(bytes.copyOf(bytes.size - 1)) }
        rejectsTag { open(bytes, k = ByteArray(32) { 42 }) }
    }
    @Test fun sizeLimitsEnforcedBeforeDecrypt() {
        rejects { seal(p = byteArrayOf()) }
        rejects { seal(p = ByteArray(ReviewRecoveryEnvelope.MAX_PAYLOAD_BYTES + 1)) }
        rejects { open(byteArrayOf()) }
        rejects { open(ByteArray(ReviewRecoveryEnvelope.MAX_ENVELOPE_BYTES + 1).also { it[0] = 1 }) }
        val p = ByteArray(ReviewRecoveryEnvelope.MAX_PAYLOAD_BYTES) { 7 }
        val bytes = seal(p = p)
        assertEquals(ReviewRecoveryEnvelope.MAX_ENVELOPE_BYTES, bytes.size)
        assertArrayEquals(p, open(bytes))
    }
    @Test fun invalidIdentifiersTimesAndKeySizesRejected() {
        rejects { binding(report = "../private") }; rejects { binding(report = "x".repeat(129)) }
        rejects { binding(session = "A".repeat(32)) }; rejects { binding(digest = "bad") }
        rejects { binding(revision = -1) }; rejects { binding(boot = -1) }; rejects { binding(version = 0) }
        rejects { binding(created = -1) }; rejects { binding(deadline = 901001) }
        rejects { binding(created = Long.MAX_VALUE) }
        rejects { open(seal(), k = ByteArray(16)) }
    }
    @Test fun diagnosticsRedacted() {
        val text = binding().toString()
        assertEquals("ReviewRecoveryBinding(redacted)", text)
        assertFalse(text.contains("synthetic"))
    }
}
