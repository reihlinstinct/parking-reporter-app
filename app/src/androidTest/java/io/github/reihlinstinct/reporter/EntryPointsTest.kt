package io.github.reihlinstinct.reporter

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.ledger.ReportDatabase
import io.github.reihlinstinct.reporter.ui.AppShell
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Instrumented coverage for the phase-1 entry points (issue #149). */
class EntryPointsTest {
    @get:Rule val compose = createComposeRule()

    // The share test looks for the Hebrew review title; the review screen follows the UI
    // language since #115 PR-2b, so pin he through the real "ui-prefs" store (as M1ShellTest).
    @Before fun pinHebrewUiLanguage() {
        seedSyntheticCityTopics()

        InstrumentationRegistry.getInstrumentation().targetContext.let { io.github.reihlinstinct.reporter.i18n.UiPrefsLanguageStore.of(it).set(io.github.reihlinstinct.reporter.i18n.AppCatalog.defaultCode(it)) }
    }

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
        assertEquals("il.sidewalks.reporter.MainActivity", link?.activityInfo?.name)
        val share = pm.resolveActivity(
            Intent(Intent.ACTION_SEND).setType("image/jpeg").setPackage(context.packageName), 0)
        assertEquals("il.sidewalks.reporter.MainActivity", share?.activityInfo?.name)
        val services = pm.getPackageInfo(context.packageName, PackageManager.GET_SERVICES).services.orEmpty()
        val tile = services.single { it.name == "il.sidewalks.reporter.ReportTileService" }
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.permission)
        assertTrue(tile.exported)
    }

    @Test fun sharedPhotoStartsReviewPrefilled() {
        cleanLedgerAndOriginals()
        try {
            // TestShareProvider (test APK, exported) generates and serves the JPEG.
            val uri = Uri.parse("content://io.github.reihlinstinct.reporter.testshare/shared-entry-synthetic.jpg")
            val signal = mutableStateOf(EntrySignal(1, EntryIntents.Request.SharedPhoto(uri.toString())))
            compose.setContent {
                MaterialTheme {
                    AppShell(authenticate = { _, _, action -> action() }, requestCamera = { it() },
                        readSettings = { null }, saveSettings = {}, entry = signal)
                }
            }
            try {
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithText("בדיקת הדיווח").fetchSemanticsNodes().isNotEmpty()
                }
            } catch (e: ComposeTimeoutException) {
                // Dump the visible tree so CI logcat shows which screen and
                // status the import actually reached (catches are silent).
                compose.onRoot().printToLog("EntryPointsTest")
                throw e
            }
            compose.onNodeWithText("בדיקת הדיווח").assertExists()
        } finally {
            cleanLedgerAndOriginals()
        }
    }

    @Test fun firstPickerOpenBootstrapsThroughAppShellThenReusesCachedCityList() {
        cleanLedgerAndOriginals()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.noBackupFilesDir, "city-topics/catalog.json").delete()
        io.github.reihlinstinct.reporter.security.SettingsProfileStore(context).save(emptyList())
        val topics = syntheticCityTopics().topics
        var authentications = 0
        var logins = 0
        var catalogReads = 0
        val settings = io.github.reihlinstinct.reporter.security.ReporterSettings(
            firstName = "בדיקה", lastName = "סינתטי", phone = "0501234567", idNumber = "000000018", email = "synthetic@example.com",
            municipalSubscriptionKey = "synthetic-key", municipalUsername = "synthetic-user", municipalPassword = "synthetic-password")
        val signal = mutableStateOf(EntrySignal(1, EntryIntents.Request.SharedPhoto(
            "content://io.github.reihlinstinct.reporter.testshare/topic-bootstrap.jpg")))
        try {
            compose.setContent { io.github.reihlinstinct.reporter.ui.theme.AppTheme {
                AppShell(authenticate = { _, _, action -> authentications++; action() }, hasDeviceCredential = { true },
                    requestCamera = {}, readSettings = { settings }, saveSettings = {}, entry = signal,
                    municipalClientFactory = { object : io.github.reihlinstinct.reporter.municipal.MunicipalClient {
                        override fun login(): String { logins++; return "synthetic-token" }
                        override fun subjectCategories(token: String): List<io.github.reihlinstinct.reporter.core.MunicipalSubject> {
                            assertEquals("synthetic-token", token); catalogReads++; return topics
                        }
                        override fun upsertContact(token: String, contact: LinkedHashMap<String, Any?>): Map<String, Any?> = error("no write")
                        override fun createCase(token: String, contact: LinkedHashMap<String, Any?>, report: LinkedHashMap<String, Any?>): Map<String, Any?> = error("no case")
                        override fun attachPhoto(token: String, caseIdInCRM: String, webCaseIdInCRM: String, fileName: String, jpeg: ByteArray) = error("no attachment")
                    } })
            } }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("בדיקת הדיווח").fetchSemanticsNodes().isNotEmpty() }
            if (compose.onAllNodesWithText("למצוא רחוב ומספר בית?").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithText("לא עכשיו").performClick()
            compose.onNodeWithText("בחר נושא מהעירייה").performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("חפש נושא, תיאור או מספר").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("סגור").performClick()
            compose.onNodeWithText("בחר נושא מהעירייה").performScrollTo().performClick()
            compose.onNodeWithText("חפש נושא, תיאור או מספר").assertIsDisplayed()
            compose.runOnIdle { assertEquals(1, authentications); assertEquals(1, logins); assertEquals(1, catalogReads) }
        } finally { cleanLedgerAndOriginals(); seedSyntheticCityTopics() }
    }

    @Test fun noLockCatalogBootstrapAnnouncesReasonWithoutAuthentication() {
        cleanLedgerAndOriginals()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.noBackupFilesDir, "city-topics/catalog.json").delete()
        io.github.reihlinstinct.reporter.security.SettingsProfileStore(context).save(emptyList())
        var authentications = 0
        var reads = 0
        val signal = mutableStateOf(EntrySignal(1, EntryIntents.Request.SharedPhoto(
            "content://io.github.reihlinstinct.reporter.testshare/topic-no-lock.jpg")))
        try {
            compose.setContent {
                io.github.reihlinstinct.reporter.ui.theme.AppTheme {
                    AppShell(authenticate = { _, _, _ -> authentications++ }, hasDeviceCredential = { false },
                        requestCamera = {}, readSettings = { reads++; null }, saveSettings = {}, entry = signal)
                }
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("בדיקת הדיווח").fetchSemanticsNodes().isNotEmpty() }
            if (compose.onAllNodesWithText("למצוא רחוב ומספר בית?").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithText("לא עכשיו").performClick()
            compose.onNodeWithText("בחר נושא מהעירייה").performScrollTo().assertIsNotEnabled().performClick()
            compose.onAllNodesWithText("הגדר נעילת מסך")[0]
                .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.LiveRegion,
                    androidx.compose.ui.semantics.LiveRegionMode.Polite))
            compose.runOnIdle { assertEquals(0, authentications); assertEquals(0, reads) }
        } finally { cleanLedgerAndOriginals(); seedSyntheticCityTopics() }
    }

    @Test fun deepLinkNewReportRequestsCamera() {
        var cameraRequested = false
        val signal = mutableStateOf(EntrySignal(1, EntryIntents.Request.NewReport))
        compose.setContent {
            MaterialTheme {
                AppShell(authenticate = { _, _, action -> action() }, requestCamera = { cameraRequested = true },
                    readSettings = { null }, saveSettings = {}, entry = signal)
            }
        }
        compose.waitUntil(15_000) { cameraRequested }
        assertTrue(cameraRequested)
    }
}
