package dev.rwilco.system

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.rwilco.alarm.RearmWorker
import dev.rwilco.diag.Diag
import dev.rwilco.update.UpdateWorker

/**
 * The handful of system events this app has to answer.
 *
 * A reboot clears every alarm the app had armed, and so does installing over itself; a change of
 * time or time zone moves every wall-clock moment the app promised ("half past nine" is not an
 * instant until a zone says so); and the exact-alarm grant coming or going is the alarms
 * themselves coming or going. All of them mean the same thing here: work out again when
 * everything should ring, and check nothing was missed in between.
 */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            // On Android 12 and 13 "Alarms & reminders" can be taken away, and taking it away
            // cancels every exact alarm the app had; given back, nothing re-arms them but this.
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> Unit
            else -> return
        }
        Log.i(TAG, "re-arming after ${intent.action}")
        Diag.note("sys", "re-arming after ${intent.action?.substringAfterLast('.')}")
        RearmWorker.schedule(context)
        RearmWorker.runNow(context)
        // The update check rides along on boot and after an update, as it always has.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // The periodic check is scheduled by the Application, which is the only place that
            // knows whether it is allowed on mobile data; this is the one-off that rides along.
            UpdateWorker.runNow(context)
        }
    }

    private companion object {
        const val TAG = "RwilcoAlarms"
    }
}
