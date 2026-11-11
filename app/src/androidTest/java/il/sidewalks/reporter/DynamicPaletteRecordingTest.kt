package il.sidewalks.reporter

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.dynamicLightColorScheme
import il.sidewalks.reporter.ui.*
import il.sidewalks.reporter.ui.theme.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Actual API36 wallpaper palette, normal font scale. No network or reports. */
class DynamicPaletteRecordingTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun language() {
        il.sidewalks.reporter.i18n.UiPrefsLanguageStore.of(context).set(il.sidewalks.reporter.i18n.AppCatalog.defaultCode(context))
    }
    private fun capture(name: String) {
        compose.waitForIdle(); Thread.sleep(1500)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun homeWithActualWallpaperPaletteOn() {
        compose.activity.runOnUiThread { compose.activity.enableEdgeToEdge() }
        DynamicPaletteStore(context).setEnabled(true)
        assertTrue(DynamicPaletteStore(context).enabled())
        val candidate = dynamicLightColorScheme(context)
        assertTrue(dynamicTextContrastPasses(candidate))
        assertNotEquals(AppLightColors.primary, candidate.primary)
        compose.setContent { AppTheme { NavyStatusBar {
            AppShell(authenticate = { _, _, action -> action() }, requestCamera = {}, readSettings = { null }, saveSettings = {})
        } } }
        capture("dynamic-on-home.png")
        compose.onNodeWithText("הגדרות").performClick()
        compose.onNodeWithText("צבעים דינמיים מהטפט").performScrollTo().assertIsOn()
        capture("dynamic-on-settings.png")
    }
    @Test fun pickerWithActualWallpaperPaletteOn() {
        DynamicPaletteStore(context).setEnabled(true)
        compose.setContent { AppTheme { CityTopicPicker(syntheticCityTopics().topics, null, emptyList(), emptyMap(), {}, {}) } }
        compose.onNodeWithText("חפש נושא, תיאור או מספר").performTextInput("1145")
        capture("dynamic-on-picker.png")
    }
}
