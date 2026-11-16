package io.github.reihlinstinct.reporter.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/** Near-expiry warning token contrast (issue #367): AA text on both theme surfaces. */
class WarningColorTest {
    private fun assertAa(name: String, background: Int, foreground: Int) {
        val ratio = Palette.contrast(background, foreground)
        assertTrue("$name contrast %.2f:1 is below AA 4.5:1".format(ratio), ratio >= 4.5)
    }

    @Test fun lightWarningMeetsAaOnSurfaceAndBackground() {
        assertAa("light surface", Palette.light.surface, WarningTokens.LIGHT)
        assertAa("light background", Palette.light.background, WarningTokens.LIGHT)
    }

    @Test fun darkWarningMeetsAaOnSurfaceAndBackground() {
        assertAa("dark surface", Palette.dark.surface, WarningTokens.DARK)
        assertAa("dark background", Palette.dark.background, WarningTokens.DARK)
    }

    @Test fun warningIsDistinctFromErrorInBothThemes() {
        assertTrue(WarningTokens.LIGHT != Palette.light.error)
        assertTrue(WarningTokens.DARK != Palette.dark.error)
    }
}
