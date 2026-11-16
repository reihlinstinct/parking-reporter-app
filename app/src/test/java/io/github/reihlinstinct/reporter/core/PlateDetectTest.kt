package io.github.reihlinstinct.reporter.core

import org.junit.Assert.*
import org.junit.Test

class PlateDetectTest {
    private fun d(s: String) = PlateDetect.detect(s)

    @Test fun plainDigitsAreRegular() {
        assertEquals(PlateDetect.Result("1234567", PlateClass.REGULAR), d("1234567"))
        assertEquals(PlateDetect.Result("12345678", PlateClass.REGULAR), d(" 123 45 678 "))
        assertEquals(PlateDetect.Result("12345678", PlateClass.REGULAR), d("\u06F1\u06F2\u06F3\u06F4\u06F5\u06F6\u06F7\u06F8"))
    }
    @Test fun regularDisplayGroupingStaysRegular() {
        assertEquals(PlateDetect.Result("1234567", PlateClass.REGULAR), d("12-345-67"))
        assertEquals(PlateDetect.Result("12345678", PlateClass.REGULAR), d("123-45-678"))
    }
    @Test fun policeByHyphenOrTrailingMem() {
        assertEquals(PlateDetect.Result("11-22-33", PlateClass.POLICE), d("11-22-33"))
        assertEquals(PlateDetect.Result("11-22-33", PlateClass.POLICE), d("11-22-33 \u05DE"))
        assertEquals(PlateDetect.Result("123456", PlateClass.POLICE), d("123456\u05DE"))
        assertEquals(PlateDetect.Result("1234567", PlateClass.POLICE), d("1234567 \u05DE"))
        assertEquals(PlateDetect.Result("11-22", PlateClass.POLICE), d("11--22-"))
    }
    @Test fun consularAndDiplomaticByPrefix() {
        assertEquals(PlateDetect.Result("123", PlateClass.DIPLOMATIC_CD), d("CD 123"))
        assertEquals(PlateDetect.Result("123", PlateClass.DIPLOMATIC_CD), d("cd123"))
        assertEquals(PlateDetect.Result("4567", PlateClass.CONSULAR_CC), d("Cc-45 67"))
        assertEquals(PlateDetect.Result("", PlateClass.DIPLOMATIC_CD), d("CD"))
    }
    @Test fun mixedOrPartialMarkersAreNeverReady() {
        assertEquals(PlateDetect.Result("", PlateClass.REGULAR), d("CD 123 \u05DE"))
        assertEquals(PlateDetect.Result("", PlateClass.REGULAR), d("C"))
        assertEquals(PlateDetect.Result("", PlateClass.REGULAR), d("123 CD"))
        assertFalse(PlateClasses.ready(d("CD 123 \u05DE").plate, d("CD 123 \u05DE").plateClass))
    }
    @Test fun otherLettersAreNotAllowed() {
        assertFalse(PlateDetect.allowed("12345x"))
        assertTrue(PlateDetect.allowed("cd 12-3 \u05DE"))
        assertEquals("", d("12345x").plate)
    }
    @Test fun detectedResultsMatchReadinessRules() {
        assertTrue(PlateClasses.ready(d("12-345-67").plate, d("12-345-67").plateClass))
        assertTrue(PlateClasses.ready(d("11-22-33 \u05DE").plate, d("11-22-33 \u05DE").plateClass))
        assertTrue(PlateClasses.ready(d("CD 5").plate, d("CD 5").plateClass))
        assertFalse(PlateClasses.ready(d("123456").plate, d("123456").plateClass))
    }

    @Test fun typingNeverDeletesUnrelatedReportLines() {
        val text = "Line A seen at 12:30\nLine B house 12\nLine C"
        var plate = ""; var cls = PlateClass.REGULAR; var cur = text
        for (k in listOf("1", "12", "12-", "12-3", "12-34", "12-345", "12-345-", "12-345-6", "12-345-67")) {
            val f = d(k)
            cur = PlateDetect.textAfterChange(cur, plate, cls, f.plateClass)
            plate = f.plate; cls = f.plateClass
        }
        assertEquals(text, cur)
    }
    @Test fun aCompletePlateLineIsRemovedWhenTheClassChanges() {
        val text = "Car 1234567 here\nLine B house 12"
        assertEquals("Line B house 12", PlateDetect.textAfterChange(text, "1234567", PlateClass.REGULAR, PlateClass.POLICE))
        assertEquals(text, PlateDetect.textAfterChange(text, "1234567", PlateClass.REGULAR, PlateClass.REGULAR))
    }
    @Test fun policeIsNeverCutToATrailingHyphen() {
        assertFalse(d("11-22-33-44-55-66-77").plate.endsWith("-"))
    }
}
