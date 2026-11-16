package io.github.reihlinstinct.reporter

import io.github.reihlinstinct.reporter.ui.theme.AppTheme
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.i18n.AppCatalog
import io.github.reihlinstinct.reporter.i18n.Bidi
import io.github.reihlinstinct.reporter.i18n.UiPrefsLanguageStore
import io.github.reihlinstinct.reporter.ui.AppShell
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Issue #115 PR-2a: the Settings language picker, through the real AppShell wiring. */
class LanguagePickerTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences(UiPrefsLanguageStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)

    @Before fun pinHebrew() {
        seedSyntheticCityTopics()
 UiPrefsLanguageStore.of(context).set(AppCatalog.defaultCode(context)) }

    @After fun restoreHebrew() { prefs.edit().putString(UiPrefsLanguageStore.KEY_LANGUAGE, AppCatalog.defaultCode(context)).commit() }

    @Test fun pickingALanguageAppliesLivePersistsAndNeverAuthenticatesOrTouchesSettings() {
        var authCalls = 0
        var settingsReads = 0
        var settingsWrites = 0
        val prompts = mutableListOf<Pair<String, String>>()
        compose.setContent { AppTheme {
            AppShell(
                authenticate = { title, subtitle, action -> authCalls++; prompts += title to subtitle; action() },
                requestCamera = {},
                readSettings = { settingsReads++; null },
                saveSettings = { settingsWrites++ },
            )
        } }
        compose.onNodeWithText("הגדרות").performClick()
        compose.runOnIdle {
            assertEquals(1, authCalls)
            assertEquals(1, settingsReads)
            // The unlock prompt is read from the catalog in the active language (he here).
            assertEquals(listOf("הגדרות פרטיות" to "אמת את נעילת המכשיר כדי לגשת לפרטים"), prompts)
        }
        compose.onNodeWithText("שם פרטי").performTextInput("בדיקה")

        compose.onNodeWithText("${Bidi.LRI}English${Bidi.PDI}").performScrollTo().performClick()
        // Live: the shell and the screen switch with no restart, and typed text stays.
        // The live relabel lands on a later frame on a loaded emulator; wait for it instead of asserting at once.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Settings").assertExists()
        compose.onNodeWithText("Privacy settings").performScrollTo().assertExists()
        compose.onNodeWithText("בדיקה").performScrollTo().assertExists()
        compose.onNodeWithText("${Bidi.LRI}English${Bidi.PDI}").performScrollTo().assertIsSelected()
        compose.runOnIdle {
            // The tapped option is English by its own name; its code is the catalog's.
            assertEquals(AppCatalog.get(context).languages.single { it.nativeName == "English" }.code, prefs.getString(UiPrefsLanguageStore.KEY_LANGUAGE, null))
            assertEquals(1, authCalls)
            assertEquals(1, settingsReads)
            assertEquals(0, settingsWrites)
        }

        compose.onNodeWithText("${Bidi.RLI}العربية${Bidi.PDI}").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("الإعدادات").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("الإعدادات").assertExists()
        compose.onNodeWithText("System default").assertDoesNotExist()
        compose.onNodeWithText("افتراضي النظام").performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(prefs.contains(UiPrefsLanguageStore.KEY_LANGUAGE))
            assertEquals(1, authCalls)
            assertEquals(0, settingsWrites)
        }
    }
}
