package il.sidewalks.reporter.security

import org.junit.Assert.*
import org.junit.Test
import il.sidewalks.reporter.security.LedgerRecordEnvelope.Column

class LedgerRecordEnvelopeTest {
    private val key = ByteArray(32) { it.toByte() }
    private val text = "synthetic-only רכב 1234567 רחוב בדיקה 1".toByteArray(Charsets.UTF_8)
    private fun encrypt(bytes: ByteArray = text, column: Column = Column.DRAFT_JSON,
                        id: String = "synthetic-1", revision: Long = 42) =
        LedgerRecordEnvelope.encrypt(bytes, key, column, id, revision)
    private fun decrypt(bytes: ByteArray, dataKey: ByteArray = key,
                        column: Column = Column.DRAFT_JSON, id: String = "synthetic-1", revision: Long = 42) =
        LedgerRecordEnvelope.decrypt(bytes, dataKey, column, id, revision)

    @Test fun roundTripForBothColumnsAndEmptyValuesPreservesCallerInputs() {
        for (column in Column.values()) for (bytes in listOf(text, byteArrayOf())) {
            val before = bytes.copyOf(); val keyBefore = key.copyOf()
            val encrypted = encrypt(bytes, column)
            assertEquals(bytes.size + 29, encrypted.size)
            assertArrayEquals(bytes, decrypt(encrypted, column = column))
            assertArrayEquals(before, bytes); assertArrayEquals(keyBefore, key)
            assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("synthetic-only"))
        }
    }
    @Test fun nonceIsFreshForRepeatedWrites() {
        val first = encrypt(); val second = encrypt()
        assertFalse(first.contentEquals(second))
        assertFalse(first.copyOfRange(1, 13).contentEquals(second.copyOfRange(1, 13)))
        assertArrayEquals(text, decrypt(first)); assertArrayEquals(text, decrypt(second))
    }
    @Test fun everyEnvelopeByteIsAuthenticatedOrRejected() {
        val encrypted = encrypt()
        for (index in encrypted.indices) {
            val tampered = encrypted.copyOf(); tampered[index] = (tampered[index].toInt() xor 1).toByte()
            assertThrows(Exception::class.java) { decrypt(tampered) }
        }
    }
    @Test fun everyTruncationAndTrailingBytesRefuse() {
        val encrypted = encrypt()
        for (size in 0 until encrypted.size) assertThrows(Exception::class.java) { decrypt(encrypted.copyOf(size)) }
        assertThrows(Exception::class.java) { decrypt(encrypted + byteArrayOf(0)) }
    }
    @Test fun wrongKeyRecordColumnAndRevisionRefuseWithoutPlaintextFallback() {
        val encrypted = encrypt()
        assertThrows(Exception::class.java) { decrypt(encrypted, dataKey = ByteArray(32) { 99 }) }
        assertThrows(Exception::class.java) { decrypt(encrypted, column = Column.REVIEWED_DIGEST) }
        assertThrows(Exception::class.java) { decrypt(encrypted, id = "synthetic-2") }
        for (revision in listOf(0L, 41L, 43L, Long.MAX_VALUE))
            assertThrows(Exception::class.java) { decrypt(encrypted, revision = revision) }
        assertThrows(Exception::class.java) { decrypt(text) }
    }
    @Test fun identityAndKeyValidationFailClosed() {
        for (id in listOf("", " ", "id\u0000other")) {
            assertThrows(IllegalArgumentException::class.java) { encrypt(id = id) }
            assertThrows(IllegalArgumentException::class.java) { decrypt(encrypt(), id = id) }
        }
        assertThrows(IllegalArgumentException::class.java) { encrypt(revision = -1) }
        assertThrows(IllegalArgumentException::class.java) { decrypt(encrypt(), revision = -1) }
        for (size in listOf(0, 16, 24, 31, 33)) {
            assertThrows(IllegalArgumentException::class.java) {
                LedgerRecordEnvelope.encrypt(text, ByteArray(size), Column.DRAFT_JSON, "id", 0)
            }
            assertThrows(IllegalArgumentException::class.java) { decrypt(encrypt(), dataKey = ByteArray(size)) }
        }
    }
    @Test fun utf8IdentitiesAndRevisionBoundariesRoundTrip() {
        for (id in listOf("synthetic-1", "בדיקה", "é", "e\u0301")) for (revision in listOf(0L, 1L, Long.MAX_VALUE)) {
            val encrypted = encrypt(id = id, revision = revision)
            assertArrayEquals(text, decrypt(encrypted, id = id, revision = revision))
        }
        assertThrows(Exception::class.java) { decrypt(encrypt(id = "é"), id = "e\u0301") }
    }
}
