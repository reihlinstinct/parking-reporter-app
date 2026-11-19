package io.github.reihlinstinct.reporter

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.i18n.AppCatalog
import io.github.reihlinstinct.reporter.i18n.ProvideStrings
import io.github.reihlinstinct.reporter.security.ReporterSettings
import io.github.reihlinstinct.reporter.ui.SettingsScreen
import io.github.reihlinstinct.reporter.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic callback proof only. Real credential enforcement is separately pinned in AppShell. */
class ApprovedCopySettingsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun passportConfirmationCancelAndConfirmAreSeparate() {
        var requests = 0
        val initial = ReporterSettings("בדיקה", "מדווח", "0500000000", "000000018", "synthetic@example.invalid",
            passportMode = true, passportNumber = "SYNTHETIC123")
        compose.setContent { AppTheme { SettingsScreen(initial, { _, _, _ -> }, {},
            deleteSavedPassport = { requests++ }) } }
        compose.onNodeWithText("מחק מספר דרכון שמור").performScrollTo().performClick()
        compose.onNodeWithText("למחוק את מספר הדרכון השמור?").assertIsDisplayed()
        capture("passport-delete-confirmation.png")
        compose.onNodeWithText("ביטול").performClick()
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithText("מחק מספר דרכון שמור").performScrollTo().performClick()
        compose.onNodeWithText("מחק", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }
    @Test fun frenchMachineDraftNoticeIsVisible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = AppCatalog.get(context)
        val french = catalog.languages.single { it.nativeName == "Français" }
        compose.setContent { AppTheme { ProvideStrings(catalog, french) {
            SettingsScreen(null, { _, _, _ -> }, {}, selectedLanguage = french.code, onLanguageSelected = {})
        } } }
        val notice = catalog.t(french.code, "settings.language.machine_notice")
        compose.onNodeWithText(notice).performScrollTo().assertIsDisplayed()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithText(notice).assertIsDisplayed()
        capture("french-machine-draft-notice.png")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val inst = InstrumentationRegistry.getInstrumentation()
        val bitmap = compose.onNode(isRoot()).captureToImage().asAndroidBitmap()
        File(inst.targetContext.filesDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
