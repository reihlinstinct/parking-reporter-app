package il.sidewalks.reporter

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import il.sidewalks.reporter.ui.SettingsScreen
import il.sidewalks.reporter.ui.theme.AppTheme
import il.sidewalks.reporter.i18n.UiPrefsLanguageStore
import il.sidewalks.reporter.security.ReporterSettings
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PassportLocalProfileTest {
    @get:Rule val compose = createComposeRule()
    @Test fun passportSavesLocalProfileWithoutAnIdAndNoInventedIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        UiPrefsLanguageStore.of(context).set("he")
        var saved: ReporterSettings? = null
        compose.setContent { AppTheme { SettingsScreen(null, save = { value, _, clear -> saved = value; clear() }, back = {}, basicSetup = true) } }
        compose.onNodeWithText("ללא תעודת זהות ישראלית (דרכון, שמירה מקומית בלבד)").performScrollTo()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("מספר דרכון (מקומי בלבד)").performScrollTo().performTextInput("SYNTHETIC-PASSPORT")
        compose.onNodeWithText("ממשק 106 הנתמך באפליקציה", substring = true).performScrollTo().assertExists()
        File(context.filesDir, "passport-local-profile-synthetic.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("שמור והמשך").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(saved!!.passportLocalOnly); assertEquals("SYNTHETIC-PASSPORT", saved?.passportNumber); assertEquals("", saved?.idNumber) }
    }
}
