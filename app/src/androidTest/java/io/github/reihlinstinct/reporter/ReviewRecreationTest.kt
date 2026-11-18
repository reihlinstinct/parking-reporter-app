package io.github.reihlinstinct.reporter

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.core.Evidence
import io.github.reihlinstinct.reporter.core.ReviewDraft
import io.github.reihlinstinct.reporter.i18n.AppCatalog
import io.github.reihlinstinct.reporter.i18n.UiPrefsLanguageStore
import io.github.reihlinstinct.reporter.ledger.DraftCodec
import io.github.reihlinstinct.reporter.ledger.ReportDatabase
import io.github.reihlinstinct.reporter.ledger.ReportRecord
import io.github.reihlinstinct.reporter.ui.FirstRunStore
import io.github.reihlinstinct.reporter.ui.ReviewRotationState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real shell/activity disposal and recreation, not a simulated holder round-trip (#419). */
class ReviewRecreationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun recreatePreservesUnsavedReviewOriginalAndRevisionAndConsumesHolder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "recreation-synthetic"
        val db = Room.databaseBuilder(context, ReportDatabase::class.java, "report-ledger.db").build()
        val original = File(context.noBackupFilesDir, "originals/$id.jpg")
        val initial = ReviewDraft(Evidence(id, "d".repeat(64), "2026-01-01T12:00:00+02:00", 31.77, 35.21),
            "", "רחוב בדיקה 1", "municipal:1280", "בקשת עמודים סינתטית", emptySet(), plateRequired = false)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            seedSyntheticCityTopics()
            UiPrefsLanguageStore.of(context).set(AppCatalog.defaultCode(context))
            FirstRunStore(context).markSeen()
            ReviewRotationState.clear()
            original.parentFile!!.mkdirs()
            val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            original.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            bitmap.recycle()
            val originalBytes = original.readBytes()
            runBlocking {
                db.openHelper.writableDatabase.execSQL("DELETE FROM reports WHERE stableId = ?", arrayOf(id))
                db.reports().reserve(ReportRecord(id, initial.evidence.originalSha256, "originals/$id.jpg", 7L,
                    DraftCodec.encode(initial), null))
            }
            // Enter through the same process-only handoff a previous shell uses. Subsequent recreation
            // must populate this handoff itself; the test never re-seeds it after launch.
            ReviewRotationState.holdShell(ReviewRotationState.Shell(initial, original, 7L))
            scenario = ActivityScenario.launch(Intent().setClassName(context.packageName, "il.sidewalks.reporter.MainActivity"))
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("צילום ראיה").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("ערוך").performClick()
            compose.onNodeWithText("רחוב ומספר בית שאישרת").performScrollTo().performTextReplacement("רחוב סינתטי 42")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithText("חזור").performScrollTo().performClick()
            compose.onNodeWithText("יש שינויים שלא נשמרו").assertIsDisplayed()
            assertEquals(7L, runBlocking { db.reports().find(id)!! }.revision)

            scenario.recreate()

            compose.waitUntil(15_000) { compose.onAllNodesWithText("יש שינויים שלא נשמרו").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("יש שינויים שלא נשמרו").assertIsDisplayed()
            compose.onNodeWithText("המשך לערוך").performClick()
            // The edit panel and unsaved field must return, not merely the persisted initial draft.
            compose.onNodeWithText("רחוב ומספר בית שאישרת").performScrollTo().assertTextContains("רחוב סינתטי 42")
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("צילום ראיה").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle {
                assertNull("shell handoff consumed", ReviewRotationState.takeShell())
                assertNull("screen handoff consumed", ReviewRotationState.take(initial))
            }
            assertArrayEquals(originalBytes, original.readBytes())
            compose.onNodeWithText("שמור טיוטה").performScrollTo().performClick()
            compose.waitUntil(15_000) { runBlocking { db.reports().find(id)!!.revision == 8L } }
            val feedback = io.github.reihlinstinct.reporter.i18n.Strings(AppCatalog.get(context), AppCatalog.defaultCode(context)).t("shell.status.draft_saved")
            compose.onNodeWithText(feedback).assertIsDisplayed()
            compose.waitForIdle()
            val saved = runBlocking { db.reports().find(id)!! }
            assertEquals("רחוב סינתטי 42", DraftCodec.decode(saved.draftJson).address)
            assertEquals("originals/$id.jpg", saved.originalRelativePath)
            assertArrayEquals(originalBytes, original.readBytes())
            compose.onRoot().captureToImage().asAndroidBitmap().let { image ->
                File(context.filesDir, "review-recreation-synthetic.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            // A successful optimistic write at revision 7 proves the shell did not reset it to 0.
            // No Send control is touched, and no municipal/profile credentials are installed here.
        } finally {
            scenario?.close()
            ReviewRotationState.clear()
            db.openHelper.writableDatabase.execSQL("DELETE FROM reports WHERE stableId = ?", arrayOf(id))
            db.close()
            original.delete()
        }
    }
}
