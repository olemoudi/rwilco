package dev.rwilco.geo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import dev.rwilco.RwilcoApplication
import kotlinx.coroutines.launch

/** The phone arrived somewhere, or left it. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "geofence event error: ${GeofenceStatusCodes.getStatusCodeString(event.errorCode)}")
            return
        }
        val ids = event.triggeringGeofences.orEmpty().map { GeofenceIds.reminderIdOf(it.requestId) }.distinct()
        if (ids.isEmpty()) return
        val app = context.applicationContext as RwilcoApplication
        val pending = goAsync()
        app.appScope.launch {
            try {
                for (id in ids) app.firing.fire(id)
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
