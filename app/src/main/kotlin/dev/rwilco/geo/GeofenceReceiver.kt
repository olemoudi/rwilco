package dev.rwilco.geo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Transition
import kotlinx.coroutines.launch

/** The phone arrived somewhere, or left it. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "geofence event error: ${GeofenceStatusCodes.getStatusCodeString(event.errorCode)}")
            return
        }
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
                for (placeId in fenced) {
                    // Its own watch has the last word on whether this is an arrival at all.
                    if (!app.placeWatcher.accept(placeId, transition)) continue
                    app.firing.fire(GeofenceIds.reminderIdOf(placeId), ruleIndex = GeofenceIds.triggerIndexOf(placeId))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "firing a place reminder failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "dev.rwilco.geo.TRANSITION"
        private const val TAG = "RwilcoGeo"
    }
}
