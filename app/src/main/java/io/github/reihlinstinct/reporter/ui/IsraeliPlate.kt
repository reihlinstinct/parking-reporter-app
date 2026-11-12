package io.github.reihlinstinct.reporter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Display-only. Only a regular Israeli plate (7 or 8 ASCII digits, the same shape the
 * registry lookup accepts) is drawn as a yellow plate. Everything else stays plain text.
 * Colors are fixed on purpose: a plate is not themed. No registry or draft logic here.
 */
internal object IsraeliPlateStyle {
    val Yellow = Color(0xFFFFD500)
    val Ink = Color(0xFF111111)
    val Band = Color(0xFF003DA5)
    val BandText = Color.White
    /** Fixed plate mark (country code on the plate), not translatable UI text. */
    const val COUNTRY_MARK = "IL"
    fun isRegular(plate: String): Boolean = plate.matches(Regex("[0-9]{7,8}"))
}

/**
 * [description] is the existing localized "Plate: N" string. It is the node text (what the
 * old plain Text exposed, so screen readers and tests see the same string); the drawn
 * glyphs below are hidden from semantics.
 */
@Composable
internal fun IsraeliPlate(plate: String, description: String, modifier: Modifier = Modifier) {
    // Digits and the left IL band never mirror in RTL layouts.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(
            modifier = modifier.semantics { text = AnnotatedString(description) },
            shape = RoundedCornerShape(6.dp),
            color = IsraeliPlateStyle.Yellow,
            border = BorderStroke(2.dp, IsraeliPlateStyle.Ink),
        ) {
            Row(Modifier.height(IntrinsicSize.Min).clearAndSetSemantics { }) {
                Box(
                    Modifier.fillMaxHeight().width(28.dp).background(IsraeliPlateStyle.Band),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(IsraeliPlateStyle.COUNTRY_MARK, color = IsraeliPlateStyle.BandText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    PlateEntry.format(plate),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = IsraeliPlateStyle.Ink,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 24.sp),
                )
            }
        }
    }
}
