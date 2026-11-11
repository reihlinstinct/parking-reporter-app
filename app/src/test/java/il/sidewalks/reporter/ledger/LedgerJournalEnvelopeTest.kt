package il.sidewalks.reporter.ledger

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class LedgerJournalEnvelopeTest {
    private val key = ByteArray(32) { it.toByte() }
    private val instanceId = ByteArray(32) { (it + 50).toByte() }
    private val origin = LedgerJournalEnvelope.Origin.MIGRATED
    private val phase = LedgerJournalEnvelope.Phase.MIGRATING
    private fun entry() = LedgerJournalEnvelope.Entry(origin, phase)
    private fun fails(action: () -> Unit) {
        try { action(); fail("Must fail closed") } catch (e: Exception) { /* expected */ }
    }
    @Test fun everyValidOriginPhaseRoundTripsAndContradictionsRefuse() {
        var allowed = 0
        for (o in LedgerJournalEnvelope.Origin.values()) for (p in LedgerJournalEnvelope.Phase.values()) {
            val valid = p == LedgerJournalEnvelope.Phase.COMPLETE ||
                (o == LedgerJournalEnvelope.Origin.FRESH && p == LedgerJournalEnvelope.Phase.FRESH_CREATING) ||
                (o == LedgerJournalEnvelope.Origin.MIGRATED && p in listOf(
                    LedgerJournalEnvelope.Phase.MIGRATING, LedgerJournalEnvelope.Phase.CLEANING))
            if (valid) {
                allowed++
                val source = LedgerJournalEnvelope.Entry(o, p)
                val actual = LedgerJournalEnvelope.open(LedgerJournalEnvelope.seal(source, key, instanceId), key, instanceId)
                assertEquals(o, actual.origin); assertEquals(p, actual.phase)
                assertEquals("LedgerJournalEntry(redacted)", actual.toString())
            } else fails { LedgerJournalEnvelope.Entry(o, p) }
        }
        assertEquals(5, allowed)
    }
    @Test fun everyByteMutationAndTruncationRefuses() {
        val good = LedgerJournalEnvelope.seal(entry(), key, instanceId)
        for (i in good.indices) {
            val bad = good.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            fails { LedgerJournalEnvelope.open(bad, key, instanceId) }
        }
        for (n in 0 until good.size) fails { LedgerJournalEnvelope.open(good.copyOf(n), key, instanceId) }
        fails { LedgerJournalEnvelope.open(good + byteArrayOf(0), key, instanceId) }
    }
    @Test fun wrongKeyAndInvalidKeySizesRefuseFreshNonceDoesNotMutateKey() {
        val before = key.copyOf()
        val first = LedgerJournalEnvelope.seal(entry(), key, instanceId)
        val second = LedgerJournalEnvelope.seal(entry(), key, instanceId)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(before, key)
        fails { LedgerJournalEnvelope.open(first, ByteArray(32) { 99 }, instanceId) }
        for (n in listOf(0, 16, 31, 33)) {
            fails { LedgerJournalEnvelope.open(first, ByteArray(n), instanceId) }
            fails { LedgerJournalEnvelope.seal(entry(), ByteArray(n), instanceId) }
        }
    }
    private val domain = "parking-reporter/ledger-migration-journal/v1".toByteArray(Charsets.UTF_8)
    private fun realAad() = domain + byteArrayOf(0) + instanceId
    private fun craft(payload: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        if (aad != null) cipher.updateAAD(aad)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(payload)
    }
    @Test fun exactDomainPositiveAndNoEmptyOrOtherDomainRefuse() {
        val valid = byteArrayOf(2, 4)
        val opened = LedgerJournalEnvelope.open(craft(valid, realAad()), key, instanceId)
        assertEquals(LedgerJournalEnvelope.Origin.MIGRATED, opened.origin)
        assertEquals(LedgerJournalEnvelope.Phase.COMPLETE, opened.phase)
        for (aad in listOf(null, byteArrayOf(),
                "parking-reporter/ledger-record/v1".toByteArray() + byteArrayOf(0) + instanceId,
                domain)) {
            fails { LedgerJournalEnvelope.open(craft(valid, aad), key, instanceId) }
        }
    }
    @Test fun correctlyAuthenticatedInvalidPayloadLengthsRefuse() {
        for (size in listOf(0, 1, 3, 4)) {
            val payload = byteArrayOf(2, 4, 0, 0).copyOf(size)
            fails { LedgerJournalEnvelope.open(craft(payload, realAad()), key, instanceId) }
        }
    }
    @Test fun unknownOriginsPairedWithCompleteAndContradictionsRefuse() {
        for (unknown in listOf(0, 3, 255))
            fails { LedgerJournalEnvelope.open(craft(byteArrayOf(unknown.toByte(), 4), realAad()), key, instanceId) }
        for (payload in listOf(byteArrayOf(1, 0), byteArrayOf(1, 2), byteArrayOf(2, 1)))
            fails { LedgerJournalEnvelope.open(craft(payload, realAad()), key, instanceId) }
    }
    @Test fun staleCompleteCannotCrossLedgerInstancesEvenWithSameKey() {
        val before = instanceId.copyOf()
        val complete = LedgerJournalEnvelope.Entry(LedgerJournalEnvelope.Origin.MIGRATED,
            LedgerJournalEnvelope.Phase.COMPLETE)
        val sealed = LedgerJournalEnvelope.seal(complete, key, instanceId)
        val other = instanceId.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        fails { LedgerJournalEnvelope.open(sealed, key, other) }
        for (size in listOf(0, 16, 31, 33)) {
            fails { LedgerJournalEnvelope.open(sealed, key, ByteArray(size)) }
            fails { LedgerJournalEnvelope.seal(complete, key, ByteArray(size)) }
        }
        assertArrayEquals(before, instanceId)
    }
}
