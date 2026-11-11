package il.sidewalks.reporter.ledger

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class LedgerJournalEnvelopeTest {
    private val key = ByteArray(32) { it.toByte() }
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
                val actual = LedgerJournalEnvelope.open(LedgerJournalEnvelope.seal(source, key), key)
                assertEquals(o, actual.origin); assertEquals(p, actual.phase)
                assertEquals("LedgerJournalEntry(redacted)", actual.toString())
            } else fails { LedgerJournalEnvelope.Entry(o, p) }
        }
        assertEquals(5, allowed)
    }
    @Test fun everyByteMutationAndTruncationRefuses() {
        val good = LedgerJournalEnvelope.seal(entry(), key)
        for (i in good.indices) {
            val bad = good.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            fails { LedgerJournalEnvelope.open(bad, key) }
        }
        for (n in 0 until good.size) fails { LedgerJournalEnvelope.open(good.copyOf(n), key) }
        fails { LedgerJournalEnvelope.open(good + byteArrayOf(0), key) }
    }
    @Test fun wrongKeyAndInvalidKeySizesRefuseFreshNonceDoesNotMutateKey() {
        val before = key.copyOf()
        val first = LedgerJournalEnvelope.seal(entry(), key)
        val second = LedgerJournalEnvelope.seal(entry(), key)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(before, key)
        fails { LedgerJournalEnvelope.open(first, ByteArray(32) { 99 }) }
        for (n in listOf(0, 16, 31, 33)) {
            fails { LedgerJournalEnvelope.open(first, ByteArray(n)) }
            fails { LedgerJournalEnvelope.seal(entry(), ByteArray(n)) }
        }
    }
    @Test fun authenticatedUnknownOrContradictoryPayloadAndWrongDomainRefuse() {
        fun craft(payload: ByteArray, domain: String): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            cipher.updateAAD(domain.toByteArray(Charsets.UTF_8))
            return byteArrayOf(1) + cipher.iv + cipher.doFinal(payload)
        }
        val domain = "parking-reporter/ledger-migration-journal/v1"
        for (bytes in listOf(byteArrayOf(0, 1), byteArrayOf(1, 0), byteArrayOf(1, 2), byteArrayOf(2, 1)))
            fails { LedgerJournalEnvelope.open(craft(bytes, domain), key) }
        fails { LedgerJournalEnvelope.open(craft(byteArrayOf(2, 2), "parking-reporter/ledger-record/v1"), key) }
    }
}
