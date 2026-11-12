package io.github.reihlinstinct.reporter.registry

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.i18n.ProvideStringsIfMissing
import io.github.reihlinstinct.reporter.ui.*
import io.github.reihlinstinct.reporter.ui.theme.AppTheme
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import java.io.File
import java.time.*

/** Explicit diagnostic only: government GET clients and display, no city client or Send callback. */
class LiveReadOnlyRegistryTest {
    @get:Rule val compose = createComposeRule()
    @Test fun readOnlyKnownPlatesThroughRealAppClientsAndDisplay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.filesDir, "registry-check-plates.txt")
        Assume.assumeTrue("Manual read-only diagnostic only", input.exists())
        val plates = input.readText().trim().split(',')
        require(plates.size in 1..6 && plates.all { PlateNumber.parse(it) != null })
        io.github.reihlinstinct.reporter.i18n.UiPrefsLanguageStore.of(context).set(io.github.reihlinstinct.reporter.i18n.AppCatalog.defaultCode(context))
        val tags = DisabilityRegistryClient()
        val vehicles = VehicleRegistryClient(VehicleRegistryConfig(VehicleRegistryConfig.KNOWN_VEHICLE_RESOURCE_ID), VehicleRegistryClient.safeClient().newBuilder().callTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build())
        var plate by mutableStateOf("")
        var result by mutableStateOf<RegistrySnapshot?>(null)
        val rows = JSONArray()
        compose.setContent { AppTheme { ProvideStringsIfMissing {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text("בדיקת מאגר רכב, קריאה בלבד", style = MaterialTheme.typography.titleLarge)
                Text("מספר רכב: $plate", style = MaterialTheme.typography.titleMedium)
                val snapshot = result
                if (snapshot == null) Text("מתחבר למאגר הממשלתי...")
                else RegistryStatus(plate, lookup = remember(snapshot) { { _, _ -> snapshot } })
                Text("ללא הגשה לעירייה וללא שינוי נתונים")
            }
        } } }
        for ((index, current) in plates.withIndex()) {
            compose.runOnIdle { plate = current; result = null }
            val snapshot = runBlocking {
                coroutineScope {
                    val tag = async(Dispatchers.IO) { tags.lookup(current) }
                    val vehicle = async(Dispatchers.IO) { vehicles.lookup(current) }
                    RegistrySnapshot(tag.await(), vehicle.await(), Instant.now())
                }
            }
            compose.runOnIdle { result = snapshot }
            compose.waitForIdle()
            Thread.sleep(3500)
            val tag = snapshot.tag
            val vehicle = snapshot.vehicle
            val row = JSONObject().put("plate", current).put("checked_at", snapshot.checkedAt.toString())
                .put("tag", when(tag) { is DisabilityLookupResult.Found -> tag.type.name; is DisabilityLookupResult.NotFound -> "NOT_FOUND"; is DisabilityLookupResult.Error -> "ERROR_${tag.kind}" })
            if (vehicle is VehicleLookupResult.Found) {
                row.put("licence_until", vehicle.record.licenceValidUntil?.toString() ?: "UNKNOWN")
                row.put("licence_status", vehicle.record.licenceStatus(LocalDate.now(ZoneId.of("Asia/Jerusalem")))).put("model", vehicle.record.commercialName)
            } else row.put("licence_status", if (vehicle is VehicleLookupResult.NotFound) "NOT_FOUND" else "UNAVAILABLE_${(vehicle as? VehicleLookupResult.Error)?.kind}_${(vehicle as? VehicleLookupResult.Error)?.httpStatus}")
            rows.put(row)
            File(context.filesDir, "registry-proof-$index.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
            File(context.filesDir, "registry-results.json").writeText(rows.toString(2))
            Thread.sleep(1300)
        }
        Assert.assertEquals(plates.size, rows.length())
    }
}
