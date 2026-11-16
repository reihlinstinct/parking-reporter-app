package io.github.reihlinstinct.reporter

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.i18n.AppCatalog
import io.github.reihlinstinct.reporter.i18n.UiPrefsLanguageStore
import io.github.reihlinstinct.reporter.ui.ReviewRotationState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReviewOcrRecreationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun realUserClearSurvivesRecreateButExplicitOcrStillWorks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ReviewRotationState.clear()
        ReviewOcrTestActivity.recognitions.set(0)
        val prefs = context.getSharedPreferences(UiPrefsLanguageStore.PREFS_NAME, 0)
        val previous = prefs.all
        try {
            UiPrefsLanguageStore.of(context).set(AppCatalog.defaultCode(context))
            ActivityScenario.launch<ReviewOcrTestActivity>(Intent(context, ReviewOcrTestActivity::class.java)).use { scenario ->
                compose.onNodeWithText("ערוך").performClick()
                compose.onNodeWithText("מספר לוחית (ספרות בלבד)").performScrollTo().performTextClearance()
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                compose.waitForIdle()
                assertEquals(0, ReviewOcrTestActivity.recognitions.get())
                // No Held seed: only ReviewScreen's real onDispose can retain the user edit count.
                scenario.recreate()
                compose.onNodeWithText("מספר לוחית (ספרות בלבד)").performScrollTo().assertExists()
                compose.waitForIdle()
                assertEquals("automatic OCR must not undo the clear", 0, ReviewOcrTestActivity.recognitions.get())
                compose.onNodeWithTag("review-plate").assertDoesNotExist()
                compose.onNodeWithText("הצע לוחית מתוך הצילום").performScrollTo().performClick()
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithText("מספר לוחית (ספרות בלבד)").assertTextContains("7654321") }.isSuccess
                }
                compose.onNodeWithText("מספר לוחית (ספרות בלבד)").performScrollTo().assertTextContains("7654321")
                assertEquals("explicit OCR remains available", 1, ReviewOcrTestActivity.recognitions.get())
            }
        } finally {
            ReviewRotationState.clear()
            prefs.edit().clear().apply {
                previous.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
            }.commit()
        }
    }
}
