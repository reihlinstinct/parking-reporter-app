package il.sidewalks.reporter.live

import android.content.Context
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import il.sidewalks.reporter.EntryIntents
import il.sidewalks.reporter.EntrySignal
import il.sidewalks.reporter.FlowDriver
import il.sidewalks.reporter.FlowFields
import il.sidewalks.reporter.ledger.ReportDatabase
import il.sidewalks.reporter.security.ReporterSettings
import il.sidewalks.reporter.ui.AppShell
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/**
 * Live submission E2E: the full UI flow against the REAL municipal endpoint,
 * filing one real case from owner-approved staged data. Opt-in only: the
 * regular CI emulator job never stages live-params.json, so this returns
 * immediately there and the suite stays green. The dedicated manual
 * workflow (live-emulator-e2e.yml) stages the params from repository
 * secrets into the app's private files directory, records the emulator
 * screen and encrypts the recording before any upload.
 *
 * The flow uses the app's production defaults end to end: the real pinned
 * municipal endpoint, the real on-device street directory and the same
 * FlowDriver the synthetic stub E2E drives.
 *
 * Privacy: credentials and report details never reach logcat, assertion
 * messages or stdout. The only test output is a scrubbed marker log (fixed
 * step names, the municipal case number, exception class names) plus
 * screenshots for the encrypted evidence bundle.
 */
class LiveEmulatorSubmissionTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var logFile: File

    /** Fixed markers only; this file lands in the workflow's public log. */
    private fun marker(step: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Jerusalem")
        }.format(Date())
        logFile.appendText("$stamp LIVE_E2E $step\n")
    }

    @Test fun liveApprovedSubmission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val paramsFile = File(context.filesDir, "live-params.json")
        // Not a live run: only the manual workflow stages the params file.
        if (!paramsFile.isFile) return
        logFile = File(context.filesDir, "live-e2e-log.txt")
        logFile.delete()
        try {
            runLive(context, JSONObject(paramsFile.readText(Charsets.UTF_8)))
        } catch (e: Throwable) {
            runCatching { marker("FAIL class=" + e.javaClass.simpleName) }
            // The real stack stays in instrumentation output, which the
            // workflow publishes only inside the encrypted bundle.
            throw e
        } finally {
            // The params carry repository secrets; never leave them on device.
            paramsFile.delete()
        }
    }

    private fun runLive(context: Context, params: JSONObject) {
        il.sidewalks.reporter.i18n.UiPrefsLanguageStore.of(context).set("he")
        marker("START")

        // Consent binding, same rule as the engine submit trial: the staged
        // text must hash to the exact digest the owner approved. Constant
        // message only: never print the digests.
        val text = params.getString("text")
        val expectedDigest = params.getString("approvedSha256").lowercase(Locale.US)
        val actualDigest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        if (expectedDigest != actualDigest) {
            throw AssertionError("approved text digest mismatch - refusing to submit")
        }
        marker("DIGEST_OK")

        val settings = ReporterSettings(
            firstName = params.getString("firstName"),
            lastName = params.getString("lastName"),
            phone = params.getString("phone"),
            idNumber = params.getString("idNumber"),
            email = params.getString("email"),
            municipalSubscriptionKey = params.getString("subscriptionKey"),
            municipalUsername = params.getString("username"),
            municipalPassword = params.getString("password"))

        // Claim the staged photo only if no report already owns it: a live
        // run never re-files a photo that already produced a case.
        val sharedUri = Uri.parse("content://il.sidewalks.reporter.liveshare/live-photo.jpg")
        val bytes = requireNotNull(context.contentResolver.openInputStream(sharedUri)) {
            "staged photo missing"
        }.use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val db = Room.databaseBuilder(context, ReportDatabase::class.java, "report-ledger.db").build()
        try {
            if (runBlocking { db.reports().findPhoto(hash) } != null) {
                throw AssertionError("staged photo already has a report; refusing to file again")
            }
        } finally {
            db.close()
        }
        marker("PHOTO_CLAIMED")

        val driver = FlowDriver(compose)
        val signal = mutableStateOf(EntrySignal(1, EntryIntents.Request.SharedPhoto(sharedUri.toString())))
        compose.setContent {
            MaterialTheme {
                AppShell(authenticate = { _, _, action -> action() }, requestCamera = { it() },
                    readSettings = { settings }, saveSettings = {}, entry = signal)
            }
        }
        var cleaned = false
        try {
            driver.driveToApproved(FlowFields(
                text = text,
                plate = params.optString("plate").ifBlank { null },
                address = params.getString("address"),
                capturedAtIso = params.getString("capturedAt"),
                latitude = params.getString("lat"),
                longitude = params.getString("lon"),
                categoryLabel = params.optString("categoryLabel").ifBlank { "חניה על המדרכה" }))
            marker("APPROVED")
            driver.screenshot("live-e2e-approved.png")
            driver.tapSendAndAwaitCaseNumber()
            marker("SEND_CONFIRMED")
            driver.screenshot("live-e2e-submitted.png")
            val shown = driver.nodeTextContaining("מספר קריאה") ?: ""
            val caseNumber = Regex("\\d{4}-\\d{7}").find(shown)?.value ?: "unparsed"
            marker("SUBMITTED case=$caseNumber")
            cleanOwnedPhoto(context, hash)
            cleaned = true
            marker("DONE")
        } finally {
            if (!cleaned) runCatching { cleanOwnedPhoto(context, hash) }
        }
    }

    /** Remove only the row and files created from this run's staged photo. */
    private fun cleanOwnedPhoto(context: Context, hash: String) {
        val db = Room.databaseBuilder(context, ReportDatabase::class.java, "report-ledger.db").build()
        try {
            val record = runBlocking { db.reports().findPhoto(hash) } ?: return
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM reports WHERE stableId = ? AND originalSha256 = ?", arrayOf(record.stableId, hash))
            File(context.noBackupFilesDir, record.originalRelativePath).delete()
            File(context.noBackupFilesDir, "submissions/${record.stableId}.json").delete()
        } finally {
            db.close()
        }
    }
}
