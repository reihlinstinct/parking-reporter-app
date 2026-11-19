package io.github.reihlinstinct.reporter

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import io.github.reihlinstinct.reporter.ui.AppTheme
import io.github.reihlinstinct.reporter.ui.AddressDataTools
import io.github.reihlinstinct.reporter.ui.SettingsScreen
import org.junit.Rule
import org.junit.Test
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry

class AddressSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun optionalAddressImportIsOnlyInAdvancedSettings() {
        compose.setContent {
            AppTheme {
                SettingsScreen(null, save = { _, _, _ -> }, back = {}, addressTools = {
                    AddressDataTools(false, true, "", {}, {}, {})
                })
            }
        }
        compose.onNodeWithTag("address-import").assertDoesNotExist()
        compose.onNodeWithText("חיבור 106 מתקדם").performScrollTo().performClick()
        compose.onNodeWithTag("address-import").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("address-refresh").assertIsEnabled()
        compose.onNodeWithTag("address-clear").performScrollTo().assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "address-advanced-settings.png").outputStream().use {
            compose.onNodeWithTag("address-data-tools").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
