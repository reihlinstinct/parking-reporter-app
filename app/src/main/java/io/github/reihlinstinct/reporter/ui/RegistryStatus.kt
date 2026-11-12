package io.github.reihlinstinct.reporter.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
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

typealias RegistryLookup = suspend (String, (RegistrySnapshot) -> Unit) -> RegistrySnapshot

class RegistrySnapshot(val tag: DisabilityLookupResult?, val vehicle: VehicleLookupResult?, val checkedAt: Instant) {
    override fun toString() = "RegistrySnapshot(redacted)"
}

/** Independent completion prevents a slow licence endpoint from hiding a completed tag. */
suspend fun readRegistrySnapshot(
    plate: String,
    tagLookup: suspend (String) -> DisabilityLookupResult,
    vehicleLookup: suspend (String) -> VehicleLookupResult,
    publish: (RegistrySnapshot) -> Unit,
): RegistrySnapshot = coroutineScope {
    var tag: DisabilityLookupResult? = null
    var vehicle: VehicleLookupResult? = null
    suspend fun emit() {
        currentCoroutineContext().ensureActive()
        publish(RegistrySnapshot(tag, vehicle, Instant.now()))
    }
    // Child coroutines resume on the caller's context; writes/publish are serialized there.
    val tagJob = launch { tag = tagLookup(plate); emit() }
    val vehicleJob = launch { vehicle = vehicleLookup(plate); emit() }
    joinAll(tagJob, vehicleJob)
    RegistrySnapshot(tag, vehicle, Instant.now())
}

/** Only the production shell opts into network reads; isolated previews/tests have no network. */
@Composable
fun rememberRegistryLookup(): RegistryLookup {
    val tag = remember { DisabilityRegistryClient(cacheTtlMillis = 0) }
    val vehicle = remember { VehicleRegistryClient(VehicleRegistryConfig(VehicleRegistryConfig.KNOWN_VEHICLE_RESOURCE_ID,
        cacheTtlMillis = 0), VehicleRegistryClient.safeClient().newBuilder()
        .callTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build()) }
    val cache = remember { TtlLruCache<PlateNumber, RegistrySnapshot>(128, 300_000) { System.nanoTime() / 1_000_000 } }
    return remember(tag, vehicle) { { plate, publish ->
        val key = requireNotNull(PlateNumber.parse(plate))
        cache.get(key) ?: readRegistrySnapshot(plate,
            { withContext(Dispatchers.IO) { tag.lookup(it) } },
            { withContext(Dispatchers.IO) { vehicle.lookup(it) } }, publish).also {
                if (it.tag != null && it.vehicle != null && it.tag !is DisabilityLookupResult.Error && it.vehicle !is VehicleLookupResult.Error) cache.put(key, it)
            }
    } }
}

/** Independent read-only information. No draft, text, save or submit callback exists here. */
@Composable
fun RegistryStatus(plate: String, lookup: RegistryLookup? = null) {
    if (PlateNumber.parse(plate) == null) return
    val s = LocalStrings.current
    // Keyed state is cleared synchronously on an edit, before any effect can return.
    var result by remember(plate) { mutableStateOf<RegistrySnapshot?>(null) }
    var unavailable by remember(plate) { mutableStateOf(false) }
    LaunchedEffect(plate, lookup) {
        try {
            if (lookup == null) unavailable = true
            else result = withTimeout(6500) { lookup(plate) { partial ->
                result = partial
            } }
        } catch (e: TimeoutCancellationException) {
            // Keep a completed half. Missing half gets a neutral unavailable result.
            result = result?.let { RegistrySnapshot(it.tag ?: DisabilityLookupResult.Error(LookupErrorKind.NETWORK),
                it.vehicle ?: VehicleLookupResult.Error(LookupErrorKind.NETWORK), it.checkedAt) }
            unavailable = result == null
        }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            result = result?.let { RegistrySnapshot(it.tag ?: DisabilityLookupResult.Error(LookupErrorKind.NETWORK),
                it.vehicle ?: VehicleLookupResult.Error(LookupErrorKind.NETWORK), it.checkedAt) }
            unavailable = result == null
        }
    }
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        val current = result
        when {
            unavailable -> Text(s.t("registry.unavailable"))
            current == null -> Text(s.t("registry.loading"))
            else -> {
                val tag = current.tag
                val type = (tag as? DisabilityLookupResult.Found)?.type
                if (tag is DisabilityLookupResult.Found) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val icon = when (type) {
                        DisabilityTagType.TRIANGLE -> R.drawable.ic_disability_tag_triangle
                        DisabilityTagType.WHEELCHAIR -> R.drawable.ic_disability_tag_wheelchair
                        else -> null
                    }
                    val tagKey = when {
                        tag == null -> "registry.loading"
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
                val licenceText = s.t(when {
                    vehicle == null -> "registry.loading"
                    status == LicenceStatus.EXPIRED -> "registry.licence.expired"
                    status == LicenceStatus.VALID -> "registry.licence.valid"
                    status == LicenceStatus.UNKNOWN -> "registry.licence.unknown"
                    vehicle is VehicleLookupResult.NotFound -> "registry.licence.not_found"
                    else -> "registry.unavailable"
                })
                if (status == LicenceStatus.EXPIRED) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
                        Text(licenceText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                    }
                } else Text(licenceText)
                Text(s.t("registry.source", mapOf("time" to current.checkedAt.atZone(ZoneId.of("Asia/Jerusalem")).toLocalTime().withNano(0).toString())), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(s.t("registry.informational"), style = MaterialTheme.typography.bodySmall)
    }
}
