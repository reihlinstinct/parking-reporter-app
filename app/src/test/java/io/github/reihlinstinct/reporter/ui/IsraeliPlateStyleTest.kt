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

    // Issue #354: synthetic examples of non-regular plates (docs/PLATE_CLASSES.md).
    // This pins the raw strings only: they are not drawn as a regular plate and PlateNumber.parse rejects
    // them. It does NOT cover the manual field or OCR, which strip to digits first (known gap in the doc).
    @Test fun rawSpecialLookingStringsAreNotRegularAndNotParsed() {
        listOf("11-22-33 מ", "1122 33מ", "112233מ", "CD 11 222 33", "CD1122233", "CC 11 222", "123456 צ", "123456צ").forEach {
            assertFalse("'$it' must not be styled as regular", IsraeliPlateStyle.isRegular(it))
            assertNull("'$it' must not be a registry plate", io.github.reihlinstinct.reporter.registry.PlateNumber.parse(it))
        }
    }
}
