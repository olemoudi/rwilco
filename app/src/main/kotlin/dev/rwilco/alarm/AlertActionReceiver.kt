package dev.rwilco.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.rwilco.RwilcoApplication
import dev.rwilco.data.FiringKind
import dev.rwilco.model.isRoutine
import dev.rwilco.model.Snooze
import dev.rwilco.notify.AlertNotifications
import java.time.Instant
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
                        ACTION_DONE -> {
                            // A routine's "hecho" from the shade can be taken back: the count
                            // it moved is said back with "deshacer" (AlertNotifications.doneNotice).
                            val before = app.repository.get(id)
                            app.firing.dismiss(id)
                            if (before != null && before.isRoutine) AlertNotifications.doneNotice(context, before, before.lastDealtAt)
                        }
                        ACTION_PUT_OFF_WEEK -> app.firing.putOffContact(id)
                        ACTION_SNOOZE -> {
                            val snooze = intent.getStringExtra(EXTRA_SNOOZE)
                                ?.let { name -> Snooze.entries.firstOrNull { it.name == name } }
                                ?: Snooze.TEN_MINUTES
                            app.firing.snooze(id, snooze)
                        }
                        // "Todavía no" to a routine's question: the card goes and the count does
                        // not move — but the answer is written down, or "todavía no" and "never
                        // saw the card" were the same nothing in the history.
                        ACTION_LATER -> {
                            AlertNotifications.cancelAsk(context, id)
                            app.repository.record(id, FiringKind.SNOOZED, detail = LATER_DETAIL)
                        }
                        ACTION_UNDO_RESET -> app.firing.undoReset(
                            id,
                            intent.getLongExtra(EXTRA_PREVIOUS, -1L).takeIf { it >= 0 }?.let(Instant::ofEpochMilli),
                        )
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
        /** "Posponer 1 semana" on a contact's card: back for the same weekday's window next week. */
        const val ACTION_PUT_OFF_WEEK = "dev.rwilco.alert.PUT_OFF_WEEK"
        /** "Todavía no" on a routine's question. */
        const val ACTION_LATER = "dev.rwilco.alert.LATER"
        /** "Deshacer" on a routine counted as done by a place; [EXTRA_PREVIOUS] is where the count goes back to. */
        const val ACTION_UNDO_RESET = "dev.rwilco.alert.UNDO_RESET"
        const val EXTRA_SNOOZE = "snooze"
        const val EXTRA_PREVIOUS = "previous"
        private const val BUDGET_MS = 9_000L
    }
}

/** What a "todavía no" writes as its detail: not a moment and not a place, just the word. */
const val LATER_DETAIL = "later"
