package dev.rwilco.ui.editor.sheets

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** What came of asking the phone where it is; the sheet turns it into a sentence. */
sealed interface LocationFix {
    data class Found(val location: Location) : LocationFix

    /** Neither fine nor coarse was granted (or "approximate" was, having asked for precise). */
    data object NoPermission : LocationFix

    /** The location switch is off, or no provider would answer in time. */
    data object NoFix : LocationFix
}

/**
 * One fix from the platform's LocationManager — no Play Services, because the place picker must
 * work on a phone without it.
 *
 * Every enabled provider is asked at once and the first answer wins. Asking only the first one
 * (GPS, which is always "enabled" and answers nowhere indoors) is what made this fail with the
 * permission granted and the phone perfectly able to say where it was: the network provider,
 * sitting right behind it, would have answered immediately.
 *
 * Coarse is enough here. A pin for a geofence with a 200-metre radius does not need a metre of
 * precision, and refusing the "approximate" grant means refusing an answer the phone was willing
 * to give.
 */
suspend fun currentLocation(context: Context): LocationFix {
    if (!hasAnyLocationPermission(context)) return LocationFix.NoPermission
    val manager = context.getSystemService(LocationManager::class.java) ?: return LocationFix.NoFix
    val providers = enabledProviders(manager)
    if (providers.isEmpty()) return LocationFix.NoFix

    // The freshest thing the phone already knows. Often instant and good enough, and the
    // fallback if nothing answers in time — an hour-old fix still names the right neighbourhood.
    val cached = providers
        .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull { it.time }
    if (cached != null && System.currentTimeMillis() - cached.time < FRESH_ENOUGH_MS) return LocationFix.Found(cached)

    val fresh = withTimeoutOrNull(FIX_TIMEOUT_MS) { firstFix(context, manager, providers) }
    return when {
        fresh != null -> LocationFix.Found(fresh)
        cached != null -> LocationFix.Found(cached)
        else -> LocationFix.NoFix
    }
}

/** Fine or coarse: either is enough to drop a pin. */
fun hasAnyLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** The providers worth asking, best first; fused is the phone's own blend where it exists. */
private fun enabledProviders(manager: LocationManager): List<String> {
    val candidates = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
    }
    return candidates.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
}

/** Asks them all at once and resumes with whichever answers first, or null if none does. */
private suspend fun firstFix(context: Context, manager: LocationManager, providers: List<String>): Location? =
    suspendCancellableCoroutine { continuation ->
        val signals = providers.map { CancellationSignal() }
        val delivered = AtomicBoolean(false)
        val silent = AtomicInteger(0)

        fun cancelAll() = signals.forEach { runCatching { it.cancel() } }

        fun deliver(location: Location?) {
            if (location == null) {
                // Every provider has now said "I don't know": there is nothing left to wait for.
                if (silent.incrementAndGet() == providers.size && delivered.compareAndSet(false, true)) {
                    if (continuation.isActive) continuation.resume(null)
                }
                return
            }
            if (delivered.compareAndSet(false, true)) {
                cancelAll()
                if (continuation.isActive) continuation.resume(location)
            }
        }

        continuation.invokeOnCancellation { cancelAll() }
        providers.forEachIndexed { index, provider ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    manager.getCurrentLocation(provider, signals[index], context.mainExecutor) { deliver(it) }
                } else {
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(provider, { deliver(it) }, context.mainLooper)
                }
            } catch (security: SecurityException) {
                Log.w(TAG, "no permission for $provider", security)
                deliver(null)
            } catch (unsupported: IllegalArgumentException) {
                Log.w(TAG, "$provider went away", unsupported)
                deliver(null)
            }
        }
    }

private const val TAG = "RwilcoGeo"
private const val FRESH_ENOUGH_MS = 2 * 60_000L
private const val FIX_TIMEOUT_MS = 12_000L
