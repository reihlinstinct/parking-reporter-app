package io.github.reihlinstinct.reporter

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import org.junit.After
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

    private var priorImeSetting: String? = null
    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ).use { String(it.readBytes()).trim() }

    @After fun restoreImeSetting() {
        val prior = priorImeSetting ?: return
        if (prior == "0" || prior == "1") shell("settings put secure show_ime_with_hard_keyboard $prior")
        else shell("settings delete secure show_ime_with_hard_keyboard")
    }

    @Before fun productionEdgeToEdgeAndRealIme() {
        InstrumentationRegistry.getInstrumentation().targetContext.let {
            io.github.reihlinstinct.reporter.i18n.UiPrefsLanguageStore.of(it).set(
                io.github.reihlinstinct.reporter.i18n.AppCatalog.defaultCode(it))
        }
        priorImeSetting = shell("settings get secure show_ime_with_hard_keyboard")
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            // Mirrors android:windowSoftInputMode="adjustResize" on the production
            // MainActivity (pinned by tests/test_manifest_ime.py). Before API 30
            // the system only reports the keyboard inset in this mode (#471).
            it.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        shell("settings put secure show_ime_with_hard_keyboard 1")
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

    @Test fun shellSettingsSaveStaysAboveRealIme() = shellSettingsKeyboardCase(1f)

    @Test fun shellSettingsSaveStaysAboveRealImeAt200Percent() {
        org.junit.Assume.assumeTrue(
            "200% proof is manual/weekly, not every PR",
            InstrumentationRegistry.getArguments().getString("extendedKeyboardMatrix") == "1")
        shellSettingsKeyboardCase(2f)
    }

    private fun shellSettingsKeyboardCase(fontScale: Float) {
        seedSyntheticCityTopics()
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit.Density(density.density, fontScale)
            ) {
                AppTheme {
                    io.github.reihlinstinct.reporter.ui.AppShell(
                        authenticate = { _, _, action -> action() },
                        requestCamera = {}, readSettings = { null }, saveSettings = {},
                        municipalClientFactory = { error("No municipal client is allowed in this test") })
                }
            }
        }
        compose.onNodeWithText("הגדרות").performClick()
        compose.onNodeWithText("אימייל").performScrollTo().performClick()
            .performTextInput("synthetic@example.invalid")
        waitForIme()
        compose.onNodeWithText("אמת זהות ושמור מוצפן").performScrollTo().assertIsDisplayed()
        assertAboveIme("אמת זהות ושמור מוצפן")
        capture("shell-settings-real-ime-${fontScale}.png")
    }

    @Test fun reviewPlateAndDescriptionKeepBothActionsAboveRealIme() {
        val initial = ReviewDraft(Evidence("ime-actions-synthetic", "a".repeat(64),
            "2026-01-01T12:00:00+02:00", 31.77, 35.21), "1234567", "רחוב בדיקה 1",
            "sidewalk_parking", "רכב 1234567 חונה על המדרכה", emptySet())
        compose.setContent { AppTheme { ReviewScreen(initial, null, {}, {}, onSubmit = {},
            topicSnapshot = syntheticCityTopics(), topicsAvailable = true,
            registryLookup = { _, _ -> kotlinx.coroutines.awaitCancellation() }) } }
        compose.onNodeWithText("ערוך").performClick()
        for ((field, value) in listOf(
            "מספר לוחית (ספרות בלבד)" to "1234567",
            "הטקסט המדויק לבדיקה" to "רכב 1234567 חונה על המדרכה בתרחיש סינתטי")) {
            compose.onNodeWithText(field).performScrollTo().performClick().performTextReplacement(value)
            waitForIme()
            compose.onNodeWithText(field).assertTextContains(value)
            for (action in listOf("שמור טיוטה", "שלח לעירייה")) {
                compose.onNodeWithText(action).performScrollTo().assertIsDisplayed()
                assertAboveIme(action)
            }
            capture("review-actions-real-ime-${field.hashCode()}.png")
        }
    }

    private fun waitForIme() {
        compose.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.let { it.isVisible(WindowInsetsCompat.Type.ime()) && it.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0 } == true
        }
    }

    private fun assertAboveIme(label: String) {
        waitForIme()
        capture("ime-before-geometry-${label.hashCode()}.png")
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
