package il.sidewalks.reporter

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import il.sidewalks.reporter.core.Evidence
import il.sidewalks.reporter.core.ReviewDraft
import il.sidewalks.reporter.ui.ReviewScreen
import org.junit.Rule
import org.junit.Test

/** The send gate on the review screen: send is the single confirmation (owner 2026-11-10). */
class SubmissionGateTest {
    @get:Rule val compose = createComposeRule()

    private fun readyDraft(): ReviewDraft {
        val evidence = Evidence("stable-gate", "a".repeat(64), "2026-11-08T10:00:00+02:00", 31.7683, 35.2137)
        return ReviewDraft(evidence, "12345678", "רחוב 12", "sidewalk_parking",
            "רכב חנה על המדרכה", emptySet())
    }

    @Test fun sendEnabledForReadyDraft() {
        var submitted = 0
        compose.setContent {
            MaterialTheme {
                ReviewScreen(readyDraft(), null, onSave = {}, onBack = {},
                    onSubmit = { submitted++ })
            }
        }
        // The draft is complete, so send is live.
        compose.onNodeWithText("שלח לעירייה").assertIsEnabled()
        compose.onNodeWithText("שלח לעירייה").assertExists()
    }

    @Test fun sendStaysDisabledOnIncompleteDraft() {
        // Send is the single confirmation (owner instruction 2026-11-10): the
        // approval latch is gone, so a resolvable warning alone no longer
        // disables send - the send tap itself folds the checked time. Only a
        // structurally incomplete draft (here: no plate) keeps send disabled.
        compose.setContent {
            MaterialTheme {
                ReviewScreen(readyDraft().copy(plate = ""), null,
                    onSave = {}, onBack = {}, onSubmit = {})
            }
        }
        compose.onNodeWithText("שלח לעירייה").assertIsNotEnabled()
    }

    @Test fun sendButtonSubmitsTheShownDraft() {
        // The screen hands the draft it actually shows to the submit
        // callback - AppShell then refuses anything but the saved copy.
        val shown = readyDraft()
        var sent: ReviewDraft? = null
        compose.setContent {
            MaterialTheme {
                ReviewScreen(shown, null, onSave = {}, onBack = {},
                    onSubmit = { sent = it })
            }
        }
        compose.onNodeWithText("שלח לעירייה").performClick()
        org.junit.Assert.assertTrue(sent == shown)
    }

    @Test fun noGateKeepsTheDisabledPlaceholder() {
        compose.setContent {
            MaterialTheme {
                ReviewScreen(readyDraft(), null, onSave = {}, onBack = {})
            }
        }
        compose.onNodeWithText("שליחה אינה זמינה בגרסת הבדיקה").assertExists()
    }
}
