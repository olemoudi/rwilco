package dev.rwilco.ui.editor.sheets

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** One fix from the platform's LocationManager (no Play Services), or null within 15 seconds. */
suspend fun currentLocation(context: Context): Location? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        ?: return null
    // The last known fix is often good enough and instant; only wait for a fresh one otherwise.
    val cached = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    if (cached != null && System.currentTimeMillis() - cached.time < 2 * 60_000) return cached
    return withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine { continuation ->
            val cancel = CancellationSignal()
            continuation.invokeOnCancellation { cancel.cancel() }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    manager.getCurrentLocation(provider, cancel, context.mainExecutor) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(provider, { location -> if (continuation.isActive) continuation.resume(location) }, context.mainLooper)
                }
            } catch (security: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }
}
