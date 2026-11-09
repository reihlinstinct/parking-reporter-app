package il.sidewalks.reporter

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.fail

/** Field values the flow driver types into the review screen. */
internal data class FlowFields(
    val text: String,
    val plate: String?,
    val address: String,
    val capturedAtIso: String,
    val latitude: String,
    val longitude: String,
    val categoryLabel: String,
)

/**
 * Drives the real AppShell review -> approval -> send flow. Shared by the
 * synthetic stub E2E (FullFlowE2eTest) and the opt-in live submission E2E
 * (live/LiveEmulatorSubmissionTest): ONE flow code path for both, so a UI
 * change breaks the stub first, never only the live run.
 */
internal class FlowDriver(private val compose: ComposeTestRule) {

    fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** Shared-photo entry through a locally approved draft. */
    fun driveToApproved(fields: FlowFields) {
        // A photo with embedded GPS (every real live photo) first raises the
        // location prompt; a synthetic photo goes straight to the review.
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("בדיקת הדיווח").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("נמצא מיקום בתמונה").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("נמצא מיקום בתמונה").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("השתמש במיקום").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("בדיקת הדיווח").fetchSemanticsNodes().isNotEmpty()
            }
        }
        compose.onNodeWithText("נדרש תיקון קטגוריה ▾").performScrollTo().performClick()
        compose.onNodeWithText(fields.categoryLabel).performClick()
        compose.onNodeWithText("ערוך").performScrollTo().performClick()
        if (fields.plate != null) {
            compose.onNodeWithText("לוחית ללא מקפים").performScrollTo().performTextReplacement(fields.plate)
        }
        compose.onNodeWithText("רחוב ומספר בית שאישרת").performScrollTo().performTextReplacement(fields.address)
        compose.onNodeWithText("הטקסט המדויק לבדיקה").performScrollTo().performTextReplacement(fields.text)
        compose.onNodeWithText("זמן מאושר עם אזור זמן (ISO)").performScrollTo().performTextReplacement(fields.capturedAtIso)
        compose.onNodeWithText("קו רוחב").performScrollTo().performTextReplacement(fields.latitude)
        compose.onNodeWithText("קו אורך").performScrollTo().performTextReplacement(fields.longitude)
        compose.onNodeWithText("אשר זמן, מיקום וכתובת שבדקת").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            try { compose.onNodeWithText("אשר את התוכן לבדיקה").assertIsEnabled(); true }
            catch (e: AssertionError) { false }
        }
        compose.onNodeWithText("אשר את התוכן לבדיקה").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("התוכן אושר מקומית, לא נשלח").fetchSemanticsNodes().isNotEmpty()
        }
    }

    fun tapSendAndAwaitCaseNumber() {
        // The send gate compares the shown draft to the last SAVED one and
        // the post-approval save is asynchronous; retry the tap if the gate
        // answers "not saved" before the save lands.
        var attempt = 0
        while (true) {
            attempt += 1
            compose.onNodeWithText("שלח לעירייה").performScrollTo().performClick()
            try {
                compose.waitUntil(45_000) {
                    compose.onAllNodesWithText("נשלח לעירייה. מספר קריאה", substring = true)
                        .fetchSemanticsNodes().isNotEmpty() ||
                        compose.onAllNodesWithText("הטיוטה לא נשמרה", substring = true)
                            .fetchSemanticsNodes().isNotEmpty()
                }
            } catch (e: ComposeTimeoutException) {
                fail("no submission outcome shown")
            }
            if (compose.onAllNodesWithText("נשלח לעירייה. מספר קריאה", substring = true)
                .fetchSemanticsNodes().isNotEmpty()) return
            if (attempt >= 3) fail("draft-not-saved persisted across retries")
            Thread.sleep(500)
        }
    }

    /** Full text of the first node containing [substring], or null. */
    fun nodeTextContaining(substring: String): String? {
        val node = compose.onAllNodesWithText(substring, substring = true)
            .fetchSemanticsNodes().firstOrNull() ?: return null
        return node.config.getOrNull(SemanticsProperties.Text)
            ?.joinToString(separator = "") { it.text }
    }
}
