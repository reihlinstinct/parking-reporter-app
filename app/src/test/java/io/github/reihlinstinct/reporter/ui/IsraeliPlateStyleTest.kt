package io.github.reihlinstinct.reporter.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsraeliPlateStyleTest {
    @Test fun regularSevenAndEightDigitPlatesAreStyled() {
        assertTrue(IsraeliPlateStyle.isRegular("1234567"))
        assertTrue(IsraeliPlateStyle.isRegular("12345678"))
    }

    @Test fun everythingElseStaysPlainText() {
        listOf("", "123456", "123456789", "12-345-67", "1234567A", "א123456", "١٢٣٤٥٦٧", "12 34567").forEach {
            assertFalse("'$it' must not be drawn as a regular plate", IsraeliPlateStyle.isRegular(it))
        }
    }
}
