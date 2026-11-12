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
    private fun draft(plate: String = "1234567") = ReviewDraft(Evidence("registry-synthetic", "a".repeat(64), null, 31.0, 35.0),
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
        compose.setContent { AppTheme { RegistryStatus("1234567", remember(remaining) { { p, emit -> lookup(p, emit) } }) } }
        compose.onNodeWithText("רישיון הרכב יפוג בעוד", substring = true).assertExists()
        screenshot("registry-orange-13-days.png")
        compose.runOnIdle { remaining = 14L }; compose.waitForIdle()
        compose.onNodeWithText("רישיון הרכב יפוג בעוד", substring = true).assertDoesNotExist()
        compose.onNodeWithText("רישיון הרכב בתוקף לפי המאגר").assertExists()
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

}
