package io.github.reihlinstinct.reporter

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import io.github.reihlinstinct.reporter.core.Evidence
import io.github.reihlinstinct.reporter.core.ReviewDraft
import io.github.reihlinstinct.reporter.ledger.ReportDatabase
import io.github.reihlinstinct.reporter.ledger.ReportRecord
import io.github.reihlinstinct.reporter.security.ReporterSettings
import io.github.reihlinstinct.reporter.security.SecureSettings
import io.github.reihlinstinct.reporter.ui.ReviewScreen
import io.github.reihlinstinct.reporter.ui.SettingsScreen
import io.github.reihlinstinct.reporter.ui.theme.AppTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class M1ShellTest {
    @get:Rule val compose = createComposeRule()
    // Issue #115 PR-2a: with no stored choice the UI follows the system locale, which is
    // en-US on the CI emulator. These tests look texts up by their Hebrew wording, so the
    // UI language is pinned to he through the real "ui-prefs" store.
    @Before fun pinHebrewUiLanguage() {
        seedSyntheticCityTopics()

        InstrumentationRegistry.getInstrumentation().targetContext.let { io.github.reihlinstinct.reporter.i18n.UiPrefsLanguageStore.of(it).set(io.github.reihlinstinct.reporter.i18n.AppCatalog.defaultCode(it)) }
    }
    private fun draft() = ReviewDraft(Evidence("synthetic", "a".repeat(64), "2026-01-01T12:00:00Z", 31.0, 35.0),
        "1234567", "רחוב בדיקה 1", "sidewalk_parking", "רכב 1234567 חונה על המדרכה", emptySet())
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun noLockscreenGreysSendWithNote() {
        // Issue #260 (owner spec 2026-11-10): with no device lockscreen the
        // send button is greyed out and a small "הגדר נעילת מסך" note shows.
        // No bypass: the tap does nothing. One setContent per test (Compose
        // forbids two), so the lockscreen state has its own test below.
        val ready = draft()
        var submitted: ReviewDraft? = null
        compose.setContent { AppTheme { ReviewScreen(ready, null, {}, onBack = {}, onSubmit = { submitted = it }, noDeviceCredential = true, topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        compose.onNodeWithText("הגדר נעילת מסך").performScrollTo().assertIsDisplayed()
        screenshot("m1-review-no-lockscreen-synthetic.png")
        compose.onNodeWithText("שלח לעירייה").assertIsNotEnabled()
        compose.onNodeWithText("שלח לעירייה").performScrollTo().performClick()
        compose.runOnIdle { assertNull(submitted) }
    }
    @Test fun lockscreenStateFlipUpdatesSendAndNote() {
        // Issue #260 review blocker 3: the screen must follow the state, so
        // returning from Settings after setting a lock refreshes it (the
        // production wiring re-reads isDeviceSecure in MainActivity.onResume).
        val ready = draft()
        var noLock by mutableStateOf(true)
        compose.setContent { AppTheme { ReviewScreen(ready, null, {}, onBack = {}, onSubmit = {}, noDeviceCredential = noLock, topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        compose.onNodeWithText("הגדר נעילת מסך").assertIsDisplayed()
        compose.onNodeWithText("שלח לעירייה").assertIsNotEnabled()
        compose.runOnIdle { noLock = false }
        compose.onNodeWithText("הגדר נעילת מסך").assertDoesNotExist()
        compose.onNodeWithText("שלח לעירייה").assertIsEnabled()
        compose.runOnIdle { noLock = true }
        compose.onNodeWithText("הגדר נעילת מסך").assertIsDisplayed()
        compose.onNodeWithText("שלח לעירייה").assertIsNotEnabled()
    }
    @Test fun lockscreenKeepsSendEnabledWithoutNote() {
        // Issue #260: with a lockscreen nothing changes - no note, send enabled.
        val ready = draft()
        compose.setContent { AppTheme { ReviewScreen(ready, null, {}, onBack = {}, onSubmit = {}, noDeviceCredential = false, topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        screenshot("m1-review-lockscreen-ok-synthetic.png")
        compose.onNodeWithText("הגדר נעילת מסך").assertDoesNotExist()
        compose.onNodeWithText("שלח לעירייה").assertIsEnabled()
    }
    @Test fun reviewSaveThenEditKeepsLatestSavedCopy() {
        var saved: ReviewDraft? = null
        var saves = 0
        compose.setContent { AppTheme { ReviewScreen(draft(), null, { saves++; saved = it }, onBack = {}, topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        screenshot("m1-review-synthetic.png")
        compose.onNodeWithText("שמור טיוטה").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("first save callback", 1, saves); assertTrue(saved?.isReady() == true) }
        compose.onNodeWithText("ערוך").performScrollTo().performClick()
        compose.onNodeWithText("מספר לוחית (ספרות בלבד)").performScrollTo().performTextReplacement("7654321")
        compose.onNodeWithText("מספר לוחית (ספרות בלבד)").assertTextContains("7654321")
        // Complete the normal IME close before scrolling/tapping the physical save target.
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        screenshot("review-resave-edited-before-save.png")
        compose.onNodeWithText("שמור טיוטה").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("second save callback", 2, saves); assertEquals("7654321", saved?.plate); assertTrue(saved?.isReady() == true) }
        screenshot("review-resave-after-save.png")
    }
    @Test fun offlinePlateCandidateRequiresSelectionAndSave() {
        val bitmap = Bitmap.createBitmap(1200, 260, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap); canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawText("12345678", 65f, 190f, android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK; textSize = 160f; isAntiAlias = true
            typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
        })
        var saved: ReviewDraft? = null
        compose.setContent { AppTheme { ReviewScreen(draft(), bitmap.asImageBitmap(), { saved = it }, {}, topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        compose.onNodeWithText("ערוך").performScrollTo().performClick()
        compose.onNodeWithText("הצע לוחית מתוך הצילום").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("הצעה בלבד: \u2066123-45-678\u2069").fetchSemanticsNodes().isNotEmpty() }
        screenshot("m2-ocr-synthetic.png")
        compose.onNodeWithText("הצעה בלבד: \u2066123-45-678\u2069").performScrollTo().performClick()
        compose.onNodeWithText("שמור טיוטה").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("12345678", saved?.plate); assertTrue(saved?.isReady() == true) }
    }
    @Test fun settingsStoreManuallyEnteredCredentialsAndClearAfterSave() {
        // Owner decision 2026-11-08: municipal API keys are entered manually here and
        // stored only inside the encrypted settings. The screenshot is taken before any
        // value is typed, so no credential ever reaches a captured image.
        var saved: ReporterSettings? = null
        compose.setContent { AppTheme { SettingsScreen(null, { value, _, clear -> saved = value; clear() }, {}) } }
        screenshot("m1-settings-synthetic.png")
        compose.onNodeWithText("שם פרטי").performTextInput("בדיקה")
        compose.onNodeWithText("שם משפחה").performTextInput("מדווח")
        // Issue #295: the save button requires every reporter field filled and valid.
        compose.onNodeWithText("מספר זהות").performScrollTo().performTextReplacement("000000018")
        compose.onNodeWithText("טלפון").performScrollTo().performTextReplacement("050-123-4567")
        compose.onNodeWithText("אימייל").performScrollTo().performTextReplacement("synthetic@example.com")
        compose.onNodeWithText("חיבור 106 מתקדם").performScrollTo().performClick()
        compose.onNodeWithText("מפתח מנוי").performScrollTo().performTextInput("synthetic-subscription-key")
        compose.onNodeWithText("שם משתמש API").performScrollTo().performTextInput("synthetic-api-user")
        compose.onNodeWithText("סיסמת API").performScrollTo().performTextInput("synthetic-api-pass")
        compose.onNodeWithText("אמת זהות ושמור מוצפן").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("בדיקה", saved?.firstName); assertEquals("מדווח", saved?.lastName)
            assertEquals("synthetic-subscription-key", saved?.municipalSubscriptionKey)
            assertEquals("synthetic-api-user", saved?.municipalUsername)
            assertEquals("synthetic-api-pass", saved?.municipalPassword)
            assertTrue(saved?.hasMunicipalCredentials() == true)
        }
        compose.onNodeWithText("בדיקה").assertDoesNotExist()
        compose.onNodeWithText("synthetic-subscription-key").assertDoesNotExist()
        compose.onNodeWithText("synthetic-api-pass").assertDoesNotExist()
        // The unmasked username field is checked too; masked fields expose only bullets.
        compose.onNodeWithText("synthetic-api-user").assertDoesNotExist()
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
    @Test fun rtlReviewUsesHebrewCategoryAndReadableDate() {
        compose.setContent { AppTheme { ReviewScreen(draft(), null, {}, {}, topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        compose.onNodeWithText("חנייה אסורה - חנייה על מדרכה").assertExists()
        compose.onNodeWithText("sidewalk_parking").assertDoesNotExist()
        compose.onNodeWithText("זמן: \u206601/01/2026 14:00\u2069").assertDoesNotExist()
    }
    @Test fun offlineEncryptedDemoAndDurableDuplicateCheckpoint() {
        assertTrue(io.github.reihlinstinct.reporter.intake.SyntheticIntakeDemo.run().contains("הבדיקה הסינתטית הצליחה"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "synthetic-intake-journal"); directory.deleteRecursively()
        val upload = io.github.reihlinstinct.reporter.intake.SealedUpload("synthetic-upload", "synthetic-key", "synthetic-device", "a".repeat(64), byteArrayOf(1, 2, 3))
        var journal = io.github.reihlinstinct.reporter.intake.FileUploadJournal(directory)
        val checkpoint = journal.reserve(upload)
        journal.store(checkpoint.copy(phase = io.github.reihlinstinct.reporter.intake.UploadPhase.CREATING))
        journal = io.github.reihlinstinct.reporter.intake.FileUploadJournal(directory)
        var blocked = false
        try { journal.reserve(upload) } catch (_: Exception) { blocked = true }
        assertTrue(blocked)
        directory.deleteRecursively()
    }
    @Test fun outgoingDerivativePreservesOriginalAndStripsExif() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "synthetic-oriented.jpg")
        val bitmap = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.GREEN)
            val canvas = android.graphics.Canvas(this)
            val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLUE }
            canvas.drawRect(0f, 0f, 80f, 80f, paint)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        androidx.exifinterface.media.ExifInterface(file).apply {
            setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, "6")
            setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, "2026:01:01 12:00:00")
            setLatLong(31.0, 35.0); saveAttributes()
        }
        val original = file.readBytes()
        val output = io.github.reihlinstinct.reporter.evidence.OutgoingEvidence.prepare(file)
        assertArrayEquals(original, file.readBytes())
        assertEquals(80, output.width); assertEquals(160, output.height)
        // EXIF rotate90 must render the source's blue-left / green-right as
        // blue-top / green-bottom, not merely swap dimensions.
        val decoded = android.graphics.BitmapFactory.decodeByteArray(output.jpeg, 0, output.jpeg.size)
        val top = decoded.getPixel(40, 40)
        val bottom = decoded.getPixel(40, 120)
        assertTrue(android.graphics.Color.blue(top) > 200 && android.graphics.Color.green(top) < 50)
        assertTrue(android.graphics.Color.green(bottom) > 200 && android.graphics.Color.blue(bottom) < 50)
        decoded.recycle()
        File(context.filesDir, "outgoing-oriented-original.jpg").writeBytes(original)
        File(context.filesDir, "outgoing-oriented-derivative.jpg").writeBytes(output.jpeg)
        // CI exports PNG screenshots automatically; retain this exact JPEG's
        // decoded pixels for visual inspection too.
        val rendered = android.graphics.BitmapFactory.decodeByteArray(output.jpeg, 0, output.jpeg.size)
        File(context.filesDir, "outgoing-oriented-derivative.png").outputStream().use {
            rendered.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        rendered.recycle()
        val stripped = androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(output.jpeg))
        assertNull(stripped.latLong)
        assertNull(stripped.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals(io.github.reihlinstinct.reporter.intake.EncryptedPackage.sha256(original), output.originalHash)
        file.delete()
    }
    @Test fun debugOnlyDemoHiddenFromHomeAndRunsFromSettings() {
        compose.setContent { AppTheme {
            io.github.reihlinstinct.reporter.ui.AppShell(authenticate = { _, _, action -> action() }, requestCamera = {}, readSettings = { null }, saveSettings = {})
        } }
        compose.onNodeWithText("טיוטות וכלי בדיקה").assertDoesNotExist()
        compose.onNodeWithText("בדיקת פיתוח: חבילה מוצפנת ללא רשת").assertDoesNotExist()
        compose.onNodeWithText("הגדרות").performClick()
        compose.onNodeWithText("הגדרות פרטיות").performScrollTo().assertExists()
        compose.onNodeWithText("בדיקת פיתוח: חבילה מוצפנת ללא רשת").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("הבדיקה הסינתטית הצליחה", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Issue #295: save/debug feedback lives in the shell's shared status slot
        // above the bottom bar now, outside the settings scroll container, so it
        // is asserted, not scrolled to (a scroll would fail: no scrollable parent).
        compose.onNodeWithText("הבדיקה הסינתטית הצליחה", substring = true).assertExists()
        screenshot("m1-home-intake-synthetic.png")
    }
    @Test fun categoryPickerCurrentFourTopicsScreenshot() {
        compose.setContent { AppTheme { ReviewScreen(draft(), null, {}, {}, topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        // A popup is a separate root. Capture the full device to include both
        // the popup and underlying review, not an arbitrary Compose root.
        Thread.sleep(1500)
        compose.onNodeWithText("חנייה אסורה - חנייה על מדרכה").performClick()
        compose.onNodeWithTag("picker-search-input").performTextInput("17841")
        compose.onNodeWithText("תמרור תנועה נפל / דהוי / שבור / נוטה ליפול").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { image ->
            File(context.filesDir, "category-picker-open.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
        Thread.sleep(2000)
        compose.onNodeWithText("תמרור תנועה נפל / דהוי / שבור / נוטה ליפול").performClick()
        compose.onNodeWithText("תמרור תנועה נפל / דהוי / שבור / נוטה ליפול").assertIsDisplayed()
        screenshot("category-picker-chosen.png")
        Thread.sleep(1500)
    }
    @Test fun nonVehicleReviewHasNoPlateAndCorrectionResetsText() {
        var saved: ReviewDraft? = null
        val bitmap = Bitmap.createBitmap(700, 300, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(235, 235, 235))
        canvas.drawRect(325f, 120f, 350f, 290f, android.graphics.Paint().apply { color = android.graphics.Color.DKGRAY })
        canvas.drawRect(230f, 20f, 455f, 125f, android.graphics.Paint().apply { color = android.graphics.Color.WHITE })
        canvas.drawText("SYNTHETIC SIGN", 248f, 75f, android.graphics.Paint().apply { color = android.graphics.Color.BLACK; textSize = 22f })
        compose.setContent { AppTheme { ReviewScreen(draft(), bitmap.asImageBitmap(), { saved = it }, {}, topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        compose.onNodeWithText("חנייה אסורה - חנייה על מדרכה").performClick()
        compose.onNodeWithTag("picker-search-input").performTextInput("17841")
        compose.onNodeWithText("תמרור תנועה נפל / דהוי / שבור / נוטה ליפול").performClick()
        compose.onNodeWithText("לוחית:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("רכב חונה על המדרכה").assertDoesNotExist()
        screenshot("design-sign-synthetic.png")
        compose.onNodeWithText("שלח").performScrollTo()
        screenshot("design-sign-actions-synthetic.png")
        compose.onNodeWithText("שמור טיוטה").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("", saved?.plate); assertFalse(saved?.isReady() == true) }
        compose.onNodeWithText("ערוך").performScrollTo().performClick()
        compose.onNodeWithText("מספר לוחית (ספרות בלבד)").assertDoesNotExist()
    }
    @Test fun photoFirstHomeScreenshot() {
        compose.setContent { AppTheme {
            io.github.reihlinstinct.reporter.ui.AppShell(authenticate = { _, _, action -> action() }, requestCamera = {}, readSettings = { null }, saveSettings = {})
        } }
        compose.onNodeWithText("צלם תמונה").assertExists()
        compose.onNodeWithText("שם פרטי").assertDoesNotExist()
        compose.onNodeWithText("בית").assertExists()
        compose.onNodeWithText("הגדרות").assertExists()
        compose.onNodeWithText("בדיקת פיתוח: חבילה מוצפנת ללא רשת").assertDoesNotExist()
        // Same row, separate click targets, equal heights, and no stale instructions.
        compose.onNodeWithText("העלה תמונה").assertExists()
        val camera = compose.onNodeWithText("צלם תמונה").fetchSemanticsNode().boundsInRoot
        val gallery = compose.onNodeWithText("העלה תמונה").fetchSemanticsNode().boundsInRoot
        assertTrue("Photo actions share a row", kotlin.math.abs(camera.top - gallery.top) < 2f)
        assertTrue("Photo actions do not overlap", camera.right <= gallery.left || gallery.right <= camera.left)
        compose.onNodeWithText("העלאה מהגלריה אינה דורשת הרשאות", substring = true).assertDoesNotExist()
        compose.onNodeWithText("ביטול הרשאת מצלמה או אימות", substring = true).assertDoesNotExist()
        screenshot("design-home-synthetic.png")
        // Issues #304/#306: at 200% text on a compact viewport the actions
        // may begin below the fold. Prove the existing scroll container can
        // reveal each complete button instead of treating a viewport crop as
        // an unreachable action. Full visible bounds are stronger than exists.
        for ((label, file) in listOf(
            "צלם תמונה" to "design-home-camera-scrolled.png",
            "העלה תמונה" to "design-home-gallery-scrolled.png",
        )) {
            val action = compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            val bounds = action.fetchSemanticsNode().boundsInRoot
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("$label must be fully inside viewport: $bounds vs $root",
                bounds.top >= root.top && bounds.bottom <= root.bottom &&
                    bounds.left >= root.left && bounds.right <= root.right)
            screenshot(file)
        }
    }
    @Test fun originalEvidenceHashAndMissingMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "synthetic-evidence.jpg")
        file.writeText("synthetic original, not a JPEG")
        val reader = io.github.reihlinstinct.reporter.evidence.EvidenceReader()
        val a = reader.read(file, "synthetic")
        val b = reader.read(file, "synthetic")
        assertEquals(a, b); assertNull(a.capturedAtIso); assertNull(a.latitude)
        file.appendText("changed")
        assertNotEquals(a.originalSha256, reader.read(file, "synthetic").originalSha256)
        file.delete()
    }

    @Test fun settingsSaveDisabledWhenAllBlankAndAfterSave() {
        var saved: ReporterSettings? = null
        compose.setContent { AppTheme { SettingsScreen(null, { s, _, done -> saved = s; done() }, {}) } }
        compose.onNodeWithText("אמת זהות ושמור מוצפן").assertIsNotEnabled()
        compose.onNodeWithText("שם פרטי").performTextInput("בדיקה")
        // Issue #295 (owner 2026-11-11): one field is not enough - the button
        // stays greyed, and the reason next to it names the missing fields.
        compose.onNodeWithText("אמת זהות ושמור מוצפן").performScrollTo().assertIsNotEnabled()
        compose.onNode(hasText("חסרים או לא תקינים", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("שם משפחה").performTextInput("מדווח")
        compose.onNodeWithText("מספר זהות").performScrollTo().performTextReplacement("000000018")
        compose.onNodeWithText("טלפון").performScrollTo().performTextReplacement("050-123-4567")
        compose.onNodeWithText("אימייל").performScrollTo().performTextReplacement("synthetic@example.com")
        compose.onNodeWithText("אמת זהות ושמור מוצפן").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("בדיקה", saved?.firstName) }
        compose.onNodeWithText("אמת זהות ושמור מוצפן").performScrollTo().assertIsNotEnabled()
    }
    @Test fun homeShowsTwoCaptureButtonsAndReportsNav() {
        compose.setContent { AppTheme {
            io.github.reihlinstinct.reporter.ui.AppShell(authenticate = { _, _, action -> action() }, requestCamera = {}, readSettings = { null }, saveSettings = {})
        } }
        compose.onNodeWithText("צלם תמונה").assertExists()
        compose.onNodeWithText("העלה תמונה").assertExists()
        compose.onNodeWithText("הדיווחים שלי").assertExists()
        compose.onNodeWithText("הדיווחים שלי").performClick()
        compose.onNodeWithText("טיוטות").assertExists()
        // Shared test database may hold another test draft; navigation must not
        // assume an empty ledger or delete unrelated rows to make it empty.
        compose.onNodeWithText("מוכנים לשליחה").assertExists()
        compose.onNodeWithText("היסטוריית דיווחים").assertExists()
        compose.onNodeWithText("בית").performClick()
        compose.onNodeWithText("צלם תמונה").assertExists()
    }
    @Test fun reportsScreenSeededDraftOpens() {
        val records = listOf(
            ReportRecord("aaaabbbb", "a".repeat(64), "originals/a.jpg", 0, "{}", null),
            ReportRecord("ccccdddd", "b".repeat(64), "originals/c.jpg", 0, "{}", "digest"),
        )
        var opened: String? = null
        compose.setContent { AppTheme { io.github.reihlinstinct.reporter.ui.ReportsScreen(records, { opened = it.stableId }) } }
        // The id is a catalog value now, isolated LRI..PDI in Hebrew (#115 PR-2b).
        compose.onNodeWithText("פתח טיוטה \u2066aaaabbbb\u2069").performClick()
        compose.runOnIdle { assertEquals("aaaabbbb", opened) }
        compose.onNodeWithText("פתח דיווח \u2066ccccdddd\u2069").assertExists()
    }
    @Test fun galleryImportPreservesBytesAndExifMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val src = File(context.cacheDir, "synthetic-pick.jpg")
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        src.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        val exif = androidx.exifinterface.media.ExifInterface(src)
        exif.setLatLong(31.77, 35.21)
        exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, "2026:01:01 12:00:00")
        exif.saveAttributes()
        val srcBytes = src.readBytes()
        val originals = File(context.noBackupFilesDir, "test-originals")
        val copied = io.github.reihlinstinct.reporter.evidence.GalleryImport.copyToOriginals({ src.inputStream() }, originals)
        assertArrayEquals(srcBytes, copied.readBytes())
        val evidence = io.github.reihlinstinct.reporter.evidence.EvidenceReader().read(copied, "gallery")
        assertNotNull(evidence.capturedAtIso)
        assertEquals(31.77, evidence.latitude!!, 0.001)
        assertEquals(35.21, evidence.longitude!!, 0.001)
        copied.delete(); src.delete()
    }
}
