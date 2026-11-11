package il.sidewalks.reporter

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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
                    onSubmit = { submitted++ }, topicSnapshot = syntheticCityTopics(), topicsAvailable = true)
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
                    onSave = {}, onBack = {}, onSubmit = {}, topicSnapshot = syntheticCityTopics(), topicsAvailable = true)
            }
        }
        compose.onNodeWithText("שלח לעירייה").assertIsNotEnabled()
    }

    @Test fun saveFoldResolvesTimeConfirmation() {
        // Tab review (#266): the fold settles warnings whose underlying
        // fields check out - readiness is field-derived, not latch-derived.
        var saved: ReviewDraft? = null
        compose.setContent {
            MaterialTheme {
                ReviewScreen(readyDraft().copy(unresolved = setOf("time_needs_confirmation")), null,
                    onSave = { saved = it }, onBack = {}, onSubmit = {}, topicSnapshot = syntheticCityTopics(), topicsAvailable = true)
            }
        }
        compose.onNodeWithText("שמור טיוטה").performClick()
        org.junit.Assert.assertTrue(saved?.unresolved?.isEmpty() == true)
        compose.onNodeWithText("שלח לעירייה").assertIsEnabled()
    }

    @Test fun saveKeepsUnsettledAddressWarnings() {
        // Tab review (#266): the fold confirms time/location only - an address
        // warning survives save until the address field itself is edited.
        // Warnings render as one combined "דרוש בירור: ..." line in view mode.
        compose.setContent {
            MaterialTheme {
                ReviewScreen(readyDraft().copy(unresolved = setOf("suggested_address_needs_confirmation")), null,
                    onSave = {}, onBack = {}, onSubmit = {}, topicSnapshot = syntheticCityTopics(), topicsAvailable = true)
            }
        }
        compose.onNodeWithText("שמור טיוטה").performClick()
        compose.onNodeWithText("כתובת דורשת אישור", substring = true).assertExists()
        compose.onNodeWithText("שלח לעירייה").assertIsNotEnabled()
    }

    @Test fun addressFieldEditConfirmsTheAddress() {
        // The field's label is "רחוב ומספר בית שאישרת": editing it is the
        // explicit confirmation that clears the warning and enables send.
        // The field exists only in edit mode.
        compose.setContent {
            MaterialTheme {
                ReviewScreen(readyDraft().copy(unresolved = setOf("suggested_address_needs_confirmation")), null,
                    onSave = {}, onBack = {}, onSubmit = {}, topicSnapshot = syntheticCityTopics(), topicsAvailable = true)
            }
        }
        compose.onNodeWithText("ערוך").performClick()
        compose.onNodeWithText("רחוב ומספר בית שאישרת").performTextReplacement("רחוב 14")
        compose.onNodeWithText("שמור טיוטה").performClick()
        compose.onNodeWithText("שלח לעירייה").assertIsEnabled()
    }

    @Test fun addressEditThatReturnsToBaselineDoesNotConfirm() {
        // Tab review (#266): one keystroke typed and deleted must not confirm -
        // returning to the suggested value re-flags the warning.
        compose.setContent {
            MaterialTheme {
                ReviewScreen(readyDraft().copy(unresolved = setOf("suggested_address_needs_confirmation")), null,
                    onSave = {}, onBack = {}, onSubmit = {}, topicSnapshot = syntheticCityTopics(), topicsAvailable = true)
            }
        }
        compose.onNodeWithText("ערוך").performClick()
        compose.onNodeWithText("רחוב ומספר בית שאישרת").performTextReplacement("רחוב 129")
        compose.onNodeWithText("רחוב ומספר בית שאישרת").performTextReplacement("רחוב 12")
        compose.onNodeWithText("שמור טיוטה").performClick()
        compose.onNodeWithText("סיום עריכה").performClick()
        compose.onNodeWithText("כתובת דורשת אישור", substring = true).assertExists()
        compose.onNodeWithText("שלח לעירייה").assertIsNotEnabled()
    }

    @Test fun saveKeepsUnsettledLocationWarnings() {
        // Tab review (#266): folding must not silently clear a warning it
        // cannot settle - without coordinates the draft stays not-ready and
        // keeps its explanation, and send stays disabled.
        compose.setContent {
            MaterialTheme {
                ReviewScreen(readyDraft().copy(confirmedLatitude = null, confirmedLongitude = null,
                    unresolved = setOf("missing_gps")), null, onSave = {}, onBack = {}, onSubmit = {}, topicSnapshot = syntheticCityTopics(), topicsAvailable = true)
            }
        }
        compose.onNodeWithText("שמור טיוטה").performClick()
        compose.onNodeWithText("GPS חסר", substring = true).assertExists()
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
                    onSubmit = { sent = it }, topicSnapshot = syntheticCityTopics(), topicsAvailable = true)
            }
        }
        compose.onNodeWithText("שלח לעירייה").performClick()
        org.junit.Assert.assertTrue(sent == shown)
    }

    @Test fun noGateKeepsTheDisabledPlaceholder() {
        compose.setContent {
            MaterialTheme {
                ReviewScreen(readyDraft(), null, onSave = {}, onBack = {}, topicSnapshot = syntheticCityTopics(), topicsAvailable = true)
            }
        }
        compose.onNodeWithText("שליחה אינה זמינה בגרסת הבדיקה").assertExists()
    }
}
