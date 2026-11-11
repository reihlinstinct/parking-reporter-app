package io.github.reihlinstinct.reporter

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import org.junit.Before
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.core.Evidence
import io.github.reihlinstinct.reporter.core.ReviewDraft
import io.github.reihlinstinct.reporter.ui.ReviewScreen
import io.github.reihlinstinct.reporter.ui.SettingsScreen
import io.github.reihlinstinct.reporter.ui.theme.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class KeyboardInsetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun productionEdgeToEdgeAndRealIme() {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "settings put secure show_ime_with_hard_keyboard 1").close()
    }

    @Test fun settingsLastEmailAndSaveReasonStayReachableWithRealKeyboard() {
        compose.setContent { AppTheme { SettingsScreen(null, { _, _, _ -> }, {}) } }
        compose.onNodeWithText("אימייל").performScrollTo().performClick().performTextInput("synthetic@example.invalid")
        waitForIme()
        compose.onNodeWithText("אמת זהות ושמור מוצפן").performScrollTo().assertIsDisplayed()
        assertAboveIme("אמת זהות ושמור מוצפן")
        capture("settings-real-ime-synthetic.png")
    }

    @Test fun manualAddressLookupRemainsReachableWithRealKeyboard() {
        val initial = ReviewDraft(Evidence("ime-synthetic", "a".repeat(64),
            "2026-01-01T12:00:00+02:00", null, null), "1234567", "", "sidewalk_parking", "בדיקה", setOf("missing_gps"))
        compose.setContent { AppTheme { ReviewScreen(initial, null, {}, {}) } }
        compose.onNodeWithText("רחוב בירושלים").performScrollTo().performClick().performTextInput("רחוב בדיקה")
        waitForIme()
        compose.onNodeWithText("מספר בית").performScrollTo().performClick().performTextInput("12")
        waitForIme()
        compose.onNodeWithText("חפש כתובת מדויקת").performScrollTo().assertIsDisplayed()
        assertAboveIme("חפש כתובת מדויקת")
        capture("review-real-ime-synthetic.png")
    }

    private fun waitForIme() {
        compose.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
    }

    private fun assertAboveIme(label: String) {
        val decor = compose.activity.window.decorView
        val inset = ViewCompat.getRootWindowInsets(decor)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val target = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Real keyboard must have a measurable inset", inset > 0)
        // imePadding shrinks the scroll viewport. The complete action must fit.
        assertTrue("Action remains in the visible content: $target vs $root", target.top >= root.top && target.bottom <= minOf(root.bottom, (decor.height - inset).toFloat()))
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
