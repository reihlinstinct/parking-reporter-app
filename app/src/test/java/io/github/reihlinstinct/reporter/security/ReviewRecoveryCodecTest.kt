package io.github.reihlinstinct.reporter.security

import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

class ReviewRecoveryCodecTest {
    private fun fixture(plate: String = "1122334", text: String = "שורה ראשונה\nsynthetic second line",
        rawPlate: String = "CD 12-", rawLat: String = "31.75", lat: Double? = 31.75, gps: Boolean = true,
        warnings: Set<String> = setOf("address_needs_confirmation"), edits: Int = 3,
        plateClass: String = "regular", rendered: List<ReviewRecoveryCodec.Sentence> = listOf(
            ReviewRecoveryCodec.Sentence("state.synthetic", "synthetic template"))) =
        ReviewRecoveryCodec.Snapshot(plate, plateClass, false, "synthetic address", "synthetic subject", text,
            warnings, "2026-11-16T12:00:00Z", lat, 35.25, rawPlate, edits, rawLat, "35.25",
            "raw street", "12a", "cross street", "saved street", "11", "7654321", gps,
            true, true, true, "synthetic-tag", rendered, true, "baseline street",
            setOf("suggested_address_needs_confirmation"))
    private inline fun <reified T : Exception> rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection") }
        catch (e: Exception) { assertTrue("Wrong exception type: ${e::class.java.name}", e is T) }
    }
    private fun fields(s: ReviewRecoveryCodec.Snapshot): List<Any?> = listOf(
        s.plate, s.plateClass, s.plateRequired, s.address, s.subject, s.text, s.unresolved,
        s.confirmedTime, s.confirmedLatitude, s.confirmedLongitude, s.plateRaw, s.plateEdits,
        s.latitudeRaw, s.longitudeRaw, s.manualStreet, s.manualHouse, s.manualCross,
        s.savedManualStreet, s.savedManualHouse, s.ocrPrefilledPlate, s.gpsFilled, s.textTyped,
        s.editing, s.confirmExit, s.templateTag, s.templateRendered.map { it.id to it.text },
        s.templateFrozen, s.addressBaseline, s.pendingAddressWarnings)

    @Test fun roundTripEveryFieldAndPreserveRawDirtyBaseline() {
        val source = fixture(); val bytes = ReviewRecoveryCodec.encode(source)
        val result = ReviewRecoveryCodec.decode(bytes)
        assertEquals(fields(source), fields(result))
        assertEquals("CD 12-", result.plateRaw)
        assertNotEquals(result.savedManualStreet, result.manualStreet)
        assertNotEquals(result.savedManualHouse, result.manualHouse)
        assertEquals(3, result.plateEdits)
        assertEquals("7654321", result.ocrPrefilledPlate)
        assertArrayEquals(bytes, ReviewRecoveryCodec.encode(result))
    }
    @Test fun rawPlateUsesProducerPredicateForAllAcceptedCharacterClasses() {
        val raw = "\u200F\u200E\u202A\u202E\u2066\u2069\u2003cd CD cc \u05DE-\u0661\u0662\uFF13\t"
        assertTrue(io.github.reihlinstinct.reporter.core.PlateDetect.allowed(raw))
        val result = ReviewRecoveryCodec.decode(ReviewRecoveryCodec.encode(fixture(rawPlate = raw)))
        assertEquals(raw, result.plateRaw)
        // Every UTF-16 character accepted by the producer must pass the codec unchanged (within byte bound).
        for (code in 0..Char.MAX_VALUE.code) {
            val value = code.toChar().toString()
            if (io.github.reihlinstinct.reporter.core.PlateDetect.allowed(value)) {
                assertEquals(value, ReviewRecoveryCodec.decode(ReviewRecoveryCodec.encode(fixture(rawPlate = value))).plateRaw)
            }
        }
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.encode(fixture(rawPlate = "<")) }
    }
    @Test fun longRawPlateFailsClosedWithoutTruncatingUserInput() {
        val multibyte = "\u0661".repeat(ReviewRecoveryCodec.PLATE_BYTES / 2)
        assertEquals(multibyte, ReviewRecoveryCodec.decode(ReviewRecoveryCodec.encode(fixture(rawPlate = multibyte))).plateRaw)
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.encode(fixture(rawPlate = multibyte + "1")) }
        val exact = "1".repeat(ReviewRecoveryCodec.PLATE_BYTES)
        assertEquals(exact, ReviewRecoveryCodec.decode(ReviewRecoveryCodec.encode(fixture(rawPlate = exact))).plateRaw)
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.encode(fixture(rawPlate = exact + "1")) }
    }
    @Test fun incompleteCoordinatesAndEmptyPlateAreNotNormalized() {
        val source = fixture(plate = "", rawLat = "-", lat = null, gps = false)
        val result = ReviewRecoveryCodec.decode(ReviewRecoveryCodec.encode(source))
        assertEquals(fields(source), fields(result))
        assertEquals("", result.plate); assertEquals("-", result.latitudeRaw)
    }
    @Test fun rejectNonfiniteOutOfRangeAndInconsistentGps() {
        for (raw in listOf("NaN", "Infinity", "91")) rejects<IllegalArgumentException> {
            ReviewRecoveryCodec.encode(fixture(rawLat = raw, gps = false))
        }
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.encode(fixture(lat = Double.NaN)) }
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.encode(fixture(lat = 91.0)) }
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.encode(fixture(rawLat = "31.76")) }
    }
    @Test fun rejectUnknownEnumsWarningsAndInvalidProvenance() {
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.encode(fixture(plateClass = "new")) }
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.encode(fixture(warnings = setOf("send_approved"))) }
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.encode(fixture(edits = -1)) }
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.encode(fixture(rendered = listOf(
            ReviewRecoveryCodec.Sentence("same", "one"), ReviewRecoveryCodec.Sentence("same", "two")))) }
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.encode(fixture(rendered = List(33) {
            ReviewRecoveryCodec.Sentence("s$it", "synthetic") })) }
    }
    @Test fun byteNotCharacterLimitsAndMalformedSurrogates() {
        val exact = fixture(text = "א".repeat(ReviewRecoveryCodec.TEXT_BYTES / 2))
        assertEquals(fields(exact), fields(ReviewRecoveryCodec.decode(ReviewRecoveryCodec.encode(exact))))
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.encode(fixture(text = exact.text + "א")) }
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.encode(fixture(plate = "1".repeat(65))) }
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.encode(fixture(text = "\uD800")) }
    }
    @Test fun malformedUtf8AndLengthRejectBeforeAllocation() {
        val bytes = ReviewRecoveryCodec.encode(fixture())
        rejects<CharacterCodingException> { ReviewRecoveryCodec.decode(bytes.copyOf().also { it[8] = 0xff.toByte() }) }
        for (length in listOf(-1, Int.MAX_VALUE, ReviewRecoveryCodec.PLATE_BYTES + 1)) {
            rejects<IllegalArgumentException> { ReviewRecoveryCodec.decode(bytes.copyOf().also {
                ByteBuffer.wrap(it).putInt(4, length)
            }) }
        }
    }
    @Test fun unknownVersionTrailingFieldsAndTruncationReject() {
        val bytes = ReviewRecoveryCodec.encode(fixture())
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.decode(bytes.copyOf().also { it[3] = 2 }) }
        // Positional schema has no keys or extension map: duplicate/unknown fields can only be extra bytes.
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.decode(bytes + bytes) }
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.decode(bytes + "approval=true".toByteArray()) }
        for (length in 4 until bytes.size) {
            try { ReviewRecoveryCodec.decode(bytes.copyOf(length)); fail("Truncated input accepted") }
            catch (_: EOFException) { }
            catch (_: IllegalArgumentException) { }
        }
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.decode(ByteArray(ReviewRecoveryEnvelope.MAX_PAYLOAD_BYTES + 1)) }
    }
    @Test fun invalidBooleanDoesNotCoerceToTrue() {
        val bytes = ReviewRecoveryCodec.encode(fixture())
        // version + plate length/value + plateClass length/value = nullable plate policy byte.
        val offset = 4 + 4 + 7 + 4 + 7
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.decode(bytes.copyOf().also { it[offset] = 3 }) }
    }
    @Test fun decodeValidatesEnumsWarningsAndProvenanceToo() {
        val bytes = ReviewRecoveryCodec.encode(fixture())
        // Locate fields by their length prefixes, not hard-coded plaintext offsets.
        val b = ByteBuffer.wrap(bytes)
        b.int
        fun skipText() { val n = b.int; b.position(b.position() + n) }
        skipText(); val classStart = b.position() + 4
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.decode(bytes.copyOf().also { it[classStart] = 'X'.code.toByte() }) }
        skipText(); b.get(); repeat(3) { skipText() }
        val warningCount = b.position(); b.int
        val warningStart = b.position() + 4
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.decode(bytes.copyOf().also { it[warningStart] = 'X'.code.toByte() }) }
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.decode(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(warningCount, Int.MAX_VALUE) }) }
        skipText(); b.get(); skipText(); b.get(); b.double; b.get(); b.double; skipText()
        val editStart = b.position()
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.decode(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(editStart, -1) }) }
        // gpsFilled follows raw coords, five manual strings and nullable OCR string.
        b.int; repeat(7) { skipText() }; b.get(); skipText()
        val boolOffset = b.position()
        rejects<IllegalArgumentException> { ReviewRecoveryCodec.decode(bytes.copyOf().also { it[boolOffset] = 2 }) }
    }
    @Test fun snapshotCollectionsDefensivelyCopied() {
        val warnings = mutableSetOf("address_needs_confirmation")
        val source = fixture(warnings = warnings)
        warnings.clear()
        assertEquals(setOf("address_needs_confirmation"), source.unresolved)
        try { (source.unresolved as MutableSet).clear() } catch (_: UnsupportedOperationException) { }
        assertEquals(setOf("address_needs_confirmation"), source.unresolved)
        try { (source.templateRendered as MutableList).clear() } catch (_: UnsupportedOperationException) { }
        assertEquals(1, source.templateRendered.size)
    }
    @Test fun nullablesAndSafeDefaultsRoundTrip() {
        val source = ReviewRecoveryCodec.Snapshot("", "regular", null, "", "", "", emptySet(), null,
            null, null, "", 0, "", "", "", "", "", "", "", null, false, false, false, false,
            null, emptyList(), false, "", emptySet())
        assertEquals(fields(source), fields(ReviewRecoveryCodec.decode(ReviewRecoveryCodec.encode(source))))
    }
    @Test fun encryptedCodecCompositionRoundTrip() {
        val source = fixture()
        val binding = ReviewRecoveryEnvelope.Binding("a".repeat(32), "synthetic-1", "b".repeat(64),
            "c".repeat(64), 7, 197, 4, 1000, 901000)
        val key = ByteArray(32) { it.toByte() }
        val plain = ReviewRecoveryCodec.encode(source)
        val encrypted = try { ReviewRecoveryEnvelope.seal(plain, key, binding, 2000, 4, 197) }
            finally { plain.fill(0) }
        val recovered = ReviewRecoveryEnvelope.open(encrypted, key, binding, 2000, 4, 197)
        val result = try { ReviewRecoveryCodec.decode(recovered) } finally { recovered.fill(0); key.fill(0) }
        assertEquals(fields(source), fields(result))
    }
    @Test fun redactedDiagnostics() {
        assertEquals("ReviewRecoverySnapshot(redacted)", fixture().toString())
        assertEquals("ReviewRecoverySentence(redacted)", fixture().templateRendered.first().toString())
    }
}
