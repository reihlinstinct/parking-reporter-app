package io.github.reihlinstinct.reporter.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.reihlinstinct.reporter.R
import io.github.reihlinstinct.reporter.i18n.LocalStrings
import io.github.reihlinstinct.reporter.registry.*
import kotlinx.coroutines.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class RegistrySnapshot(val tag: DisabilityLookupResult, val vehicle: VehicleLookupResult, val checkedAt: Instant) {
    override fun toString() = "RegistrySnapshot(redacted)"
}

/** Only the production shell opts into network reads; isolated previews/tests have no network. */
@Composable
fun rememberRegistryLookup(): suspend (String) -> RegistrySnapshot {
    val tag = remember { DisabilityRegistryClient(cacheTtlMillis = 0) }
    val vehicle = remember { VehicleRegistryClient(VehicleRegistryConfig(VehicleRegistryConfig.KNOWN_VEHICLE_RESOURCE_ID,
        cacheTtlMillis = 0), DisabilityRegistryClient.boundedClient()) }
    val cache = remember { TtlLruCache<PlateNumber, RegistrySnapshot>(128, 300_000) { System.nanoTime() / 1_000_000 } }
    return remember(tag, vehicle) { { plate ->
        val key = requireNotNull(PlateNumber.parse(plate))
        cache.get(key) ?: coroutineScope {
            val tagResult = async(Dispatchers.IO) { tag.lookup(plate) }
            val vehicleResult = async(Dispatchers.IO) { vehicle.lookup(plate) }
            RegistrySnapshot(tagResult.await(), vehicleResult.await(), Instant.now()).also {
                if (it.tag !is DisabilityLookupResult.Error && it.vehicle !is VehicleLookupResult.Error) cache.put(key, it)
            }
        }
    } }
}

/** Independent read-only information. No draft, text, save or submit callback exists here. */
@Composable
fun RegistryStatus(plate: String, lookup: (suspend (String) -> RegistrySnapshot)? = null) {
    if (PlateNumber.parse(plate) == null) return
    val s = LocalStrings.current
    // Keyed state is cleared synchronously on an edit, before any effect can return.
    var result by remember(plate) { mutableStateOf<RegistrySnapshot?>(null) }
    var unavailable by remember(plate) { mutableStateOf(false) }
    LaunchedEffect(plate, lookup) {
        try {
            if (lookup == null) unavailable = true
            else result = withTimeout(2200) { lookup(plate) }
        } catch (e: TimeoutCancellationException) { unavailable = true }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { unavailable = true }
    }
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        val current = result
        when {
            unavailable -> Text(s.t("registry.unavailable"))
            current == null -> Text(s.t("registry.loading"))
            else -> {
                val tag = current.tag
                val type = (tag as? DisabilityLookupResult.Found)?.type
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val icon = when (type) {
                        DisabilityTagType.TRIANGLE -> R.drawable.ic_disability_tag_triangle
                        DisabilityTagType.WHEELCHAIR -> R.drawable.ic_disability_tag_wheelchair
                        else -> null
                    }
                    val tagKey = when {
                        type == DisabilityTagType.TRIANGLE -> "registry.tag.triangle_icon"
                        type == DisabilityTagType.WHEELCHAIR -> "registry.tag.wheelchair_icon"
                        type == DisabilityTagType.UNKNOWN -> "registry.tag.unknown"
                        tag is DisabilityLookupResult.NotFound -> "registry.tag.not_found"
                        else -> "registry.unavailable"
                    }
                    if (icon != null) Image(painterResource(icon), null, Modifier.size(32.dp))
                    Text(s.t(tagKey))
                }
                val vehicle = current.vehicle
                val status = (vehicle as? VehicleLookupResult.Found)?.record?.licenceStatus(LocalDate.now(ZoneId.of("Asia/Jerusalem")))
                Text(s.t(when {
                    status == LicenceStatus.EXPIRED -> "registry.licence.expired"
                    status == LicenceStatus.VALID -> "registry.licence.valid"
                    status == LicenceStatus.UNKNOWN -> "registry.licence.unknown"
                    vehicle is VehicleLookupResult.NotFound -> "registry.licence.not_found"
                    else -> "registry.unavailable"
                }))
                Text(s.t("registry.source", mapOf("time" to current.checkedAt.atZone(ZoneId.of("Asia/Jerusalem")).toLocalTime().withNano(0).toString())), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(s.t("registry.informational"), style = MaterialTheme.typography.bodySmall)
    }
}
