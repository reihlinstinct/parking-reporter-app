package io.github.reihlinstinct.reporter.core

import io.github.reihlinstinct.reporter.recognition.PlateCandidates
import org.junit.Assert.*
import org.junit.Test

class PlateCandidatesTest {
    @Test fun supportedNumericShapesNormalizeAndDeduplicate() {
        assertEquals(listOf("1234567", "12345678"), PlateCandidates.fromText("12-345-67 1234567 123-45-678"))
    }
    @Test fun ambiguousLettersAndWrongLengthsNeverBecomePlate() {
        assertTrue(PlateCandidates.fromText("123456 123456789 12O4567 AB1234567").isEmpty())
        assertTrue(PlateCandidates.fromText("12 345 67").isEmpty())
    }
    @Test fun boundedCandidateCountAndInput() {
        assertEquals(10, PlateCandidates.fromText((1..100).joinToString(" ") { (1000000 + it).toString() }).size)
    }
    @Test fun runNextToHebrewClassLetterIsNotARegularPlate() {
        val mem = "\u05DE"; val tsadi = "\u05E6"
        assertTrue(PlateCandidates.fromText("1234567$mem").isEmpty())
        assertTrue(PlateCandidates.fromText("$mem" + "12345678").isEmpty())
        assertTrue(PlateCandidates.fromText("12345678 $tsadi").isEmpty())
        assertTrue(PlateCandidates.fromText("$tsadi-1234567").isEmpty())
        assertTrue(PlateCandidates.fromText("12-345-67 $mem").isEmpty())
        assertEquals(listOf("1234567"), PlateCandidates.fromText("1234567 \u05E8\u05DB\u05D1"))
    }
}
