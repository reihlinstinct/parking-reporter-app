package io.github.reihlinstinct.reporter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TagIconThemeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun iconsKeepContrastingContoursAt24And32DpOnBothThemes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resources = listOf(R.drawable.ic_disability_tag_triangle, R.drawable.ic_disability_tag_wheelchair)
        // Render the Android vectors at the real display density, not SVG approximations.
        for (resource in resources) for (dp in listOf(24, 32)) for (background in listOf(Color.WHITE, Color.BLACK)) {
            val size = (dp * context.resources.displayMetrics.density).toInt()
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(background)
            context.getDrawable(resource)!!.mutate().apply { setBounds(0, 0, size, size); draw(Canvas(bitmap)) }
            // The lower centre crosses the white exterior and the dark inner contour on both shapes.
            val pixels = (size / 2 until size).map { bitmap.getPixel(size / 2, it) }
            fun luminance(c: Int): Double {
                fun channel(v: Int): Double { val s = v / 255.0; return if (s <= .04045) s / 12.92 else Math.pow((s + .055) / 1.055, 2.4) }
                return .2126 * channel(Color.red(c)) + .7152 * channel(Color.green(c)) + .0722 * channel(Color.blue(c))
            }
            assertTrue("light contour must remain visible on dark", pixels.count { luminance(it) > .8 } >= (size / 24).coerceAtLeast(1))
            assertTrue("dark inner contour must remain visible on light", pixels.any { luminance(it) < .2 })
            val file = File(context.filesDir, "tag-boundary-$resource-$dp-${if (background == Color.BLACK) "dark" else "light"}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.setContent {
            Column {
                for (dark in listOf(false, true)) {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(24.dp)) {
                            Text(if (dark) "כהה / Dark" else "בהיר / Light", color = MaterialTheme.colorScheme.onSurface)
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                for (size in listOf(24, 32)) for (resource in resources) {
                                    Image(painterResource(resource), "תג חניה", Modifier.size(size.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(context.filesDir, "tag-theme-grid.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
