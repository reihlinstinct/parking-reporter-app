package io.github.reihlinstinct.reporter

import android.net.Uri
import io.github.reihlinstinct.reporter.ui.theme.AppTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.ledger.ReportDatabase
import io.github.reihlinstinct.reporter.municipal.StreetDirectory
import io.github.reihlinstinct.reporter.municipal.MunicipalClient
import io.github.reihlinstinct.reporter.municipal.OkHttpMunicipalClient
import io.github.reihlinstinct.reporter.security.ReporterSettings
import io.github.reihlinstinct.reporter.ui.AppShell
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Full report flow, end to end, on the real AppShell: shared-photo entry,
 * review, evidence confirmation, approval, send, municipal submission.
 *
 * Stub-only: an on-device loopback MockWebServer answers the four municipal
 * calls with canned responses. No network outside loopback, no credentials,
 * no private data, and no live mode. The app manifest forbids cleartext;
 * app/src/debug scopes a loopback-only exception to test builds.
 * Device-unlock is replaced by a pass-through only in this synthetic test.
 * The UI driving itself lives in FlowDriver, shared with the opt-in live
 * submission E2E so both exercise the identical flow code path.
 */
class FullFlowE2eTest {
    @get:Rule val compose = createComposeRule()

    @Before fun pinHebrewUiLanguage() {
        seedSyntheticCityTopics()

        InstrumentationRegistry.getInstrumentation().targetContext.let { io.github.reihlinstinct.reporter.i18n.UiPrefsLanguageStore.of(it).set(io.github.reihlinstinct.reporter.i18n.AppCatalog.defaultCode(it)) }
    }

    private val sharedUri = Uri.parse("content://il.sidewalks.reporter.testshare/with-metadata.jpg")
    private var ownedPhotoHash: String? = null

    /** Never take ownership of a pre-existing row, even one with the test photo. */
    private fun claimSyntheticPhoto() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = requireNotNull(context.contentResolver.openInputStream(sharedUri)).use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val db = Room.databaseBuilder(context, ReportDatabase::class.java, "report-ledger.db").build()
        try {
            assumeTrue("Test photo already has a report; leave it untouched", runBlocking { db.reports().findPhoto(hash) } == null)
            ownedPhotoHash = hash
        } finally {
            db.close()
        }
    }

    /** Remove only the row and files created from this test's synthetic photo. */
    private fun cleanOwnedState() {
        val hash = ownedPhotoHash ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, ReportDatabase::class.java, "report-ledger.db").build()
        try {
            val record = runBlocking { db.reports().findPhoto(hash) } ?: return
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM reports WHERE stableId = ? AND originalSha256 = ?", arrayOf(record.stableId, hash))
            File(context.noBackupFilesDir, record.originalRelativePath).delete()
            File(context.noBackupFilesDir, "submissions/${record.stableId}.json").delete()
        } finally {
            db.close()
            ownedPhotoHash = null
        }
    }

    /** Synthetic directory injected in memory; never edits an installed file. */
    private fun syntheticStreetDirectory(): StreetDirectory = StreetDirectory.load(
        """{"version":1,"streets":[{"StreetCode":"900001","StreetName":"רחוב בדיקה"}],"addresses":[[31.77,35.2137,"1","רחוב בדיקה"]]}""")

    private fun launchFlow(settings: ReporterSettings, factory: ((ReporterSettings) -> MunicipalClient)?) {
        val uri = sharedUri
        val signal = mutableStateOf(EntrySignal(1, EntryIntents.Request.SharedPhoto(uri.toString())))
        compose.setContent {
            AppTheme {
                AppShell(authenticate = { _, _, action -> action() }, requestCamera = { it() },
                    readSettings = { settings }, saveSettings = {}, entry = signal,
                    municipalClientFactory = factory,
                    streetDirectoryLoader = { syntheticStreetDirectory() })
            }
        }
    }

    @Test fun localInputRefusalIsReportedAsFailureNotMissedTap() {
        claimSyntheticPhoto()
        try {
            // A valid review with an unsupported reporter phone is refused
            // before state/network. This note lacks the old driver phrases.
            val settings = ReporterSettings(
                firstName = "ישראל", lastName = "ישראלי", phone = "123",
                idNumber = "000000018", email = "synthetic@example.com",
                municipalSubscriptionKey = "synthetic-key",
                municipalUsername = "synthetic-user", municipalPassword = "synthetic-pass")
            var clientCalls = 0
            launchFlow(settings) { _ -> clientCalls++; error("must not create a client") }
            val driver = FlowDriver(compose)
            driver.driveToSaved(FlowFields(
                text = "רכב 1234567 חונה על המדרכה בתרחיש בדיקה סינתטי", plate = "1234567",
                address = "רחוב בדיקה 1", capturedAtIso = "2026-01-01T12:00:00+02:00",
                latitude = "31.77", longitude = "35.2137", categoryLabel = "חניה על המדרכה"))
            assertEquals(FlowDriver.SendOutcome.SUBMIT_FAILED, driver.tapSendAndAwaitOnce(5_000))
            assertEquals(0, clientCalls)
        } finally { cleanOwnedState() }
    }

    /** #244, current single-confirmation UX: first tap may add disclosure,
     * but must not send it until a later explicit Send on the revised text. */
    @Test fun firstSendDisclosurePersistsRevisionAndReloadsWithoutNetworkUntilSecondSend() {
        claimSyntheticPhoto()
        val settings = ReporterSettings(
            firstName = "ישראל", lastName = "ישראלי", phone = "+12025550123",
            idNumber = "", email = "synthetic@example.com", passportMode = true,
            passportNumber = "SYNTHETIC-PASSPORT", municipalSubscriptionKey = "synthetic-key",
            municipalUsername = "synthetic-user", municipalPassword = "synthetic-pass")
        val clientCreations = java.util.concurrent.atomic.AtomicInteger()
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val reports = java.util.concurrent.CopyOnWriteArrayList<String>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, ReportDatabase::class.java, "report-ledger.db").build()
        fun row() = requireNotNull(runBlocking { db.reports().findPhoto(requireNotNull(ownedPhotoHash)) })
        try {
            launchFlow(settings) { _ ->
                clientCreations.incrementAndGet()
                object : MunicipalClient {
                    override fun login(): String { calls += "login"; return "synthetic-token" }
                    override fun upsertContact(token: String, contact: LinkedHashMap<String, Any?>): Map<String, Any?> {
                        calls += "contact"
                        assertEquals(io.github.reihlinstinct.reporter.municipal.PassportSubmission.COMPATIBILITY_ID, contact["ContactIdNumber"])
                        assertEquals(io.github.reihlinstinct.reporter.municipal.PassportSubmission.INTERNATIONAL_PHONE_PLACEHOLDER, contact["MobilePhoneNumber"])
                        return mapOf("ContactIdInCRM" to "synthetic-contact")
                    }
                    override fun createCase(token: String, contact: LinkedHashMap<String, Any?>, report: LinkedHashMap<String, Any?>): Map<String, Any?> {
                        calls += "create"; reports += report["CaseDescription"].toString()
                        return mapOf("CaseIdInCRM" to "synthetic-case", "WebCaseIdInCRM" to "synthetic-web", "CaseNumberInCRM" to "2000-0000000")
                    }
                    override fun attachPhoto(token: String, caseIdInCRM: String, webCaseIdInCRM: String, fileName: String, jpeg: ByteArray) {
                        calls += "attach"
                    }
                }
            }
            val driver = FlowDriver(compose)
            val text = "רכב 12345678 חונה על המדרכה בתרחיש בדיקה סינתטי"
            driver.driveToSaved(FlowFields(text, "12345678", "רחוב בדיקה 1",
                "2026-01-01T12:00:00+02:00", "31.77", "35.2137", "חניה על המדרכה"))
            val before = row()
            assertEquals(FlowDriver.SendOutcome.SUBMIT_FAILED, driver.tapSendAndAwaitOnce(10_000))
            val revised = row()
            val decoded = io.github.reihlinstinct.reporter.ledger.DraftCodec.decode(revised.draftJson)
            val expected = io.github.reihlinstinct.reporter.municipal.PassportSubmission.reconcileDisclosure(text, settings)
            assertEquals(before.revision + 1, revised.revision)
            assertEquals(expected, decoded.exactHebrewText)
            assertEquals(decoded.digest(), revised.reviewedDigest)
            assertTrue(io.github.reihlinstinct.reporter.municipal.PassportSubmission.hasReviewedDisclosure(decoded.exactHebrewText, settings))
            assertEquals(0, clientCreations.get())
            assertTrue(calls.isEmpty())
            assertFalse(File(context.noBackupFilesDir, "submissions/${revised.stableId}.json").exists())
            compose.onNodeWithText("xxxxxORT", substring = true).assertExists()
            driver.screenshot("disclosure-first-send-revised.png")
            // Dispose ReviewScreen and restore from the actual persisted ledger row.
            compose.onNodeWithText("חזור").performScrollTo().performClick()
            compose.onNodeWithText("הדיווחים שלי").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(revised.stableId.take(8), substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            // Catalog placeholders insert bidi isolates around IDs, so do not
            // hardcode a visually equivalent but byte-different button label.
            compose.onNodeWithText(revised.stableId.take(8), substring = true).performScrollTo().performClick()
            compose.onNodeWithText("xxxxxORT", substring = true).assertExists()
            assertEquals(revised, row())
            assertEquals(0, clientCreations.get())
            assertTrue(calls.isEmpty())
            // Reopening starts derivative preparation asynchronously. Restored
            // text alone does not mean the exact outgoing image is ready.
            // Wait for the real preview, without another send attempt or delay.
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("צילום ראיה")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            // Re-saving/reloading is not sending. Only the second explicit Send
            // can authorize the now-visible full disclosure and contact details.
            driver.tapSendAndAwaitCaseNumber()
            assertEquals(1, clientCreations.get())
            assertEquals(listOf("login", "contact", "create", "attach"), calls.toList())
            assertEquals(listOf(expected), reports.toList())
        } finally { db.close(); cleanOwnedState() }
    }

    @Test fun fullReportFlowStubbedServer() = fullReportFlowStubbed("חניה על המדרכה", "1145")

    @Test fun redWhiteNumeric1144FilesOnlyThatCodeToLoopbackStub() =
        fullReportFlowStubbed("1144", "1144")

    private fun fullReportFlowStubbed(selector: String, expectedCode: String) {
        claimSyntheticPhoto()
        val settings = ReporterSettings(
            firstName = "ישראל", lastName = "ישראלי", phone = "+972501234567",
            idNumber = "000000018", email = "synthetic@example.com",
            municipalSubscriptionKey = "synthetic-key",
            municipalUsername = "synthetic-user",
            municipalPassword = "synthetic-pass")
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Success":true,"Token":"synthetic-token"}"""))
            server.enqueue(MockResponse().setBody("""{"Success":true,"ContactIdInCRM":"synthetic-contact-id"}"""))
            server.enqueue(MockResponse().setBody(
                """{"Success":true,"CaseIdInCRM":"synthetic-case-id","WebCaseIdInCRM":"synthetic-webcase-id","CaseNumberInCRM":"2000-0000000"}"""))
            server.enqueue(MockResponse().setBody("""{"Success":true}"""))
            server.start()
            try {
                launchFlow(settings) { s ->
                    OkHttpMunicipalClient.forTesting(server.url("/"),
                        s.municipalSubscriptionKey, s.municipalUsername, s.municipalPassword)
                }
                val driver = FlowDriver(compose)
                val text = "רכב 12345678 חונה על המדרכה בתרחיש בדיקה סינתטי"
                driver.driveToSaved(FlowFields(
                    text = text,
                    plate = "12345678",
                    address = "רחוב בדיקה 1",
                    capturedAtIso = "2026-01-01T12:00:00+02:00",
                    latitude = "31.77",
                    longitude = "35.2137",
                    categoryLabel = selector))
                driver.screenshot("e2e-review-synthetic.png")
                driver.tapSendAndAwaitCaseNumber()
                driver.screenshot("e2e-submitted-synthetic.png")

                val login = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Login never reached the stub" }
                val contact = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "contact never reached the stub" }
                val create = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "CreateCase never reached the stub" }
                val attach = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "AddFileToCase never reached the stub" }
                assertEquals("/Login", login.path)
                assertEquals("/createupdatecontact", contact.path)
                assertEquals("/CreateCase", create.path)
                assertEquals("/AddFileToCase", attach.path)
                assertEquals("synthetic-user", JSONObject(login.body.readUtf8()).getString("UserName"))
                val createBody = JSONObject(create.body.readUtf8())
                assertEquals(text, createBody.getString("CaseDescription"))
                assertEquals("900001", createBody.getString("CaseStreetCode"))
                assertEquals("1", createBody.getString("CaseHouseNumber"))
                assertEquals(expectedCode, createBody.getString("CaseSubjectCode"))
                assertEquals("000000018", createBody.getJSONObject("contact").getString("ContactIdNumber"))
                val attachBody = JSONObject(attach.body.readUtf8())
                val jpeg = java.util.Base64.getDecoder().decode(attachBody.getString("fileContent"))
                assertTrue("attached file must be an outgoing JPEG",
                    jpeg.size > 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
                // #201: exercise the real AppShell -> coordinator -> HTTP attachment
                // with a GPS/time-bearing source, not merely the image utility.
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val originalBytes = requireNotNull(context.contentResolver.openInputStream(sharedUri)).use { it.readBytes() }
                val sourceExif = androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(originalBytes))
                assertNotNull(sourceExif.latLong)
                assertNotNull(sourceExif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL))
                val attachedExif = androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(jpeg))
                assertNull(attachedExif.latLong)
                assertNull(attachedExif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL))
                assertTrue("outgoing cap", jpeg.size <= 1_100_000)
                assertTrue("no EXIF segment", !String(jpeg, Charsets.ISO_8859_1).contains("Exif\u0000\u0000"))
                val db = Room.databaseBuilder(context, ReportDatabase::class.java, "report-ledger.db").build()
                try {
                    val row = requireNotNull(runBlocking { db.reports().findPhoto(requireNotNull(ownedPhotoHash)) })
                    val reviewed = io.github.reihlinstinct.reporter.ledger.DraftCodec.decode(row.draftJson)
                    assertEquals("12345678", reviewed.plate)
                    assertEquals("municipal:$expectedCode", reviewed.subject)
                    val original = File(context.noBackupFilesDir, row.originalRelativePath)
                    assertArrayEquals(originalBytes, original.readBytes())
                    val preview = io.github.reihlinstinct.reporter.evidence.OutgoingEvidence.prepare(original)
                    assertArrayEquals(preview.jpeg, jpeg)
                    val state = io.github.reihlinstinct.reporter.municipal.FileSubmissionStateStore.forReport(
                        File(context.noBackupFilesDir, "submissions"), row.stableId).read()
                    assertEquals(preview.hash, state?.get("photo_sha256"))
                } finally { db.close() }
                assertNotNull(attachBody.getString("caseIdInCRM"))
            } finally {
                cleanOwnedState()
            }
        }
    }

    /**
     * Regression for run 42769339528: a send tap that never reaches the
     * app's submit path (here: the send button is disabled because the fresh
     * draft is incomplete, so send is not enabled) must be reported as
     * SEND_NOT_FIRED - provably no request was made - and never as a
     * municipal-looking timeout.
     */
    @Test fun sendTapOnIncompleteDraftIsNotFired() {
        claimSyntheticPhoto()
        val settings = ReporterSettings(
            firstName = "ישראל", lastName = "ישראלי", phone = "+972501234567",
            idNumber = "000000018", email = "synthetic@example.com",
            municipalSubscriptionKey = "synthetic-key",
            municipalUsername = "synthetic-user",
            municipalPassword = "synthetic-pass")
        try {
            launchFlow(settings, null)
            val driver = FlowDriver(compose)
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("בדיקת הדיווח").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText("נמצא מיקום בתמונה").fetchSemanticsNodes().isNotEmpty()
            }
            if (compose.onAllNodesWithText("נמצא מיקום בתמונה").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText("השתמש במיקום").performClick()
            }
            // Location consent starts an async import/reserve. The absence of
            // an enabled Send button during that import is NOT the negative
            // send path under test. Wait for review, which is published only
            // after the synthetic photo's row has been reserved, so finally
            // cannot clean too early and leave a late row for the next test.
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("בדיקת הדיווח").fetchSemanticsNodes().isNotEmpty()
            }
            if (compose.onAllNodesWithText("למצוא רחוב ומספר בית?").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText("לא עכשיו").performClick()
            }
            assertEquals(FlowDriver.SendOutcome.SEND_NOT_FIRED, driver.tapSendAndAwaitOnce(5_000))
        } finally {
            cleanOwnedState()
        }
    }

    /**
     * Tab #264 review: File.list() returns null both for an absent directory
     * and for one that cannot be read, so a null listing is not proof that no
     * submission record exists. With a plain FILE parked at the submissions
     * path (exists() true, list() null) the driver cannot prove a send tap is
     * pre-network, so it must refuse to tap at all.
     */
    @Test fun sendTapWithoutProvablyEmptyRecordDirIsNotFired() {
        claimSyntheticPhoto()
        val settings = ReporterSettings(
            firstName = "ישראל", lastName = "ישראלי", phone = "+972501234567",
            idNumber = "000000018", email = "synthetic@example.com",
            municipalSubscriptionKey = "synthetic-key",
            municipalUsername = "synthetic-user",
            municipalPassword = "synthetic-pass")
        MockWebServer().use { server ->
            server.start()
            try {
                launchFlow(settings) { s ->
                    OkHttpMunicipalClient.forTesting(server.url("/"),
                        s.municipalSubscriptionKey, s.municipalUsername, s.municipalPassword)
                }
                val blocker = File(
                    InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
                    "submissions")
                // Earlier tests may leave the empty submissions directory.
                // Remove only a provably empty directory, never other records.
                if (blocker.isDirectory) {
                    assumeTrue("Preserve existing submission records", blocker.list()?.isEmpty() == true)
                    assertTrue("Remove empty synthetic-state directory", blocker.delete())
                }
                assumeTrue("Preserve a pre-existing non-directory", !blocker.exists())
                blocker.writeText("not a directory - an unreadable listing must fail closed")
                try {
                    val driver = FlowDriver(compose)
                    driver.driveToSaved(FlowFields(
                        text = "רכב 12345678 חונה על המדרכה בתרחיש בדיקה סינתטי",
                        plate = "12345678",
                        address = "רחוב בדיקה 1",
                        capturedAtIso = "2026-01-01T12:00:00+02:00",
                        latitude = "31.77",
                        longitude = "35.2137",
                        categoryLabel = "חניה על המדרכה"))
                    var tapped = false
                    assertEquals(FlowDriver.SendOutcome.SEND_NOT_FIRED,
                        driver.tapSendAndAwaitOnce(5_000, onTapAboutToFire = { tapped = true }))
                    assertTrue("send must not be tapped while the record directory is not provably empty", !tapped)
                    assertEquals(0, server.requestCount)
                } finally {
                    blocker.delete()
                }
            } finally {
                cleanOwnedState()
            }
        }
    }

    /** Tab review (#266): a send tap without a prior save persists the exact
     * shown draft into the ledger before the first network call (M-B). */
    @Test fun sendWithoutPriorSavePersistsShownDraftBeforeNetwork() {
        claimSyntheticPhoto()
        val settings = ReporterSettings(
            firstName = "ישראל", lastName = "ישראלי", phone = "+972501234567",
            idNumber = "000000018", email = "synthetic@example.com",
            municipalSubscriptionKey = "synthetic-key",
            municipalUsername = "synthetic-user",
            municipalPassword = "synthetic-pass")
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Success":true,"Token":"synthetic-token"}"""))
            server.enqueue(MockResponse().setBody("""{"Success":true,"ContactIdInCRM":"synthetic-contact-id"}"""))
            server.enqueue(MockResponse().setBody(
                """{"Success":true,"CaseIdInCRM":"synthetic-case-id","WebCaseIdInCRM":"synthetic-webcase-id","CaseNumberInCRM":"2000-0000000"}"""))
            server.enqueue(MockResponse().setBody("""{"Success":true}"""))
            server.start()
            try {
                launchFlow(settings) { s ->
                    OkHttpMunicipalClient.forTesting(server.url("/"),
                        s.municipalSubscriptionKey, s.municipalUsername, s.municipalPassword)
                }
                val driver = FlowDriver(compose)
                val text = "רכב 12345678 חונה על המדרכה בתרחיש בדיקה סינתטי"
                driver.driveToEdited(FlowFields(
                    text = text,
                    plate = "12345678",
                    address = "רחוב בדיקה 1",
                    capturedAtIso = "2026-01-01T12:00:00+02:00",
                    latitude = "31.77",
                    longitude = "35.2137",
                    categoryLabel = "חניה על המדרכה"))
                driver.tapSendAndAwaitCaseNumber()
                assertEquals(4, server.requestCount)
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val db = Room.databaseBuilder(context, ReportDatabase::class.java, "report-ledger.db").build()
                try {
                    val hash = requireNotNull(ownedPhotoHash) { "no claimed photo" }
                    val record = requireNotNull(runBlocking { db.reports().findPhoto(hash) })
                    val stored = io.github.reihlinstinct.reporter.ledger.DraftCodec.decode(record.draftJson)
                    assertEquals(text, stored.exactHebrewText)
                    assertTrue("the send tap folded the time confirmation", stored.unresolved.isEmpty())
                    assertNotNull("saved-ready digest recorded", record.reviewedDigest)
                } finally {
                    db.close()
                }
            } finally {
                cleanOwnedState()
            }
        }
    }

    /** Tab review (#266): when the shown draft cannot be persisted, the send
     * stops before the first network call - unsaved text is never sent. */
    @Test fun failedSaveBlocksNetwork() {
        claimSyntheticPhoto()
        val settings = ReporterSettings(
            firstName = "ישראל", lastName = "ישראלי", phone = "+972501234567",
            idNumber = "000000018", email = "synthetic@example.com",
            municipalSubscriptionKey = "synthetic-key",
            municipalUsername = "synthetic-user",
            municipalPassword = "synthetic-pass")
        MockWebServer().use { server ->
            server.start()
            try {
                launchFlow(settings) { s ->
                    OkHttpMunicipalClient.forTesting(server.url("/"),
                        s.municipalSubscriptionKey, s.municipalUsername, s.municipalPassword)
                }
                val driver = FlowDriver(compose)
                driver.driveToEdited(FlowFields(
                    text = "רכב 12345678 חונה על המדרכה בתרחיש בדיקה סינתטי",
                    plate = "12345678",
                    address = "רחוב בדיקה 1",
                    capturedAtIso = "2026-01-01T12:00:00+02:00",
                    latitude = "31.77",
                    longitude = "35.2137",
                    categoryLabel = "חניה על המדרכה"))
                // An external writer bumps the row, making the shell's
                // in-memory revision stale: the persist step fails closed.
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val db = Room.databaseBuilder(context, ReportDatabase::class.java, "report-ledger.db").build()
                try {
                    val hash = requireNotNull(ownedPhotoHash) { "no claimed photo" }
                    val record = requireNotNull(runBlocking { db.reports().findPhoto(hash) })
                    db.openHelper.writableDatabase.execSQL(
                        "UPDATE reports SET revision = revision + 1 WHERE stableId = ?", arrayOf(record.stableId))
                } finally {
                    db.close()
                }
                assertEquals(FlowDriver.SendOutcome.DRAFT_NOT_SAVED, driver.tapSendAndAwaitOnce(15_000))
                assertEquals(0, server.requestCount)
            } finally {
                cleanOwnedState()
            }
        }
    }

}
