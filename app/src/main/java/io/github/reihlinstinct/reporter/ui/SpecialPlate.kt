package io.github.reihlinstinct.reporter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.reihlinstinct.reporter.core.PlateClass

/**
 * Display-only, per the class the user picked. Police is drawn red with white characters (police red plate
 * spec 119/2016, docs/PLATE_CLASSES.md). CD and CC have no verified colour, so they stay neutral grey.
 * Colours are fixed on purpose, like the regular plate. No draft or registry logic here.
 */
@Composable
internal fun SpecialPlate(text: String, plateClass: PlateClass, description: String, modifier: Modifier = Modifier) {
    val police = plateClass == PlateClass.POLICE
    val background = if (police) Color(0xFFB71C1C) else Color(0xFFEEEEEE)
    val ink = if (police) Color.White else Color(0xFF111111)
    val edge = if (police) Color(0xFF7F0000) else Color(0xFF555555)
    Surface(color = background, shape = RoundedCornerShape(6.dp), border = BorderStroke(2.dp, edge),
        modifier = modifier.clearAndSetSemantics { this.text = AnnotatedString(description) }) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(text, color = ink, style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 28.sp),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}
