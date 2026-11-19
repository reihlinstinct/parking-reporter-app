package io.github.reihlinstinct.reporter

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.ui.IsraeliPlate
import io.github.reihlinstinct.reporter.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/** #391: synthetic display grid; exact source text remains one accessible plate identity. */
class RegularPlateScaleTest {
    @get:Rule val compose = createComposeRule()
    @Test fun regularPlatesAtNormalAndDoubleFontScaleOnBothThemes() {
        compose.setContent {
            val density = LocalDensity.current.density
            Column {
                for (dark in listOf(false, true)) AppTheme(darkTheme = dark) {
                    Surface {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            for (scale in listOf(1f, 2f)) CompositionLocalProvider(
                                LocalDensity provides Density(density, scale)) {
                                IsraeliPlate("1234567", "synthetic-plate-$dark-$scale")
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
        for (dark in listOf(false, true)) for (scale in listOf(1f, 2f)) {
            compose.onNodeWithText("synthetic-plate-$dark-$scale").assertIsDisplayed()
        }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.filesDir, "regular-plate-font-scale-light-dark.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
