package io.github.reihlinstinct.reporter

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.core.Evidence
import io.github.reihlinstinct.reporter.core.ReviewDraft
import io.github.reihlinstinct.reporter.ui.ReviewScreen
import io.github.reihlinstinct.reporter.ui.theme.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReviewUnsavedBackTest {
    @get:Rule val compose = createComposeRule()
    private fun draft() = ReviewDraft(Evidence("unsaved-synthetic", "a".repeat(64),
        "2026-01-01T12:00:00+02:00", 31.77, 35.21), "1234567", "רחוב בדיקה 1",
        "sidewalk_parking", "בדיקה", emptySet())

    @Test fun explicitBackOffersKeepOrDiscardWithoutDeletingSavedDraft() {
        val initial = draft()
        var saved = initial
        var exits = 0
        compose.setContent { AppTheme { ReviewScreen(initial, null, { saved = it }, { exits++ }) } }
        compose.onNodeWithText("ערוך").performClick()
        compose.onNodeWithText("מספר לוחית (ספרות בלבד)").performScrollTo().performTextReplacement("7654321")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithText("חזור").performScrollTo().performClick()
        capture("review-unsaved-before-visible-assert.png")
        compose.onNodeWithText("יש שינויים שלא נשמרו").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, exits); assertEquals(initial, saved) }
        capture("review-unsaved-confirm-synthetic.png")
        compose.onNodeWithText("המשך לערוך").performClick()
        compose.onNodeWithText("7654321").performScrollTo().assertExists()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithText("חזור").performScrollTo().performClick()
        capture("review-unsaved-second-back-before-discard.png")
        compose.onNodeWithText("יש שינויים שלא נשמרו").assertIsDisplayed()
        compose.onNodeWithText("בטל שינויים וחזור").performClick()
        compose.runOnIdle { assertEquals(1, exits); assertEquals(initial, saved) }
    }

    @Test fun systemBackProtectsEditsAndCleanSavedDraftCanReopen() {
        var saved by mutableStateOf(draft())
        var visible by mutableStateOf(true)
        compose.setContent { AppTheme {
            if (visible) ReviewScreen(saved, null, { saved = it }, { visible = false })
            else androidx.compose.material3.TextButton(onClick = { visible = true }) {
                androidx.compose.material3.Text("reopen-synthetic")
            }
        } }
        compose.onNodeWithText("ערוך").performClick()
        compose.onNodeWithText("מספר לוחית (ספרות בלבד)").performScrollTo().performTextReplacement("7654321")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("יש שינויים שלא נשמרו").assertIsDisplayed()
        compose.onNodeWithText("המשך לערוך").performClick()
        compose.onNodeWithText("שמור טיוטה").performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("7654321", saved.plate) }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithText("חזור").performScrollTo().performClick()
        compose.onNodeWithText("יש שינויים שלא נשמרו").assertDoesNotExist()
        compose.onNodeWithText("reopen-synthetic").performClick()
        compose.onNodeWithText("לוחית:", substring = true).assertExists()
        compose.onNodeWithText("ערוך").performClick()
        compose.onNodeWithText("מספר לוחית (ספרות בלבד)").performScrollTo().assertTextContains("7654321")
    }

    @Test fun cleanExifTimestampNormalizationDoesNotInventUnsavedTimeEdits() {
        var exits = 0
        compose.setContent { AppTheme { ReviewScreen(draft().copy(confirmedTime = "2026-02-02T12:00:00+02:00"), null, {}, { exits++ }) } }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithText("חזור").performScrollTo().performClick()
        compose.onNodeWithText("יש שינויים שלא נשמרו").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
