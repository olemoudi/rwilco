package dev.rwilco.geo

import android.content.Context
import dev.rwilco.model.Fix
import dev.rwilco.model.speaksForHere
import dev.rwilco.ui.editor.sheets.LocationFix
import dev.rwilco.ui.editor.sheets.currentLocation
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the phone is right now, for "al salir de aquí": the watch's own last position when it
 * is fresh and tight enough to draw a circle around, else one asked of the platform the way
 * the place picker asks (every provider at once, the first answer wins, a few seconds at most).
 * Null when nothing can say — no grant, location off, nothing answered, or an answer sloppier
 * than the circle it would draw, which the exit would then cross by noise alone.
 */
suspend fun hereFix(context: Context, store: PlaceWatchStore, now: Instant): Fix? = withContext(Dispatchers.IO) {
    store.read().lastFix?.takeIf { it.speaksForHere(now) }?.let { return@withContext it }
    val found = currentLocation(context) as? LocationFix.Found ?: return@withContext null
    val location = found.location
    val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0
    // The platform hands back its last known fix when nothing answers in time, however old:
    // "aquí" drawn around where the phone was half an hour ago rings the moment it looks.
    Fix(location.latitude, location.longitude, accuracy, Instant.ofEpochMilli(location.time)).takeIf { it.speaksForHere(now) }
}
