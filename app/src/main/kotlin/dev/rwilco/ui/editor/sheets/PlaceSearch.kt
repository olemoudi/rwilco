package dev.rwilco.ui.editor.sheets

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** A place somebody searched for: what to show, and where it is. */
data class FoundPlace(val label: String, val detail: String?, val lat: Double, val lng: Double)

/**
 * Turning "calle mayor 3" into a point on the map, through the platform's own geocoder — the
 * phone already has one, and it speaks the language the phone is set to. Where it is missing or
 * offline the search comes back empty and the map is still there to long-press.
 */
suspend fun searchPlaces(context: Context, query: String, locale: Locale, limit: Int = 5): List<FoundPlace> {
    val text = query.trim()
    if (text.isEmpty() || !Geocoder.isPresent()) return emptyList()
    val geocoder = Geocoder(context, locale)
    val addresses = withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine<List<Address>> { continuation ->
                geocoder.getFromLocationName(text, limit, object : Geocoder.GeocodeListener {
                    override fun onGeocode(results: MutableList<Address>) {
                        if (continuation.isActive) continuation.resume(results)
                    }

                    override fun onError(errorMessage: String?) {
                        Log.w(TAG, "geocoder said: $errorMessage")
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocationName(text, limit).orEmpty() }
                    .onFailure { Log.w(TAG, "geocoding failed", it) }
                    .getOrDefault(emptyList())
            }
        }
    }.orEmpty()
    return addresses.mapNotNull { it.toFoundPlace() }
}

private fun Address.toFoundPlace(): FoundPlace? {
    if (!hasLatitude() || !hasLongitude()) return null
    // The first address line is the postal one-liner; the rest is where it is, which is what
    // tells two "Calle Mayor 3" apart.
    val line = getAddressLine(0)
    val label = line?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
        ?: featureName
        ?: locality
        ?: return null
    val detail = line?.substringAfter(',', "")?.trim()?.takeIf { it.isNotEmpty() }
        ?: listOfNotNull(locality, countryName).joinToString(", ").takeIf { it.isNotEmpty() }
    return FoundPlace(label, detail, latitude, longitude)
}

private const val TAG = "RwilcoGeo"
private const val GEOCODE_TIMEOUT_MS = 10_000L
