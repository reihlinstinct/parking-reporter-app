package io.github.reihlinstinct.reporter

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import io.github.reihlinstinct.reporter.ui.theme.AppTheme
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
        compose.onNodeWithTag("address-title").performScrollTo().assertIsDisplayed()
        screenshot("address-advanced-settings-top.png")
        compose.onNodeWithTag("address-import").performScrollTo().assertIsDisplayed()
        screenshot("address-advanced-settings-import.png")
        compose.onNodeWithTag("address-refresh").performScrollTo().assertIsDisplayed().assertIsEnabled()
        screenshot("address-advanced-settings-refresh.png")
        compose.onNodeWithTag("address-clear").performScrollTo().assertIsDisplayed()
        screenshot("address-advanced-settings-bottom.png")
    }

    @Test fun regionalLookupConsentShowsApproximateAreaAndIpBeforeAnyFetch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        io.github.reihlinstinct.reporter.i18n.UiPrefsLanguageStore.of(context).set(io.github.reihlinstinct.reporter.i18n.AppCatalog.defaultCode(context))
        val initial = io.github.reihlinstinct.reporter.core.ReviewDraft(
            io.github.reihlinstinct.reporter.core.Evidence("region-consent-synthetic", "c".repeat(64),
                "2026-01-01T12:00:00+02:00", 31.77, 35.21),
            "1234567", "", "sidewalk_parking", "בדיקה", setOf("address_needs_confirmation")
        ).copy(confirmedLatitude = 31.77, confirmedLongitude = 35.21)
        var fetches = 0
        compose.setContent { AppTheme { io.github.reihlinstinct.reporter.ui.ReviewScreen(
            initial, null, {}, {}, addressLookup = { _, _ -> fetches++; emptyList() }) } }
        compose.onNodeWithText("למצוא רחוב ומספר בית?").assertIsDisplayed()
        org.junit.Assert.assertEquals(0,fetches)
        screenshot("address-regional-consent.png")
        compose.onNode(hasAnyAncestor(isDialog()) and hasText("לא נשלחים קואורדינטות מדויקות, כתובת שהקלדת או פרטי מדווח."))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("כן, חפש כתובת").assertIsDisplayed()
        compose.onNodeWithText("לא עכשיו").assertIsDisplayed()
        org.junit.Assert.assertEquals(0,fetches)
        screenshot("address-regional-consent-bottom.png")
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
