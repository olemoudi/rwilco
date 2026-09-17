package dev.rwilco.ui.editor.sheets

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
 * What a search came to: places — possibly none, which is an answer — or no answer at all.
 *
 * They are different sentences. "No such address" sends somebody back to the words they typed;
 * "could not look" is about the phone, and the thing to do is drop the pin by hand. They used
 * to be one empty list, so a search made on the metro said the address did not exist (0.132.0).
 */
sealed interface PlaceSearch {
    data class Found(val places: List<FoundPlace>) : PlaceSearch

    /** The geocoder could not be asked: missing, failed, timed out, or the phone is offline. */
    data object Unavailable : PlaceSearch
}

/**
 * [found] is null when the geocoder gave no answer. An empty answer while [online] is false is
 * the network talking, not the address: a geocoder with no connection tends to say "nothing"
 * rather than fail.
 */
fun placeSearchOutcome(found: List<FoundPlace>?, online: Boolean): PlaceSearch = when {
    found == null -> PlaceSearch.Unavailable
    found.isEmpty() && !online -> PlaceSearch.Unavailable
    else -> PlaceSearch.Found(found)
}

/**
 * Turning "calle mayor 3" into a point on the map, through the platform's own geocoder — the
 * phone already has one, and it speaks the language the phone is set to. Where it is missing or
 * offline the search says so ([PlaceSearch.Unavailable]) and the map is still there to long-press.
 */
suspend fun searchPlaces(context: Context, query: String, locale: Locale, limit: Int = 5): PlaceSearch {
    val text = query.trim()
    if (text.isEmpty()) return PlaceSearch.Found(emptyList())
    if (!Geocoder.isPresent()) return PlaceSearch.Unavailable
    val geocoder = Geocoder(context, locale)
    // Null is "no answer": a timeout, an error, a throw. An empty list is the geocoder's own.
    val addresses: List<Address>? = withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine<List<Address>?> { continuation ->
                geocoder.getFromLocationName(text, limit, object : Geocoder.GeocodeListener {
                    override fun onGeocode(results: MutableList<Address>) {
                        if (continuation.isActive) continuation.resume(results)
                    }

                    override fun onError(errorMessage: String?) {
                        Log.w(TAG, "geocoder said: $errorMessage")
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocationName(text, limit).orEmpty() }
                    .onFailure { Log.w(TAG, "geocoding failed", it) }
                    .getOrNull()
            }
        }
    }
    return placeSearchOutcome(addresses?.mapNotNull { it.toFoundPlace() }, online = context.isOnline())
}

/**
 * Whether the phone has a network that reaches the internet right now. No active network is
 * offline; a question that could not be asked at all counts as online, so that an answer the
 * geocoder did give is believed rather than second-guessed.
 */
private fun Context.isOnline(): Boolean = runCatching {
    val connectivity = getSystemService(ConnectivityManager::class.java) ?: return@runCatching true
    val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return@runCatching false
    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}.getOrDefault(true)

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
