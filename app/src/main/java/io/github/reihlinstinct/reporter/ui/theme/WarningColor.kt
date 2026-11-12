package io.github.reihlinstinct.reporter.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Reviewed near-expiry warning colour (issue #367). Replaces the old surface-brightness
 * heuristic in RegistryStatus. Fixed per theme, never derived from pixels:
 * light 0x9A4900 and dark 0xFFB74D keep the original orange hue, and
 * WarningColorTest pins AA contrast (4.5:1) against the surface and background of the
 * matching municipal palette. The expired warning stays the theme error colour.
 */
object WarningTokens {
    const val LIGHT = 0x9A4900
    const val DARK = 0xFFB74D
}

/** Provided by [AppTheme] from its dark/light decision. Defaults to the light token. */
val LocalWarningColor = compositionLocalOf { opaque(WarningTokens.LIGHT) }
