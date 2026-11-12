package io.github.reihlinstinct.reporter

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.core.Evidence
import io.github.reihlinstinct.reporter.core.ReviewDraft
import io.github.reihlinstinct.reporter.evidence.AddressSuggestion
import io.github.reihlinstinct.reporter.ui.ReviewScreen
import io.github.reihlinstinct.reporter.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReviewAddressTest {
    @get:Rule val compose = createComposeRule()
    private fun draft() = ReviewDraft(
        Evidence("address-fixture", "a".repeat(64), "2026-01-01T12:00:00+02:00", 31.77, 35.21),
        "1234567", "", "sidewalk_parking", "תיאור בדיקה בלבד", setOf("address_needs_confirmation"))

    // Issue #256: the Done key on the last single-line field clears focus and hides the keyboard.
    @Test fun imeDoneOnHouseNumberClearsFocus() {
        val initial = draft().copy(evidence = draft().evidence.copy(latitude = null, longitude = null),
            address = "", confirmedLatitude = null, confirmedLongitude = null, unresolved = setOf("missing_gps", "address_needs_confirmation"))
        compose.setContent { AppTheme { ReviewScreen(initial, null, {}, {}, topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        compose.onNodeWithText("מספר בית").performScrollTo().performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("מספר בית").assertIsFocused() }.isSuccess }
        compose.onNodeWithText("מספר בית").performImeAction()
        compose.waitForIdle()
        compose.onNodeWithText("מספר בית").assertIsNotFocused()
    }

    @Test fun noGpsAddressRequiresExplicitConfirmationAndPreservesOriginal() {
        val initial = draft().copy(evidence = draft().evidence.copy(latitude = null, longitude = null),
            address = "", confirmedLatitude = null, confirmedLongitude = null, unresolved = setOf("missing_gps", "address_needs_confirmation"))
        var saved: ReviewDraft? = null
        compose.setContent { AppTheme { ReviewScreen(initial, null, { saved = it }, {}, manualAddressLookup = { street, house ->
            assertEquals("רחוב בדיקה", street); assertEquals("12", house)
            io.github.reihlinstinct.reporter.municipal.StreetDirectory.ConfirmableAddress("רחוב בדיקה 12", 31.77, 35.21)
        }, topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        compose.onNodeWithText("שלח").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("רחוב בירושלים").performScrollTo().performTextReplacement("רחוב בדיקה")
        compose.onNodeWithText("מספר בית").performScrollTo().performTextReplacement("12")
        compose.onNodeWithText("חפש כתובת מדויקת").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("אשר את כתובת האירוע").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("שלח").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("אשר את כתובת האירוע").performScrollTo()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "no-gps-address-confirm-synthetic.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("אשר את כתובת האירוע").performClick()
        compose.onNodeWithText("ערוך").performScrollTo().performClick()
        compose.onNodeWithText("חפש הצעות כתובת לפי המיקום").assertDoesNotExist()
        compose.onNodeWithText("רחוב ומספר בית שאישרת").performScrollTo().performTextReplacement("רחוב בדיקה 14")
        compose.onNodeWithText("שלח").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("שמור טיוטה").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(initial.evidence, saved?.evidence)
            assertEquals("רחוב בדיקה 14", saved?.address)
            assertNull(saved?.confirmedLatitude)
            assertNull(saved?.confirmedLongitude)
        }
    }

    @Test fun timeControlsAreAbsentAndOriginalMetadataIsKept() {
        val initial = draft().copy(address = "רחוב בדיקה 12", evidence = draft().evidence.copy(capturedAtIso = "2026-01-01T12:00:47+02:00"), confirmedTime = "2026-01-01T12:00:47+02:00")
        var saved: ReviewDraft? = null
        compose.setContent { AppTheme { ReviewScreen(initial, null, { saved = it }, {}, addressLookup = { _, _ -> emptyList() }, topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        compose.onNodeWithText("זמן: \u206601/01/2026 12:00\u2069").assertDoesNotExist()
        compose.onNodeWithText("כל שינוי מבטל אישור", substring = true).assertDoesNotExist()
        compose.onNodeWithText("ערוך").performScrollTo().performClick()
        compose.onNodeWithText("הזמן מוצג לפי השעון המקומי בירושלים.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("בדוק את הרחוב ומספר הבית", substring = true).assertDoesNotExist()
        compose.onNodeWithText("זמן הצילום (24 שעות)").assertDoesNotExist()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "review-local-time-synthetic.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("שמור טיוטה").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(initial.evidence, saved?.evidence); assertEquals(initial.confirmedTime, saved?.confirmedTime) }
    }

    @Test fun editingTimeShowsNoUnlabelledExampleAndPreservesActualTime() {
        val actual = "2026-11-12T05:30:41+02:00"
        val initial = draft().copy(address = "רחוב בדיקה 12", confirmedTime = actual,
            evidence = draft().evidence.copy(capturedAtIso = actual))
        var saved: ReviewDraft? = null
        compose.setContent { AppTheme { ReviewScreen(initial, null, { saved = it }, {},
            topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        compose.onNodeWithText("ערוך").performScrollTo().performClick()
        compose.onNodeWithText("זמן הצילום (24 שעות)").assertDoesNotExist()
        compose.onNodeWithText("01/01/2026 12:00", substring = true).assertDoesNotExist()
        compose.onNodeWithText("12/11/2026 05:30").assertDoesNotExist()
        compose.onNodeWithText("שמור טיוטה").performScrollTo()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "review-no-stray-time-example.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("שמור טיוטה").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(actual, saved?.confirmedTime)
            assertEquals(initial.evidence, saved?.evidence)
        }
    }

    @Test fun plateEntryFormatsDigitsButStoresNoDashes() {
        val initial = draft().copy(address = "רחוב בדיקה 12")
        var saved: ReviewDraft? = null
        compose.setContent { AppTheme { ReviewScreen(initial, null, { saved = it }, {}, topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        compose.onNodeWithText("ערוך").performScrollTo().performClick()
        compose.onNodeWithText("מספר לוחית (ספרות בלבד)").performScrollTo().performTextReplacement("123-45-678")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "review-formatted-plate-synthetic.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("שמור טיוטה").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("12345678", saved?.plate) }
    }

    @Test fun unambiguousOcrPrefillsWithoutSuggestionTap() {
        val initial = draft().copy(address = "רחוב בדיקה 12", plate = "")
        var saved: ReviewDraft? = null
        val bitmap = android.graphics.Bitmap.createBitmap(20, 20, android.graphics.Bitmap.Config.ARGB_8888)
        compose.setContent { AppTheme { ReviewScreen(initial, bitmap.asImageBitmap(), { saved = it }, {}, plateRecognizer = { listOf("1234567") }, topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("לוחית: \u206612-345-67\u2069").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("שמור טיוטה").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("1234567", saved?.plate) }
    }

    // Issue #358: the automatic prefill alone must not trigger the unsaved-edits dialog.
    @Test fun ocrPrefillAloneIsNotAnUnsavedEdit() {
        val initial = draft().copy(address = "רחוב בדיקה 12", plate = "")
        var exits = 0
        val bitmap = android.graphics.Bitmap.createBitmap(20, 20, android.graphics.Bitmap.Config.ARGB_8888)
        compose.setContent { AppTheme { ReviewScreen(initial, bitmap.asImageBitmap(), {}, { exits++ }, plateRecognizer = { listOf("1234567") }, topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("לוחית: \u206612-345-67\u2069").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("חזור").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, exits) }
        compose.onNodeWithText("יש שינויים שלא נשמרו").assertDoesNotExist()
    }

    // Issue #358: a real edit after the prefill still asks.
    @Test fun editAfterOcrPrefillStillAsksBeforeLeaving() {
        val initial = draft().copy(address = "רחוב בדיקה 12", plate = "")
        var exits = 0
        val bitmap = android.graphics.Bitmap.createBitmap(20, 20, android.graphics.Bitmap.Config.ARGB_8888)
        compose.setContent { AppTheme { ReviewScreen(initial, bitmap.asImageBitmap(), {}, { exits++ }, plateRecognizer = { listOf("1234567") }, topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("לוחית: \u206612-345-67\u2069").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("ערוך").performScrollTo().performClick()
        compose.onNodeWithText("רחוב ומספר בית שאישרת").performScrollTo().performTextReplacement("רחוב בדיקה 14")
        compose.onNodeWithText("חזור").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("יש שינויים שלא נשמרו").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(0, exits) }
    }

    @Test fun gpsSuggestsStreetHouseButNeverChangesEvidence() {
        val initial = draft()
        var saved: ReviewDraft? = null
        compose.setContent { AppTheme { ReviewScreen(initial, null, { saved = it }, {}, addressLookup = { lat, lon ->
            assertEquals(31.77, lat, 0.0); assertEquals(35.21, lon, 0.0)
            listOf(AddressSuggestion("רחוב בדיקה", "12", "provider full address should not be used"))
        }, topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        compose.onNodeWithText("כן, חפש כתובת").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("הצעה בלבד: \u2068רחוב בדיקה 12\u2069").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("הצעה בלבד: \u2068רחוב בדיקה 12\u2069").performScrollTo().assertExists()
        compose.onNodeWithText("קו רוחב").assertDoesNotExist()
        compose.onNodeWithText("קו אורך").assertDoesNotExist()
        compose.onNodeWithText("שלח").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("הצעה בלבד: \u2068רחוב בדיקה 12\u2069").performScrollTo().performClick()
        compose.onNodeWithText("קו רוחב").assertDoesNotExist()
        compose.onNodeWithText("קו אורך").assertDoesNotExist()
        compose.onNodeWithText("רחוב ומספר בית שאישרת").performScrollTo()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "review-resolved-address-synthetic.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("סיום עריכה").performScrollTo().performClick()
        File(context.filesDir, "review-address-summary-synthetic.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("ערוך").performScrollTo().performClick()
        compose.onNodeWithText("שמור טיוטה").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("רחוב בדיקה 12", saved?.address)
            assertEquals(initial.evidence, saved?.evidence)
            assertEquals(initial.confirmedLatitude, saved?.confirmedLatitude)
            assertEquals(initial.confirmedLongitude, saved?.confirmedLongitude)
        }
        compose.onNodeWithText("קו רוחב").assertDoesNotExist()
        compose.onNodeWithText("קו אורך").assertDoesNotExist()
    }

    @Test fun lookupFailureLeavesManualAddressAndNoInventedNumber() {
        compose.setContent { AppTheme { ReviewScreen(draft(), null, {}, {}, addressLookup = { _, _ -> error("offline") }, topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        compose.onNodeWithText("כן, חפש כתובת").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("אין הצעות, הזן כתובת שבדקת").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("אין הצעות, הזן כתובת שבדקת").performScrollTo().assertExists()
        compose.onNodeWithText("ערוך").performScrollTo().performClick()
        compose.onNodeWithText("רחוב ומספר בית שאישרת").performScrollTo().assertExists()
        compose.onNodeWithText("קו רוחב").assertDoesNotExist()
        compose.onNodeWithText("שלח").performScrollTo().assertIsNotEnabled()
    }
    @Test fun decliningProviderConsentDoesNotLookup() {
        var calls = 0
        compose.setContent { AppTheme { ReviewScreen(draft(), null, {}, {}, addressLookup = { _, _ -> calls += 1; emptyList() }, topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        compose.onNodeWithText("לא עכשיו").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithText("קו רוחב").assertDoesNotExist()
        compose.onNodeWithText("שלח").performScrollTo().assertIsNotEnabled()
    }
    @Test fun missingGpsHasVisibleCorrectionRouteAndNeverInventsCoordinates() {
        val initial = draft().copy(evidence = draft().evidence.copy(latitude = null, longitude = null), confirmedLatitude = null, confirmedLongitude = null)
        var saved: ReviewDraft? = null
        compose.setContent { AppTheme { ReviewScreen(initial, null, { saved = it }, {}, topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        compose.onNodeWithText("לא נמצא מיקום בתמונה.", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("קו רוחב").assertDoesNotExist()
        compose.onNodeWithText("קו אורך").assertDoesNotExist()
        compose.onNodeWithText("שלח").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("שמור טיוטה").performScrollTo().performClick()
        compose.runOnIdle {
            assertNull(saved?.evidence?.latitude)
            assertNull(saved?.evidence?.longitude)
            assertNull(saved?.confirmedLatitude)
            assertNull(saved?.confirmedLongitude)
        }
    }
    @Test fun decliningConsentSurvivesSavedStateRestoration() {
        var calls = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AppTheme { ReviewScreen(draft(), null, {}, {}, addressLookup = { _, _ -> calls += 1; emptyList() }, topicSnapshot = syntheticCityTopics(), topicsAvailable = true) } }
        compose.onNodeWithText("לא עכשיו").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("למצוא רחוב ומספר בית?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, calls) }
    }
}
