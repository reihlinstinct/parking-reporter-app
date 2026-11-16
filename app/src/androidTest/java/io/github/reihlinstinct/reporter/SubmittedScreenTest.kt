package io.github.reihlinstinct.reporter

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.reihlinstinct.reporter.ui.SubmittedScreen
import io.github.reihlinstinct.reporter.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SubmittedScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsSuccessTitleBodyAndBackButtonWithoutReceiptInRelease() {
        var done = 0
        compose.setContent { AppTheme { SubmittedScreen(receiptText = null, onDone = { done++ }) } }
        compose.onNodeWithText("הדיווח הוגש בהצלחה").assertIsDisplayed()
        compose.onNodeWithText("העירייה קיבלה את הדיווח. תודה שעזרת לשפר את העיר.").assertIsDisplayed()
        compose.onNodeWithTag("submitted-check").assertIsDisplayed()
        compose.onNodeWithTag("submission-note").assertDoesNotExist()
        compose.onNodeWithText("חזרה לדיווחים").performClick()
        compose.runOnIdle { assertEquals(1, done) }
    }

    @Test fun showsTheReceiptLineSmallAtTheBottomOnlyWhenGiven() {
        compose.setContent { AppTheme { SubmittedScreen(receiptText = "נשלח לעירייה. מספר קריאה: synthetic-123", onDone = {}) } }
        compose.onNodeWithText("נשלח לעירייה. מספר קריאה: synthetic-123").assertExists()
        compose.onNodeWithTag("submission-note").assertExists()
    }
}
