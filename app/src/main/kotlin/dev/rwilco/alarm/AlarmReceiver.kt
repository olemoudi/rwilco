package dev.rwilco.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.rwilco.RwilcoApplication
import dev.rwilco.diag.Diag
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A reminder's moment arrived. Everything real happens in [ReminderFiring]. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = ReminderScheduler.reminderIdOf(intent) ?: return
        val ruleIndex = ReminderScheduler.ruleIndexOf(intent)
        // Two alarms can be armed for one reminder: its own moment, and the safety net's quiet
        // word about a moment nobody answered. They arrive here by the same door, told apart by
        // the URI they were armed under.
        val nudge = ReminderScheduler.isNudge(intent)
        // And a third: the set's deadline running out, which rings nothing and lets the round go.
        val lapse = ReminderScheduler.isLapse(intent)
        // And a fourth: a routine asking whether it has been done (Prompt.kt).
        val ask = ReminderScheduler.isAsk(intent)
        val app = context.applicationContext as RwilcoApplication
        // goAsync: the work is a database read and a notification, and a receiver that returns
        // before them is a reminder that never rings.
        val pending = goAsync()
        app.appScope.launch {
            try {
                // **The answer is never cut short; only the wait for it is.** The broadcast's own
                // budget bounds how long this receiver holds on — past it the system finishes the
                // receiver itself, and a finish() of our own on top of that throws — but the work
                // runs on beside it. Cancelled at the budget, a firing queued behind another door
                // holding the lock was dropped before it was written down, and its moment waited
                // for the next catch-up: hours, and by then a silent "missed" card.
                val work = app.appScope.async {
                    when {
                        nudge -> app.firing.nudge(id)
                        lapse -> app.firing.expire(id)
                        ask -> app.firing.ask(id, ruleIndex, viaPlace = false)
                        else -> app.firing.fire(id, ruleIndex = ruleIndex)
                    }
                }
                // Said even when nobody is waiting any more: past the budget, a throw has no
                // catch below to land in.
                work.invokeOnCompletion { failure -> if (failure != null) Log.e("RwilcoAlarms", "firing $id failed", failure) }
                val done = withTimeoutOrNull(BUDGET_MS) { work.await() }
                if (done == null) {
                    // In case this process does not live to finish it: the same alarm, a minute on.
                    // Only a ring has a moment the row keeps armed for it; the net's word, a lapse
                    // and a question are re-derived by the next re-arm pass on their own.
                    val retry = !nudge && !lapse && !ask
                    if (retry) {
                        app.scheduler.armRetry(id, ruleIndex, app.clock.instant().plusSeconds(RETRY_SECONDS))
                        // The retry has this reminder's alarm identity, so it stands over whatever
                        // the work armed before it finished — a next moment under a minute away
                        // would ring late, as the wrong rule. Once the work is done, it is re-armed
                        // from the row; done already, that is now.
                        work.invokeOnCompletion { app.appScope.launch { runCatching { app.scheduler.rearmAll() } } }
                    }
                    Log.e("RwilcoAlarms", "firing $id ran out of time; it goes on${if (retry) ", and is asked again in a minute" else ""}")
                    Diag.note("fire", "r=${id.take(8)} outlasted its broadcast${if (retry) "; retry armed" else ""}")
                }
            } catch (t: Throwable) {
                // Said by invokeOnCompletion above, which is the one place that sees it either way.
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    private companion object {
        const val BUDGET_MS = 9_000L
        const val RETRY_SECONDS = 60L
    }
}
