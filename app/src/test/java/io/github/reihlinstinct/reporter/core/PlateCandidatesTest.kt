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
        assertTrue(PlateCandidates.fromText("$mem 12-345-67").isEmpty())
        assertEquals(listOf("1234567"), PlateCandidates.fromText("1234567 \u05E8\u05DB\u05D1"))
    }
    @Test fun hebrewWordsAroundARunAreNotPlateClassLetters() {
        val nekhim = "\u05E0\u05DB\u05D9\u05DD"; val im = "\u05E2\u05DD"
        val sug = "\u05DE\u05E1\u05D5\u05D2"; val tsahov = "\u05E6\u05D4\u05D5\u05D1"
        assertEquals(listOf("1234567"), PlateCandidates.fromText("$nekhim 1234567"))
        assertEquals(listOf("1234567"), PlateCandidates.fromText("$im 1234567"))
        assertEquals(listOf("1234567"), PlateCandidates.fromText("1234567 $sug"))
        assertEquals(listOf("1234567"), PlateCandidates.fromText("1234567 $tsahov"))
        assertEquals(listOf("1234567"), PlateCandidates.fromText("1234567\n\u05DE"))
        assertEquals(listOf("1234567"), PlateCandidates.fromText("\u05DE\n1234567"))
    }
    @Test fun mlKitSeparatorsAndAnAttachedFlagLetterStillGiveTheDigits() {
        // Synthetic strings in the shapes the real-photo benchmark showed (#210); not real plates.
        assertEquals(listOf("1234567"), PlateCandidates.fromText("12:345-67"))
        assertEquals(listOf("12345678"), PlateCandidates.fromText("123.45-678"))
        assertEquals(listOf("1234567"), PlateCandidates.fromText("I12-345-67"))
        assertEquals(listOf("12345678"), PlateCandidates.fromText("L123:45-678\nIL"))
        assertEquals(listOf("1234567"), PlateCandidates.fromText("12:34567"))
        assertEquals(listOf("12345678"), PlateCandidates.fromText("123.45678"))
    }
    @Test fun looseShapesAreStillRejected() {
        assertTrue(PlateCandidates.fromText("AB12-345-67").isEmpty())
        assertTrue(PlateCandidates.fromText("I12-345-67X").isEmpty())
        assertTrue(PlateCandidates.fromText("12-34567").isEmpty())
        assertTrue(PlateCandidates.fromText("12:345:6").isEmpty())
        assertTrue(PlateCandidates.fromText("I1234567").isEmpty())
        assertTrue(PlateCandidates.fromText("12:345 67").isEmpty())
        assertTrue(PlateCandidates.fromText("1:2:3:4:5:6:7").isEmpty())
    }
}
