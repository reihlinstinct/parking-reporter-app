package io.github.reihlinstinct.reporter.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // Issue #354: owner-supplied examples of non-regular plates, examples only (docs/PLATE_CLASSES.md).
    // This pins the raw strings only: they are not drawn as a regular plate and PlateNumber.parse rejects
    // them. It does NOT cover the manual field or OCR, which strip to digits first (known gap in the doc).
    @Test fun rawSpecialLookingStringsAreNotRegularAndNotParsed() {
        listOf("21-12-20 מ", "2112 20מ", "211220מ", "CD 23 044 22", "CD2304422", "CC 12 345", "174142 צ", "174142צ").forEach {
            assertFalse("'$it' must not be styled as regular", IsraeliPlateStyle.isRegular(it))
            assertNull("'$it' must not be a registry plate", io.github.reihlinstinct.reporter.registry.PlateNumber.parse(it))
        }
    }
}
