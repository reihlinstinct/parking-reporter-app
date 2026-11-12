package io.github.reihlinstinct.reporter

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.core.*
import io.github.reihlinstinct.reporter.registry.*
import io.github.reihlinstinct.reporter.ui.*
import io.github.reihlinstinct.reporter.ui.theme.AppTheme
import io.github.reihlinstinct.reporter.i18n.*
import org.junit.Rule
import org.junit.Test
import java.time.*
import java.io.File

/** Recording-only fixture harness around UNCHANGED merged-main ReviewScreen. No network or filing. */
class RegistryStatusVideoTest {
    @get:Rule val compose = createComposeRule()
    @Test fun reviewRegistryStatesRecordingOnly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        UiPrefsLanguageStore.of(context).set(AppCatalog.defaultCode(context))
        var step by mutableStateOf(0)
        val labels = listOf("דוגמה מוקלטת1912929: רישיון פג תוקף", "דוגמה: נותרו13ימים", "דוגמה: רישיון בתוקף", "דוגמה: אין רישום רכב", "דוגמה: מאגר תג לא זמין", "דוגמה: תג נכה נמצא")
        val messages = listOf("רישיון הרכב פג תוקף לפי המאגר", "רישיון הרכב יפוג בעוד", "רישיון הרכב בתוקף לפי המאגר", "לא נמצא רישום רכב במאגר", "המאגר לא זמין", "תג חניה לנכה, סמל משולש")
        compose.setContent { AppTheme {
            Column(Modifier.fillMaxSize()) {
                Text("הדגמת בדיקה בלבד, ללא פנייה למאגר או שליחה")
                Text(labels[step])
                key(step) {
                    val plate = if (step == 0) "1912929" else "1234567"
                    val checked = Instant.parse("2026-11-12T09:46:00Z")
                    val date = when(step) { 0 -> LocalDate.of(2024,5,27); 1 -> LocalDate.of(2026,11,25); else -> LocalDate.of(2027,1,1) }
                    val lookup: RegistryLookup = remember(step) { { _, _ -> RegistrySnapshot(
                        when(step) { 4 -> DisabilityLookupResult.Error(LookupErrorKind.NETWORK); 5 -> DisabilityLookupResult.Found(DisabilityTagType.TRIANGLE); else -> DisabilityLookupResult.NotFound },
                        if (step == 3) VehicleLookupResult.NotFound else VehicleLookupResult.Found(VehicleRecord(PlateNumber.parse(plate)!!, date, null, null, null, null, null)), checked) } }
                    ReviewScreen(ReviewDraft(Evidence("video-$step", "a".repeat(64), null,31.0,35.0),plate,"רחוב בדיקה12","municipal:1145","רכב $plate חונה על המדרכה",emptySet()),null,{}, {},
                        topicSnapshot=syntheticCityTopics(),topicsAvailable=true,registryLookup=lookup)
                }
            }
        } }
        for (i in labels.indices) {
            compose.runOnIdle { step=i }; compose.waitForIdle()
            compose.onNodeWithText(messages[i], substring=true).performScrollTo().assertIsDisplayed()
            File(context.filesDir,"registry-video-state-$i.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
            Thread.sleep(5000)
        }
    }
}
