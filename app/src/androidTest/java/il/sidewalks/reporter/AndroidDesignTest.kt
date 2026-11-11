package il.sidewalks.reporter

import android.os.Build
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import il.sidewalks.reporter.ui.AppShell
import il.sidewalks.reporter.ui.SettingsScreen
import il.sidewalks.reporter.ui.theme.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File

class AndroidDesignTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun prepare() {
        compose.activity.runOnUiThread { compose.activity.enableEdgeToEdge() }
        DynamicPaletteStore(context).setEnabled(false)
        il.sidewalks.reporter.i18n.UiPrefsLanguageStore.of(context).set(il.sidewalks.reporter.i18n.AppCatalog.defaultCode(context))
    }
    @After fun cleanup() { DynamicPaletteStore(context).setEnabled(false) }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun municipalDefaultAndOptionalDynamicColorsArePersistedAndReadable() {
        assertFalse(DynamicPaletteStore(context).enabled())
        compose.setContent { AppTheme { NavyStatusBar { SettingsScreen(null, { _, _, _ -> }, {}) } } }
        val toggle = compose.onNode(hasText("צבעים דינמיים מהטפט") and hasClickAction())
        toggle.performScrollTo().assertIsOff()
        screenshot("design303-municipal-settings.png")
        if (Build.VERSION.SDK_INT >= 31) {
            toggle.assertIsEnabled().performClick().assertIsOn()
            compose.runOnIdle { assertTrue(DynamicPaletteStore(context).enabled()) }
            screenshot("design303-dynamic-settings.png")
            for (candidate in listOf(dynamicLightColorScheme(context), dynamicDarkColorScheme(context))) {
                val safe = accessibleDynamicScheme(candidate, AppLightColors)
                assertTrue(dynamicTextContrastPasses(safe))
            }
            val unreadable = AppLightColors.copy(onPrimary = AppLightColors.primary)
            assertEquals(AppLightColors, accessibleDynamicScheme(unreadable, AppLightColors))
            toggle.performClick().assertIsOff()
        } else {
            toggle.assertIsNotEnabled()
            compose.onNodeWithText("צבעים דינמיים דורשים Android 12 ומעלה. פלטת ירושלים נשארת פעילה.").assertExists()
        }
    }
    @Test fun firstRunHasNoDynamicPaletteControl() {
        compose.setContent { AppTheme { SettingsScreen(null, { _, _, _ -> }, {}, basicSetup = true) } }
        compose.onNodeWithText("צבעים דינמיים מהטפט").assertDoesNotExist()
        assertFalse(DynamicPaletteStore(context).enabled())
    }
    @Test fun systemBackReturnsSettingsToHomeWithoutExiting() {
        compose.setContent { AppTheme {
            AppShell(authenticate = { _, _, action -> action() }, requestCamera = {}, readSettings = { null }, saveSettings = {})
        } }
        compose.onNodeWithText("הגדרות").performClick()
        compose.onNodeWithText("הגדרות פרטיות").assertExists()
        Espresso.pressBack()
        compose.onNodeWithText("צלם תמונה").assertExists()
        compose.onNodeWithText("הגדרות פרטיות").assertDoesNotExist()
        screenshot("design303-back-home.png")
    }
}
