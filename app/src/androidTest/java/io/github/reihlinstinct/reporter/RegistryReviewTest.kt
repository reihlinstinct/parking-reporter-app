package io.github.reihlinstinct.reporter

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.core.*
import io.github.reihlinstinct.reporter.registry.*
import io.github.reihlinstinct.reporter.ui.*
import io.github.reihlinstinct.reporter.ui.theme.AppTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate

class RegistryReviewTest {
    @get:Rule val compose = createComposeRule()
    @Before fun language() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        io.github.reihlinstinct.reporter.i18n.UiPrefsLanguageStore.of(context).set(io.github.reihlinstinct.reporter.i18n.AppCatalog.defaultCode(InstrumentationRegistry.getInstrumentation().targetContext))
    }
    private fun draft(plate: String = "1234567") = ReviewDraft(Evidence("registry-synthetic", "a".repeat(64), null, 31.77, 35.21),
        plate, "רחוב בדיקה 12", "municipal:1145", "רכב $plate חונה על המדרכה", emptySet())
    private fun snapshot(type: DisabilityTagType) = RegistrySnapshot(DisabilityLookupResult.Found(type),
        VehicleLookupResult.Found(VehicleRecord(PlateNumber.parse("1234567")!!, LocalDate.of(2020, 1, 1), null, null, null, null, null)),
        Instant.parse("2026-11-12T05:00:00Z"))
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, name).outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun slowLookupDoesNotGateSaveOrSendAndDoesNotChangeOutgoingText() {
        var saved: ReviewDraft? = null; var sent: ReviewDraft? = null
        val lookup: RegistryLookup = { _, _ -> awaitCancellation() }
        compose.setContent { AppTheme { ReviewScreen(draft(), null, { saved = it }, {}, onSubmit = { sent = it },
            topicSnapshot = syntheticCityTopics(), topicsAvailable = true, registryLookup = lookup) } }
        compose.onNodeWithText("שמור טיוטה").performScrollTo().performClick()
        compose.onNodeWithText("שלח לעירייה").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(draft().exactHebrewText, saved!!.exactHebrewText)
            assertEquals(draft().exactHebrewText, sent!!.exactHebrewText)
        }
        screenshot("registry-slow-send-still-ready.png")
    }
    @Test fun plateMustBeVisibleInDescriptionBeforeSendAndInsertionIsExplicit() {
        compose.setContent { AppTheme { ReviewScreen(draft().copy(exactHebrewText = "רכב חונה"), null, {}, {}, onSubmit = {},
            topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        compose.onNodeWithText("שלח לעירייה").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("הוסף את המספר לתיאור המוצג").performScrollTo().performClick()
        compose.onNodeWithText("רכב חונה", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("שלח לעירייה").performScrollTo().assertIsEnabled()
        screenshot("registry-plate-text-inserted.png")
    }
    @Test fun knownTypeAndLicenceBelowPlateAndNoRegistryForIrrelevantTopic() {
        var initial by mutableStateOf(draft())
        val lookup: RegistryLookup = { _, _ -> snapshot(DisabilityTagType.TRIANGLE) }
        compose.setContent { AppTheme { ReviewScreen(initial, null, {}, {}, onSubmit = {}, topicSnapshot = syntheticCityTopics(), topicsAvailable = true, registryLookup = lookup) } }
        compose.onNodeWithText("רישיון הרכב פג תוקף לפי המאגר").assertExists()
        screenshot("registry-triangle-expired-light.png")
        compose.runOnIdle { initial = draft().copy(subject = "municipal:1280", plate = "", exactHebrewText = "בקשת עמודים") }
        compose.waitForIdle()
        compose.onNodeWithText("רישיון הרכב פג תוקף לפי המאגר").assertDoesNotExist()
    }
    @Test fun stalePlateCallbackCannotShowOldType() {
        var initial by mutableStateOf(draft())
        val lookup: RegistryLookup = { plate, _ ->
            if (plate == "1234567") { withContext(NonCancellable) { delay(500) }; snapshot(DisabilityTagType.TRIANGLE) }
            else snapshot(DisabilityTagType.WHEELCHAIR)
        }
        compose.setContent { AppTheme(darkTheme = true) { Surface(Modifier.fillMaxSize()) { ReviewScreen(initial, null, {}, {}, topicSnapshot = syntheticCityTopics(), topicsAvailable = true, registryLookup = lookup) } } }
        compose.runOnIdle { initial = draft("7654321") }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNodeWithText("רישיון הרכב פג תוקף לפי המאגר").assertExists()
        compose.onNodeWithText("תג חניה לנכה בכיסא גלגלים").assertExists()
        compose.onNodeWithText("תג חניה לנכה, סמל משולש").assertDoesNotExist()
        screenshot("registry-wheelchair-dark-stale-guard.png")
    }
    @Test fun completedTagVisibleWhileLicenceWaitsAndSendNeverWaits() {
        val licence = CompletableDeferred<VehicleLookupResult>()
        val lookup: RegistryLookup = { plate, publish -> readRegistrySnapshot(plate,
            { DisabilityLookupResult.Found(DisabilityTagType.TRIANGLE) },
            { licence.await() }, publish) }
        compose.setContent { AppTheme { ReviewScreen(draft(), null, {}, {}, onSubmit = {},
            topicSnapshot = syntheticCityTopics(), topicsAvailable = true, registryLookup = lookup) } }
        compose.onNodeWithText("תג חניה לנכה, סמל משולש").assertExists()
        compose.onNodeWithText("שלח לעירייה").performScrollTo().assertIsEnabled()
        compose.runOnIdle { licence.complete(snapshot(DisabilityTagType.TRIANGLE).vehicle!!) }
        compose.waitForIdle()
        compose.onNodeWithText("רישיון הרכב פג תוקף לפי המאגר").assertExists()
        screenshot("registry-partial-tag-then-licence.png")
    }

    /** #397: controlled vehicle-first completion; no registry network request. */
    @Test fun vehicleFirstShowsPendingTagLiveRegionThenReplacesIt() {
        val tag = CompletableDeferred<DisabilityLookupResult>()
        val lookup: RegistryLookup = { plate, publish -> readRegistrySnapshot(plate,
            { tag.await() }, { snapshot(DisabilityTagType.TRIANGLE).vehicle!! }, publish) }
        compose.setContent { AppTheme { Surface(Modifier.fillMaxSize()) {
            RegistryStatus("1234567", lookup)
        } } }
        val pending = "בודק תג חניה לנכה..."
        compose.onNodeWithText(pending).assertIsDisplayed()
        compose.onNodeWithText("רישיון הרכב פג תוקף לפי המאגר").assertExists()
        compose.onAllNodes(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.LiveRegion,
            androidx.compose.ui.semantics.LiveRegionMode.Polite)).assertCountEquals(1)
        screenshot("registry-vehicle-first-tag-pending.png")
        compose.runOnIdle { tag.complete(DisabilityLookupResult.Found(DisabilityTagType.TRIANGLE)) }
        compose.waitForIdle()
        compose.onNodeWithText(pending).assertDoesNotExist()
        compose.onNodeWithText("תג חניה לנכה, סמל משולש").assertIsDisplayed()
        screenshot("registry-vehicle-first-tag-completed.png")
        // Semantics proof only. Actual TalkBack utterance count is still unverified.
    }

    /** The standalone status case above does not prove its consuming review route. */
    @Test fun pendingTagIsExposedInsideReviewWithoutChangingTheReport() {
        // Use a valid licence: an expired fixture legitimately adds a template sentence.
        val tag = CompletableDeferred<DisabilityLookupResult>()
        val initial = draft()
        var saved: ReviewDraft? = null
        var submissions = 0
        val lookup: RegistryLookup = { plate, publish -> readRegistrySnapshot(plate,
            { tag.await() }, { VehicleLookupResult.Found(VehicleRecord(PlateNumber.parse("1234567")!!,
                LocalDate.of(2099, 1, 1), null, null, null, null, null)) }, publish) }
        compose.setContent { AppTheme { ReviewScreen(initial, null, { saved = it }, {},
            onSubmit = { submissions++ }, topicSnapshot = syntheticCityTopics(),
            topicsAvailable = true, registryLookup = lookup) } }
        val pending = "בודק תג חניה לנכה..."
        compose.onNodeWithText(pending).performScrollTo().assertIsDisplayed()
        val polite = SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.LiveRegion,
            androidx.compose.ui.semantics.LiveRegionMode.Polite)
        compose.onAllNodes(polite and hasAnyDescendant(hasText(pending))).assertCountEquals(1)
        compose.onNodeWithText("רישיון הרכב בתוקף לפי המאגר").assertExists()
        screenshot("registry-review-tag-pending.png")
        compose.runOnIdle { tag.complete(DisabilityLookupResult.Found(DisabilityTagType.TRIANGLE)) }
        compose.onNodeWithText("תג חניה לנכה, סמל משולש").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(pending).assertDoesNotExist()
        screenshot("registry-review-tag-completed.png")
        compose.onNodeWithText("שמור טיוטה").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(initial.exactHebrewText, saved!!.exactHebrewText)
            assertEquals(initial.plate, saved!!.plate)
            assertEquals(0, submissions)
        }
        // A live-region node is not proof of TalkBack utterance count.
    }

    @Test fun absentTagIsSilentButExpiredLicenceRemainsVisible() {
        val lookup: RegistryLookup = { _, _ -> RegistrySnapshot(DisabilityLookupResult.NotFound,
            snapshot(DisabilityTagType.TRIANGLE).vehicle, Instant.now()) }
        compose.setContent { AppTheme { ReviewScreen(draft(), null, {}, {}, topicSnapshot = syntheticCityTopics(),
            topicsAvailable = true, registryLookup = lookup) } }
        compose.onNodeWithText("לא נמצא רישום תג במאגר").assertDoesNotExist()
        compose.onNodeWithText("רישיון הרכב פג תוקף לפי המאגר").assertExists()
        screenshot("registry-no-tag-expired-light.png")
    }

    @Test fun nearExpiryOrangeShowsDaysButExactly14DaysHasNormalStatus() {
        var remaining by mutableStateOf(13L)
        val checked = Instant.parse("2026-11-12T22:30:00Z")
        val lookup: RegistryLookup = { _, _ -> RegistrySnapshot(DisabilityLookupResult.NotFound,
            VehicleLookupResult.Found((snapshot(DisabilityTagType.TRIANGLE).vehicle as VehicleLookupResult.Found).record.copy(
                licenceValidUntil = java.time.LocalDate.of(2026, 11, 13).plusDays(remaining))), checked) }
        compose.setContent { AppTheme { Surface(Modifier.fillMaxSize()) { RegistryStatus("1234567", remember(remaining) { { p, emit -> lookup(p, emit) } }) } } }
        compose.onNodeWithText("רישיון הרכב יפוג בעוד", substring = true).assertExists()
        screenshot("registry-orange-13-days.png")
        compose.runOnIdle { remaining = 14L }; compose.waitForIdle()
        compose.onNodeWithText("רישיון הרכב יפוג בעוד", substring = true).assertDoesNotExist()
        compose.onNodeWithText("רישיון הרכב בתוקף לפי המאגר").assertExists()
        compose.runOnIdle { remaining = 0L }; compose.waitForIdle()
        compose.onNodeWithText("רישיון הרכב יפוג בעוד \u20660\u2069 ימים").assertExists()
        compose.onNodeWithText("רישיון הרכב פג תוקף לפי המאגר").assertDoesNotExist()
        screenshot("registry-orange-today-0-days.png")
        compose.runOnIdle { remaining = -1L }; compose.waitForIdle()
        compose.onNodeWithText("רישיון הרכב פג תוקף לפי המאגר").assertExists()
        compose.onNodeWithText("רישיון הרכב יפוג בעוד", substring = true).assertDoesNotExist()
    }

    @Test fun orangeNearExpiryIsReadableOnDarkSurface() {
        val lookup: RegistryLookup = { _, _ -> RegistrySnapshot(DisabilityLookupResult.NotFound,
            VehicleLookupResult.Found((snapshot(DisabilityTagType.TRIANGLE).vehicle as VehicleLookupResult.Found).record.copy(
                licenceValidUntil = LocalDate.of(2026, 11, 26))), Instant.parse("2026-11-12T22:30:00Z")) }
        compose.setContent { AppTheme(darkTheme = true) { Surface(Modifier.fillMaxSize()) { RegistryStatus("1234567", lookup) } } }
        compose.onNodeWithText("רישיון הרכב יפוג בעוד \u206613\u2069 ימים").assertExists()
        screenshot("registry-orange-13-days-dark.png")
    }

    @Test fun noVehicleRecordAndUnknownLicenceStayDistinctFromExpired() {
        var vehicle by mutableStateOf<VehicleLookupResult>(VehicleLookupResult.NotFound)
        compose.setContent { AppTheme { RegistryStatus("1234567", remember(vehicle) { { _, _ ->
            RegistrySnapshot(DisabilityLookupResult.NotFound, vehicle, Instant.now())
        } }) } }
        compose.onNodeWithText("רישיון הרכב פג תוקף לפי המאגר").assertDoesNotExist()
        compose.onNodeWithText("רישיון הרכב יפוג בעוד", substring = true).assertDoesNotExist()
        compose.onNodeWithText("לא נמצא רישום רכב במאגר").assertExists()
        screenshot("registry-no-vehicle-record.png")
        compose.runOnIdle { vehicle = VehicleLookupResult.Found((snapshot(DisabilityTagType.TRIANGLE).vehicle as VehicleLookupResult.Found).record.copy(licenceValidUntil = null)) }
        compose.waitForIdle()
        compose.onNodeWithText("רישיון הרכב פג תוקף לפי המאגר").assertDoesNotExist()
        compose.onNodeWithText("תוקף רישיון הרכב לא ידוע").assertExists()
        compose.onNodeWithText("לא נמצא רישום רכב במאגר").assertDoesNotExist()
        screenshot("registry-unknown-licence.png")
    }

    @Test fun cacheIsImmediateLabelledFallbackButAlwaysRequestsAndReplacesWithLive() {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val checked = Instant.now().minusSeconds(3600)
        val saved = RegistryFallbackCache.Cached(DisabilityLookupResult.Found(DisabilityTagType.TRIANGLE),
            VehicleLookupResult.Found((snapshot(DisabilityTagType.TRIANGLE).vehicle as VehicleLookupResult.Found).record.copy(
                licenceValidUntil = LocalDate.now(java.time.ZoneId.of("Asia/Jerusalem")).plusDays(30))), checked, checked)
        val lookup: RegistryLookup = { plate, emit -> cachedRegistryLookup(plate, saved,
            { _, _ -> calls++; gate.await(); snapshot(DisabilityTagType.WHEELCHAIR) }, {}, emit) }
        compose.setContent { AppTheme { Surface(Modifier.fillMaxSize()) { RegistryStatus("1234567", lookup) } } }
        compose.onNodeWithText("מידע שמור מלפני", substring = true).assertExists()
        compose.onNodeWithText("תג חניה לנכה, סמל משולש").assertExists()
        compose.runOnIdle { assertEquals(1, calls) }
        screenshot("registry-cache-immediate-labelled.png")
        gate.complete(Unit); compose.waitForIdle()
        compose.onNodeWithText("תג חניה לנכה בכיסא גלגלים").assertExists()
        compose.onNodeWithText("מידע שמור מלפני", substring = true).assertDoesNotExist()
        screenshot("registry-cache-replaced-live.png")
    }

    @Test fun liveFailureKeepsLabelledFallbackWithoutWritingErrors() {
        var writes = 0; var calls = 0
        val saved = RegistryFallbackCache.Cached(DisabilityLookupResult.Found(DisabilityTagType.TRIANGLE),
            VehicleLookupResult.NotFound, Instant.now().minusSeconds(120), Instant.now().minusSeconds(120))
        val lookup: RegistryLookup = { plate, emit -> cachedRegistryLookup(plate, saved,
            { _, _ -> calls++; throw java.io.IOException("synthetic offline") }, { writes++ }, emit) }
        compose.setContent { AppTheme { Surface(Modifier.fillMaxSize()) { RegistryStatus("1234567", lookup) } } }
        compose.onNodeWithText("מידע שמור מלפני", substring = true).assertExists()
        compose.onNodeWithText("תג חניה לנכה, סמל משולש").assertExists()
        compose.runOnIdle { assertEquals(1, calls); assertEquals(0, writes) }
        screenshot("registry-cache-offline-fallback.png")
    }

    /** Recorded public registry data from2026-11-12, NOT a live network test. No owner fields. */
    @Test fun recordedInactiveNissanShowsRedExpiredWithoutTag() {
        val lookup: RegistryLookup = { _, _ -> RegistrySnapshot(DisabilityLookupResult.NotFound,
            VehicleLookupResult.Found(VehicleRecord(PlateNumber.parse("1912929")!!, LocalDate.of(2024,5,27),
                LocalDate.of(2023,11,22), null, "ALMERA", null, 1998)), Instant.parse("2026-11-12T09:46:00Z")) }
        compose.setContent { AppTheme { Surface(Modifier.fillMaxSize()) { RegistryStatus("1912929", lookup) } } }
        compose.onNodeWithText("רישיון הרכב פג תוקף לפי המאגר").assertExists()
        compose.onNodeWithText("לא נמצא רישום תג במאגר").assertDoesNotExist()
        screenshot("registry-recorded-inactive-1912929-expired.png")
    }

    @Test fun tagErrorIsUnavailableNotSilentNegative() {
        val lookup: RegistryLookup = { _, _ -> RegistrySnapshot(DisabilityLookupResult.Error(LookupErrorKind.NETWORK),
            VehicleLookupResult.Found((snapshot(DisabilityTagType.TRIANGLE).vehicle as VehicleLookupResult.Found).record.copy(
                licenceValidUntil = LocalDate.of(2030,1,1))), Instant.parse("2026-11-12T09:00:00Z")) }
        compose.setContent { AppTheme { Surface(Modifier.fillMaxSize()) { RegistryStatus("1234567", lookup) } } }
        compose.onNodeWithText("המאגר לא זמין").assertExists()
        compose.onNodeWithText("לא נמצא רישום תג במאגר").assertDoesNotExist()
        compose.onNodeWithText("רישיון הרכב בתוקף לפי המאגר").assertExists()
        screenshot("registry-tag-unavailable-valid-licence.png")
    }

    // Issue #403: the inactive-record line shows only for a record from the inactive resource, in he and en. Test-only, no wording change.
    private fun inactiveLookup(inactive: Boolean): RegistryLookup = { _, _ -> RegistrySnapshot(DisabilityLookupResult.NotFound,
        VehicleLookupResult.Found(VehicleRecord(PlateNumber.parse("1234567")!!, LocalDate.of(2030, 1, 1), null, null, null, null, null, inactive = inactive)),
        Instant.parse("2026-11-12T09:00:00Z")) }

    @Test fun inactiveRecordLineShowsInHebrew() {
        compose.setContent { AppTheme { Surface(Modifier.fillMaxSize()) { RegistryStatus("1234567", inactiveLookup(true)) } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("רישום הרכב במאגר אינו פעיל", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("רישום הרכב במאגר אינו פעיל", substring = true).assertExists()
    }

    @Test fun activeRecordHasNoInactiveLineInHebrew() {
        compose.setContent { AppTheme { Surface(Modifier.fillMaxSize()) { RegistryStatus("1234567", inactiveLookup(false)) } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("רישיון הרכב בתוקף לפי המאגר").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("רישום הרכב במאגר אינו פעיל", substring = true).assertDoesNotExist()
    }

    @Test fun inactiveRecordLineShowsInAnotherLanguageOnlyWhenInactive() {
        // A non-default catalog language taken from the catalog (no hardcoded code), supplied directly to the screen.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = io.github.reihlinstinct.reporter.i18n.AppCatalog.get(context)
        val other = catalog.languages.first { it.code != io.github.reihlinstinct.reporter.i18n.AppCatalog.defaultCode(context) }.code
        val strings = io.github.reihlinstinct.reporter.i18n.Strings(catalog, other)
        val line = strings.t("registry.licence.inactive_record")
        assertNotEquals("registry.licence.inactive_record", line)
        var inactive by mutableStateOf(true)
        compose.setContent { AppTheme { androidx.compose.runtime.CompositionLocalProvider(io.github.reihlinstinct.reporter.i18n.LocalStrings provides strings) {
            Surface(Modifier.fillMaxSize()) { RegistryStatus("1234567", remember(inactive) { inactiveLookup(inactive) }) } } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(line, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { inactive = false }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(line, substring = true).fetchSemanticsNodes().isEmpty() }
    }

}
