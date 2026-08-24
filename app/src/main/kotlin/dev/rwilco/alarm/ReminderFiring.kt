package dev.rwilco.alarm

import android.content.Context
import android.util.Log
import dev.rwilco.data.ReminderRepository
import dev.rwilco.data.SettingsStore
import dev.rwilco.model.Snooze
import dev.rwilco.model.Status
import dev.rwilco.model.allHoldAt
import dev.rwilco.model.firingPlan
import dev.rwilco.model.missedFire
import dev.rwilco.model.statusAfterDismissal
import dev.rwilco.notify.AlertNotifications
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant

/**
 * What happens when a reminder rings, and what the two answers to it do. One place, so the
 * alarm, the notification buttons and the alert screen cannot drift apart.
 */
class ReminderFiring(
    private val context: Context,
    private val repository: ReminderRepository,
    private val settingsStore: SettingsStore,
    private val scheduler: ReminderScheduler,
    private val clock: Clock,
) {

    /**
     * The moment arrived. [late] is when it should have arrived, if the phone slept through it;
     * [ruleIndex] says which rule rang, which is what lets a place be judged against the
     * conditions on it ("al llegar a casa, y sólo si es por la tarde"). An alarm needs no such
     * check: the moment it was armed for already satisfied them.
     */
    suspend fun fire(id: String, late: Instant? = null, ruleIndex: Int? = null) {
        val reminder = repository.get(id) ?: return
        if (reminder.status != Status.ACTIVE) return
        val now = clock.instant()
        val rule = ruleIndex?.let { reminder.rules.getOrNull(it) }
        if (rule != null && !rule.conditions.allHoldAt(now, clock.zone)) {
            Log.i(TAG, "$id reached its place outside the hours it asked for")
            return
        }
        // A snooze set after the alarm was armed (from the notification, a moment ago) wins.
        val snoozed = reminder.snoozedUntil
        if (snoozed != null && snoozed > now) {
            scheduler.rearmAll()
            return
        }
        Log.i(TAG, "firing $id${if (late != null) " (late)" else ""}")
        repository.markFired(id, now)
        AlertNotifications.post(context, reminder, firingPlan(reminder.actions), late)
        scheduler.rearmAll()
    }

    /** "Hecho": finished if nothing can ring again, otherwise just this occurrence dealt with. */
    suspend fun dismiss(id: String) {
        val reminder = repository.get(id) ?: return
        val now = clock.instant()
        val defaultTime = settingsStore.settings.first().defaultTime
        val status = statusAfterDismissal(reminder, now, clock.zone, defaultTime)
        repository.snooze(id, null)
        repository.setStatus(id, status)
        AlertNotifications.cancel(context, id)
        scheduler.rearmAll()
    }

    suspend fun snooze(id: String, snooze: Snooze) {
        val now = clock.instant()
        val defaultTime = settingsStore.settings.first().defaultTime
        repository.snooze(id, snooze.until(now, clock.zone, defaultTime))
        AlertNotifications.cancel(context, id)
        scheduler.rearmAll()
    }

    /**
     * Re-arms everything and speaks up about what was missed while the phone was off. Used at
     * launch, after a reboot, and by the safety-net worker — not on every edit, where a missed
     * firing is not news.
     */
    suspend fun rearmAndCatchUp() {
        val missed = scheduler.rearmAll()
        for (reminder in missed) {
            val at = missedFire(reminder, clock.instant()) ?: continue
            fire(reminder.id, late = at)
        }
    }

    private companion object {
        const val TAG = "RwilcoAlarms"
    }
}
