package dev.rwilco.geo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import dev.rwilco.RwilcoApplication
import dev.rwilco.alarm.RearmWorker
import dev.rwilco.diag.Diag
import dev.rwilco.model.Crossing
import dev.rwilco.model.GeofenceIds
import dev.rwilco.model.Transition
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The phone arrived somewhere, or left it. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            // Play Services says so when it has DROPPED the app's fences — location switched
            // off, the network provider gone — and until now that news went to the log and
            // nowhere else: the places were blind until the six-hourly re-arm came round. The
            // one code that means "they are gone" asks for them back at once; the ones that
            // mean "asking too often" or "too many" would only loop.
            val code = GeofenceStatusCodes.getStatusCodeString(event.errorCode)
            Log.w(TAG, "geofence event error: $code")
            Diag.note("geo", "geofence error $code")
            if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) RearmWorker.runNow(context)
            return
        }
        // **A loitering is not an arrival.** It used to be folded into one, harmlessly, because
        // no fence asked for the transition; now the fences behind a rate do, and it means the
        // opposite of an arrival — not "the line was crossed" but "the wait after it is over".
        // Still an ENTER as far as which side of the line the phone is on, which is all the
        // memory and the log want from it.
        val loitered = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_DWELL
        val transition = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER, Geofence.GEOFENCE_TRANSITION_DWELL -> Transition.ENTER
            Geofence.GEOFENCE_TRANSITION_EXIT -> Transition.EXIT
            else -> return
        }
        val fenced = event.triggeringGeofences.orEmpty().map { it.requestId }.distinct()
        if (fenced.isEmpty()) return
        val app = context.applicationContext as RwilcoApplication
        val pending = goAsync()
        app.appScope.launch {
            try {
                // The broadcast's own budget, as every other receiver here keeps it: past it the
                // system has finished the receiver, and the watch's lock may be held by a look.
                withTimeoutOrNull(BUDGET_MS) {
                    for ((index, placeId) in fenced.withIndex()) {
                        // Each crossing whole or not at all. accept() writes the side into the
                        // watch's memory before its answer says whether to ring, and a
                        // cancellation landing between the two consumed the crossing for good:
                        // the memory said "already there", so no later look could report it
                        // again. The same hardening look() got in 0.58.0 — the timeout may
                        // land between places, never inside one.
                        if (!currentCoroutineContext().isActive) {
                            Log.e(TAG, "place crossings ran out of time; ${fenced.size - index} left unjudged")
                            break
                        }
                        withContext(NonCancellable) {
                            runCatching {
                                // Its own watch has the last word on whether this is an arrival
                                // at all, and on what an arrival is worth: under "todos" a place
                                // that has been ticked off is waiting for the crossing that
                                // takes the tick back.
                                val reminderId = GeofenceIds.reminderIdOf(placeId)
                                val ruleIndex = GeofenceIds.triggerIndexOf(placeId)
                                val crossing =
                                    if (loitered) app.placeWatcher.acceptDwell(placeId)
                                    else app.placeWatcher.accept(placeId, transition)
                                when (crossing) {
                                    Crossing.RINGS ->
                                        if (GeofenceIds.isSnooze(placeId)) app.firing.fire(reminderId, viaSnoozePlace = true)
                                        else app.firing.fire(reminderId, ruleIndex = ruleIndex)
                                    Crossing.TAKES_BACK -> ruleIndex?.let { app.firing.untick(reminderId, it) }
                                    Crossing.NOTHING -> Unit
                                }
                            }.onFailure { Log.e(TAG, "handing on a crossing at $placeId failed", it) }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "firing a place reminder failed", t)
            } finally {
                // Past the broadcast's budget the system has finished the receiver itself.
                runCatching { pending.finish() }
            }
        }
    }

    companion object {
        const val ACTION = "dev.rwilco.geo.TRANSITION"
        /** Under the ten seconds a broadcast is given, with a margin for the finish itself. */
        private const val BUDGET_MS = 9_000L
        private const val TAG = "RwilcoGeo"
    }
}
