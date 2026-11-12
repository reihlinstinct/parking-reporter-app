package io.github.reihlinstinct.reporter.evidence

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** One coordinate pair read from the phone, in degrees. */
data class DeviceFix(val latitude: Double, val longitude: Double)

/**
 * Issue #186: an optional, user-triggered, one-shot read of the phone's location. It is only called after
 * the user taps the button and grants the runtime permission. No background location, no updates, no
 * history: one fix, then the listener is removed. The result only fills the review screen's coordinates;
 * nothing leaves the phone until the user sends the report.
 */
object CurrentLocation {
    const val TIMEOUT_MS = 15_000L

    /** Accepts only a finite, in-range coordinate pair that is not the (0, 0) "no fix" placeholder. */
    fun validFix(latitude: Double, longitude: Double): DeviceFix? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        if (latitude == 0.0 && longitude == 0.0) return null
        return DeviceFix(latitude, longitude)
    }

    /** GPS first (street accuracy), then the network provider; only providers that are switched on. */
    fun providerOrder(enabled: Collection<String>): List<String> =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in enabled }

    /** Null when no provider is on, the read fails or times out. The caller must hold ACCESS_FINE_LOCATION. */
    @SuppressLint("MissingPermission")
    suspend fun read(context: Context, timeoutMs: Long = TIMEOUT_MS): DeviceFix? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = providerOrder(manager.getProviders(true))
        for (provider in providers) {
            // A cold GPS fix needs longer than a network fix, so the first provider (GPS) gets two thirds.
            val budget = if (providers.size > 1) (if (provider == providers.first()) timeoutMs * 2 / 3 else timeoutMs / 3) else timeoutMs
            val location = withTimeoutOrNull(budget) { single(manager, provider, context) }
            val fix = location?.let { validFix(it.latitude, it.longitude) }
            if (fix != null) return fix
        }
        return null
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun single(manager: LocationManager, provider: String, context: Context): Location? =
        try {
            suspendCancellableCoroutine { cont ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val cancel = android.os.CancellationSignal()
                    cont.invokeOnCancellation { cancel.cancel() }
                    manager.getCurrentLocation(provider, cancel, context.mainExecutor) { location ->
                        if (cont.isActive) cont.resume(location)
                    }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            manager.removeUpdates(this)
                            if (cont.isActive) cont.resume(location)
                        }
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }
                    cont.invokeOnCancellation { manager.removeUpdates(listener) }
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            }
        } catch (_: SecurityException) { null } catch (_: IllegalArgumentException) { null }
}
