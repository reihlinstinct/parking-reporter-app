package il.sidewalks.reporter

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import il.sidewalks.reporter.i18n.UiPrefsLanguageStore
import il.sidewalks.reporter.ui.AppShell
import il.sidewalks.reporter.ui.FirstRunStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Issue #187: the one-time first-run welcome, through the real AppShell wiring. */
class FirstRunScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun clearFirstRunFlag() {
        context.getSharedPreferences(FirstRunStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().remove(FirstRunStore.KEY_FIRST_RUN_SEEN).commit()
    }

    @Before fun pinHebrewAndClearFlag() {
        UiPrefsLanguageStore.of(context).set("he")
        clearFirstRunFlag()
    }

    @After fun restore() {
        context.getSharedPreferences(UiPrefsLanguageStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().putString(UiPrefsLanguageStore.KEY_LANGUAGE, "he").commit()
        clearFirstRunFlag()
    }

    @Test fun firstRunShowsWelcomeAndStartEntersTheShell() {
        var done = false
        compose.setContent { MaterialTheme {
            AppShell(
                authenticate = { _, _, action -> action() },
                requestCamera = {},
                readSettings = { null },
                saveSettings = {},
                showFirstRun = !done,
                onFirstRunDone = { done = true },
            )
        } }
        compose.onNodeWithText("ברוכים הבאים למדרכות פנויות").assertExists()
        compose.onNodeWithText("מתחילים").assertExists()
        // The plain shell is not underneath: no navigation bar yet.
        compose.onNodeWithText("הגדרות").assertDoesNotExist()
        compose.onNodeWithText("מתחילים").performClick()
        compose.runOnIdle { assertEquals(true, done) }
        compose.setContent { MaterialTheme {
            AppShell(
                authenticate = { _, _, action -> action() },
                requestCamera = {},
                readSettings = { null },
                saveSettings = {},
                showFirstRun = !done,
                onFirstRunDone = { done = true },
            )
        } }
        compose.onNodeWithText("הגדרות").assertExists()
        compose.onNodeWithText("ברוכים הבאים למדרכות פנויות").assertDoesNotExist()
    }

    @Test fun storePersistsTheSeenFlag() {
        val store = FirstRunStore(context)
        assertFalse(store.seen())
        store.markSeen()
        assertEquals(true, store.seen())
        assertEquals(true, FirstRunStore(context).seen())
    }
}
