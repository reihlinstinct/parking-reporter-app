package io.github.reihlinstinct.reporter.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/** Registry-check date in Jerusalem, not a later recomposition clock. Today is valid through today. */
fun licenceDaysRemaining(until: LocalDate?, checkedAt: Instant): Long? = until?.let {
    java.time.temporal.ChronoUnit.DAYS.between(checkedAt.atZone(ZoneId.of("Asia/Jerusalem")).toLocalDate(), it)
}

class RegistrySnapshot(val tag: DisabilityLookupResult?, val vehicle: VehicleLookupResult?, val checkedAt: Instant, val cachedAt: Instant? = null, val cachedUntil: Instant? = null) {
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
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val cache = remember(context) { RegistryFallbackCache(java.io.File(context.noBackupFilesDir, "registry-fallback")) }
    return remember(tag, vehicle, cache) { { plate, publish ->
        val key = requireNotNull(PlateNumber.parse(plate))
        val saved = withContext(Dispatchers.IO) { cache.get(key) }
        cachedRegistryLookup(plate, saved,
            { p, emit -> readRegistrySnapshot(p,
                { withContext(Dispatchers.IO) { tag.lookup(it) } },
                { withContext(Dispatchers.IO) { vehicle.lookup(it) } }, emit) },
            { fresh -> withContext(Dispatchers.IO) {
                try { cache.put(key, fresh.tag, fresh.vehicle, fresh.checkedAt) } catch (_: Exception) { /* Cache cannot gate live data. */ }
            } }, publish)
    } }

}

/** Independent read-only information. No draft, text, save or submit callback exists here. */
@Composable
fun RegistryStatus(plate: String, lookup: RegistryLookup? = null) {
    io.github.reihlinstinct.reporter.i18n.ProvideStringsIfMissing {
        RegistryStatusContent(plate, lookup)
    }
}

@Composable
private fun RegistryStatusContent(plate: String, lookup: RegistryLookup?) {
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
                it.vehicle ?: VehicleLookupResult.Error(LookupErrorKind.NETWORK), it.checkedAt, it.cachedAt, it.cachedUntil) }
            unavailable = result == null
        }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            result = result?.let { RegistrySnapshot(it.tag ?: DisabilityLookupResult.Error(LookupErrorKind.NETWORK),
                it.vehicle ?: VehicleLookupResult.Error(LookupErrorKind.NETWORK), it.checkedAt, it.cachedAt, it.cachedUntil) }
            unavailable = result == null
        }
    }
    val fallbackDeadline = result?.cachedUntil
    LaunchedEffect(fallbackDeadline) {
        if (fallbackDeadline != null) {
            delay(java.time.Duration.between(Instant.now(), fallbackDeadline).toMillis().coerceAtLeast(0))
            if (result?.cachedUntil == fallbackDeadline) { result = null; unavailable = true }
        }
    }
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        val current = result
        when {
            unavailable -> Text(s.t("registry.unavailable"))
            current == null -> Text(s.t("registry.loading"))
            else -> {
                if (current.cachedAt != null) {
                    val age = java.time.Duration.between(current.cachedAt, Instant.now()).toMinutes().coerceAtLeast(0)
                    Text(s.t("registry.cached", mapOf("minutes" to age.toString())), style = MaterialTheme.typography.bodySmall)
                }
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
                if (tag is DisabilityLookupResult.Error) Text(s.t("registry.unavailable"))
                val vehicle = current.vehicle
                val days = licenceDaysRemaining((vehicle as? VehicleLookupResult.Found)?.record?.licenceValidUntil, current.checkedAt)
                val status = when { days == null -> if (vehicle is VehicleLookupResult.Found) LicenceStatus.UNKNOWN else null; days < 0 -> LicenceStatus.EXPIRED; else -> LicenceStatus.VALID }
                val expiresSoon = days != null && days in 0L..13L
                val licenceText = s.t(when {
                    vehicle == null -> "registry.loading"
                    status == LicenceStatus.EXPIRED -> "registry.licence.expired"
                    expiresSoon -> "registry.licence.expires_soon"
                    status == LicenceStatus.VALID -> "registry.licence.valid"
                    status == LicenceStatus.UNKNOWN -> "registry.licence.unknown"
                    vehicle is VehicleLookupResult.NotFound -> "registry.licence.not_found"
                    else -> "registry.unavailable"
                }, mapOf("days" to (days ?: 0L).toString()))
                if (status == LicenceStatus.EXPIRED) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
                        Text(licenceText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                    }
                } else if (expiresSoon) {
                    // Dark orange on light surfaces and light orange on dark surfaces for contrast.
                    val surface = MaterialTheme.colorScheme.surface
                    val orange = if (surface.red + surface.green + surface.blue < 1.5f) Color(0xFFFFB74D) else Color(0xFF9A4900)
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Warning, null, tint = orange, modifier = Modifier.size(28.dp))
                        Text(licenceText, color = orange, style = MaterialTheme.typography.titleMedium)
                    }
                } else Text(licenceText)
                Text(s.t("registry.source", mapOf("time" to (current.cachedAt ?: current.checkedAt).atZone(ZoneId.of("Asia/Jerusalem")).toLocalTime().withNano(0).toString())), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(s.t("registry.informational"), style = MaterialTheme.typography.bodySmall)
    }
}

/** Cache is only an immediate/failure fallback: never short-circuit the live request. */
suspend fun cachedRegistryLookup(
    plate: String,
    saved: RegistryFallbackCache.Cached?,
    live: RegistryLookup,
    save: suspend (RegistrySnapshot) -> Unit,
    publish: (RegistrySnapshot) -> Unit,
): RegistrySnapshot {
    fun merged(fresh: RegistrySnapshot): RegistrySnapshot {
        val usable = saved != null && Instant.now().isBefore(saved.expiresAt)
        val useTag = usable && (fresh.tag == null || fresh.tag is DisabilityLookupResult.Error)
        val useVehicle = usable && (fresh.vehicle == null || fresh.vehicle is VehicleLookupResult.Error)
        return RegistrySnapshot(if (useTag) saved!!.tag else fresh.tag,
            if (useVehicle) saved!!.vehicle else fresh.vehicle, fresh.checkedAt,
            if (useTag || useVehicle) saved!!.checkedAt else null,
            if (useTag || useVehicle) saved!!.expiresAt else null)
    }
    if (saved != null && Instant.now().isBefore(saved.expiresAt))
        publish(RegistrySnapshot(saved.tag, saved.vehicle, Instant.now(), saved.checkedAt, saved.expiresAt))
    // Throws cancellation through to the plate-keyed UI effect; its partial result stays visible.
    val fresh = live(plate) { publish(merged(it)) }
    save(fresh)
    return merged(fresh)
}
