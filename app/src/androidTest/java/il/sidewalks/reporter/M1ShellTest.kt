package il.sidewalks.reporter

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import il.sidewalks.reporter.core.Evidence
import il.sidewalks.reporter.core.ReviewDraft
import il.sidewalks.reporter.ledger.ReportDatabase
import il.sidewalks.reporter.ledger.ReportRecord
import il.sidewalks.reporter.security.ReporterSettings
import il.sidewalks.reporter.security.SecureSettings
import il.sidewalks.reporter.ui.ReviewScreen
import il.sidewalks.reporter.ui.SettingsScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class M1ShellTest {
    @get:Rule val compose = createComposeRule()
    private fun draft() = ReviewDraft(Evidence("synthetic", "a".repeat(64), "2026-01-01T12:00:00Z", 31.0, 35.0),
        "1234567", "רחוב בדיקה 1", "sidewalk_parking", "רכב חונה על המדרכה", emptySet())
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun reviewApprovalThenEditClears() {
        var saved: ReviewDraft? = null
        compose.setContent { MaterialTheme { ReviewScreen(draft(), null, { saved = it }, {}) } }
        screenshot("m1-review-synthetic.png")
        compose.onNodeWithText("אשר את התוכן לבדיקה").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(saved?.isApproved() == true) }
        compose.onNodeWithText("לוחית ללא מקפים").performScrollTo().performTextReplacement("7654321")
        compose.onNodeWithText("שמור טיוטה").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(saved?.isApproved() == true) }
    }
    @Test fun settingsContainReporterOnlyAndClearAfterSave() {
        var saved: ReporterSettings? = null
        compose.setContent { MaterialTheme { SettingsScreen(null, { value, clear -> saved = value; clear() }, {}) } }
        screenshot("m1-settings-synthetic.png")
        compose.onNodeWithText("שם פרטי").performTextInput("בדיקה")
        compose.onNodeWithText("שם משפחה").performTextInput("מדווח")
        compose.onNodeWithText("מפתח מנוי").assertDoesNotExist()
        compose.onNodeWithText("אמת זהות ושמור מוצפן").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("בדיקה", saved?.firstName); assertEquals("מדווח", saved?.lastName) }
        compose.onNodeWithText("בדיקה").assertDoesNotExist()
    }
    @Test fun duplicateAndStaleRevisionBlockedAcrossReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "synthetic-ledger-test.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, ReportDatabase::class.java, name).build()
        val record = ReportRecord("synthetic", "a".repeat(64), "originals/synthetic.jpg", 0, "{}", null)
        db.reports().reserve(record)
        var duplicateRejected = false
        try { db.reports().reserve(record.copy(stableId = "other")) } catch (_: Exception) { duplicateRejected = true }
        assertTrue(duplicateRejected)
        assertEquals(1, db.reports().store("synthetic", 0, "synthetic-draft", "synthetic-digest"))
        assertEquals(0, db.reports().store("synthetic", 0, "stale", null))
        db.close()
        db = Room.databaseBuilder(context, ReportDatabase::class.java, name).build()
        assertEquals("synthetic-digest", db.reports().find("synthetic")?.reviewedDigest)
        assertEquals(1, db.reports().store("synthetic", 1, "edit", null))
        assertNull(db.reports().find("synthetic")?.reviewedDigest)
        db.close(); context.deleteDatabase(name); Unit
    }
    @Test fun settingsWithoutRecentAuthenticationNeverStorePlaintext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.noBackupFilesDir, "reporter-settings.enc")
        file.delete()
        var failed = false
        try { SecureSettings(context).save(ReporterSettings("synthetic-first", "synthetic-last", "0000000", "000000000", "synthetic@example.invalid")) }
        catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertFalse(file.exists())
    }
    @Test fun originalEvidenceHashAndMissingMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "synthetic-evidence.jpg")
        file.writeText("synthetic original, not a JPEG")
        val reader = il.sidewalks.reporter.evidence.EvidenceReader()
        val a = reader.read(file, "synthetic")
        val b = reader.read(file, "synthetic")
        assertEquals(a, b); assertNull(a.capturedAtIso); assertNull(a.latitude)
        file.appendText("changed")
        assertNotEquals(a.originalSha256, reader.read(file, "synthetic").originalSha256)
        file.delete()
    }
}
