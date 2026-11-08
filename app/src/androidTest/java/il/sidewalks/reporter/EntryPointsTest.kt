package il.sidewalks.reporter

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import il.sidewalks.reporter.ledger.ReportDatabase
import il.sidewalks.reporter.ui.AppShell
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Instrumented coverage for the phase-1 entry points (issue #149). */
class EntryPointsTest {
    @get:Rule val compose = createComposeRule()

    /** The share test reserves a real ledger row; leave no draft behind. */
    private fun cleanLedgerAndOriginals() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, ReportDatabase::class.java, "report-ledger.db").build()
        try {
            val paths = runBlocking { db.reports().all() }.map { it.originalRelativePath }
            db.openHelper.writableDatabase.execSQL("DELETE FROM reports")
            paths.forEach { File(context.noBackupFilesDir, it).delete() }
        } finally {
            db.close()
        }
    }

    @Test fun deepLinkShareAndTileAreDeclaredInTheManifest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = context.packageManager
        val link = pm.resolveActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(EntryIntents.DEEP_LINK_NEW_REPORT)).setPackage(context.packageName), 0)
        assertEquals(MainActivity::class.java.name, link?.activityInfo?.name)
        val share = pm.resolveActivity(
            Intent(Intent.ACTION_SEND).setType("image/jpeg").setPackage(context.packageName), 0)
        assertEquals(MainActivity::class.java.name, share?.activityInfo?.name)
        val services = pm.getPackageInfo(context.packageName, PackageManager.GET_SERVICES).services.orEmpty()
        val tile = services.single { it.name == ReportTileService::class.java.name }
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.permission)
        assertTrue(tile.exported)
    }

    @Test fun sharedPhotoStartsReviewPrefilled() {
        cleanLedgerAndOriginals()
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val src = File(context.cacheDir, "shared-entry-synthetic.jpg")
            val bmp = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
            bmp.setPixel(1, 1, android.graphics.Color.BLUE)
            src.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            val uri = Uri.parse("content://il.sidewalks.reporter.testshare/shared-entry-synthetic.jpg")
            val signal = mutableStateOf(EntrySignal(1, EntryIntents.Request.SharedPhoto(uri.toString())))
            compose.setContent {
                MaterialTheme {
                    AppShell(authenticate = { it() }, requestCamera = { it() },
                        readSettings = { null }, saveSettings = {}, entry = signal)
                }
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("בדיקת הדיווח").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("בדיקת הדיווח").assertExists()
            src.delete()
        } finally {
            cleanLedgerAndOriginals()
        }
    }

    @Test fun deepLinkNewReportRequestsCamera() {
        var cameraRequested = false
        val signal = mutableStateOf(EntrySignal(1, EntryIntents.Request.NewReport))
        compose.setContent {
            MaterialTheme {
                AppShell(authenticate = { it() }, requestCamera = { cameraRequested = true },
                    readSettings = { null }, saveSettings = {}, entry = signal)
            }
        }
        compose.waitUntil(15_000) { cameraRequested }
        assertTrue(cameraRequested)
    }
}
