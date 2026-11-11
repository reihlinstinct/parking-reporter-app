package il.sidewalks.reporter

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import il.sidewalks.reporter.ui.AppShell
import il.sidewalks.reporter.ui.SettingsScreen
import il.sidewalks.reporter.ui.theme.AppTheme
import il.sidewalks.reporter.i18n.AppCatalog
import il.sidewalks.reporter.i18n.UiPrefsLanguageStore
import il.sidewalks.reporter.security.ReporterSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Issue #295 regression tests:
 *  - a lock-less device greys the save button with a visible, screen-reader
 *    announced reason instead of swallowing taps (the tester's actual bug);
 *  - the button stays greyed until every required reporter field is filled and
 *    valid, and says next to the button exactly what is missing;
 *  - the passport field says inline why it blocks saving;
 *  - field errors are announced to screen readers (live region). */
class SettingsSaveGateTest {
    @get:Rule val compose = createComposeRule()

    private fun pinHebrew() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .let { UiPrefsLanguageStore.of(it).set(AppCatalog.defaultCode(it)) }
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, name).outputStream().use {
            compose.onNode(isRoot()).captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** Fill every required reporter field with valid synthetic values.
     *  [includeId] is false in passport mode, where the ID field is not shown. */
    private fun fillRequiredFields(includeId: Boolean = true) {
        compose.onNodeWithText("שם פרטי").performScrollTo().performTextInput("בדיקה")
        compose.onNodeWithText("שם משפחה").performScrollTo().performTextInput("מדווח")
        if (includeId) compose.onNodeWithText("מספר זהות").performScrollTo().performTextReplacement("000000018")
        compose.onNodeWithText("טלפון").performScrollTo().performTextReplacement("050-123-4567")
        compose.onNodeWithText("אימייל").performScrollTo().performTextReplacement("synthetic@example.com")
    }

    @Test fun noDeviceCredentialGreysSaveAndSaysWhy() {
        pinHebrew()
        var saveCalls = 0
        compose.setContent { AppTheme {
            SettingsScreen(null, save = { _, _, _ -> saveCalls++ }, back = {}, basicSetup = true, noDeviceCredential = true)
        } }
        // Even with every required field valid, no lockscreen means no save.
        fillRequiredFields()
        compose.onNodeWithText("הגדר נעילת מסך").performScrollTo()
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        compose.onNodeWithText("שמור והמשך").performScrollTo().assertIsNotEnabled()
        screenshot("settings-save-no-device-lock.png")
        compose.runOnIdle { assertEquals(0, saveCalls) }
    }

    @Test fun missingRequiredFieldsAreListedNextToTheButton() {
        pinHebrew()
        compose.setContent { AppTheme {
            SettingsScreen(null, save = { _, _, _ -> }, back = {}, basicSetup = true)
        } }
        compose.onNodeWithText("שם פרטי").performTextInput("בדיקה")
        compose.onNode(hasText("חסרים או לא תקינים", substring = true)).performScrollTo()
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        compose.onNodeWithText("שמור והמשך").performScrollTo().assertIsNotEnabled()
        screenshot("settings-save-missing-fields.png")
        // Completing everything removes the reason and enables the button.
        fillRequiredFields()
        compose.onNode(hasText("חסרים או לא תקינים", substring = true)).assertDoesNotExist()
        compose.onNodeWithText("שמור והמשך").performScrollTo().assertIsEnabled()
    }

    @Test fun passportModeShowsInlineErrorAndEmptyHint() {
        pinHebrew()
        compose.setContent { AppTheme {
            SettingsScreen(null, save = { _, _, _ -> }, back = {}, basicSetup = true)
        } }
        // The passport-mode checkbox is the only toggleable on the basic-setup screen.
        compose.onNode(isToggleable()).performScrollTo().performClick()
        // Empty passport in passport mode: an explicit hint on the field itself.
        compose.onNode(hasText("הזן מספר דרכון", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("שמור והמשך").performScrollTo().assertIsNotEnabled()
        // Too-short passport: an inline error on the field, button still greyed.
        compose.onNodeWithText("מספר דרכון").performScrollTo().performTextInput("ab")
        compose.onNodeWithText("מספר הדרכון אינו תקין. בדוק את המספר.").performScrollTo()
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        compose.onNodeWithText("שמור והמשך").performScrollTo().assertIsNotEnabled()
        screenshot("settings-passport-invalid.png")
        // A valid passport plus the other required fields re-enables save.
        compose.onNodeWithText("מספר דרכון").performScrollTo().performTextReplacement("A1234567")
        fillRequiredFields(includeId = false)
        compose.onNodeWithText("שמור והמשך").performScrollTo().assertIsEnabled()
    }

    @Test fun existingFieldErrorsAreAnnouncedToScreenReaders() {
        pinHebrew()
        compose.setContent { AppTheme {
            SettingsScreen(null, save = { _, _, _ -> }, back = {}, basicSetup = true)
        } }
        compose.onNodeWithText("אימייל").performScrollTo().performTextInput("bad@@example.com")
        compose.onNodeWithText("כתובת האימייל אינה תקינה. בדוק את הכתובת.").performScrollTo()
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText("מספר זהות").performScrollTo().performTextReplacement("000000019")
        compose.onNodeWithText("מספר תעודת הזהות אינו תקין. בדוק את הספרות וספרת הביקורת.").performScrollTo()
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    /** The whole onboarding flow through the real AppShell wiring on a lock-less
     *  device: the unlock gate is unreachable, so the button greys with the note
     *  and neither authenticate() nor a save can run - the reporter's exact bug. */
    @Test fun onboardingOnALocklessDeviceCannotSilentlySwallowSave() {
        pinHebrew()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(il.sidewalks.reporter.ui.FirstRunStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().remove(il.sidewalks.reporter.ui.FirstRunStore.KEY_FIRST_RUN_SEEN).commit()
        var authentications = 0
        var saved: ReporterSettings? = null
        compose.setContent { AppTheme {
            AppShell(
                authenticate = { _, _, action -> authentications++; action() },
                hasDeviceCredential = { false },
                requestCamera = {},
                readSettings = { null },
                saveSettings = { saved = it },
                showFirstRun = true,
                onFirstRunDone = {},
            )
        } }
        compose.onNodeWithText("מתחילים").performClick()
        fillRequiredFields()
        compose.onNodeWithText("הגדר נעילת מסך").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("שמור והמשך").performScrollTo().assertIsNotEnabled()
        screenshot("onboarding-no-device-lock.png")
        compose.runOnIdle {
            assertEquals(0, authentications)
            assertNull(saved)
        }
        context.getSharedPreferences(il.sidewalks.reporter.ui.FirstRunStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().remove(il.sidewalks.reporter.ui.FirstRunStore.KEY_FIRST_RUN_SEEN).commit()
    }
}
