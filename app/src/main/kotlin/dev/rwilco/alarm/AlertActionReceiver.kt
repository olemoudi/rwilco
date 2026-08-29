package dev.rwilco.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Snooze
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The "Hecho" and "Posponer" buttons on the notification. */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = ReminderScheduler.reminderIdOf(intent) ?: return
        val app = context.applicationContext as RwilcoApplication
        val pending = goAsync()
        app.appScope.launch {
            try {
                // Bounded under the broadcast's own budget, as AlarmReceiver is: past it the
                // system finishes the receiver itself, and a finish() of ours on top throws.
                val done = withTimeoutOrNull(BUDGET_MS) {
                    when (intent.action) {
                        ACTION_DONE -> app.firing.dismiss(id)
                        ACTION_SNOOZE -> {
                            val snooze = intent.getStringExtra(EXTRA_SNOOZE)
                                ?.let { name -> Snooze.entries.firstOrNull { it.name == name } }
                                ?: Snooze.TEN_MINUTES
                            app.firing.snooze(id, snooze)
                        }
                    }
                }
                if (done == null) Log.e("RwilcoAlarms", "action ${intent.action} on $id ran out of time")
            } catch (t: Throwable) {
                Log.e("RwilcoAlarms", "action ${intent.action} on $id failed", t)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    companion object {
        const val ACTION_DONE = "dev.rwilco.alert.DONE"
        const val ACTION_SNOOZE = "dev.rwilco.alert.SNOOZE"
        const val EXTRA_SNOOZE = "snooze"
        private const val BUDGET_MS = 9_000L
    }
}
