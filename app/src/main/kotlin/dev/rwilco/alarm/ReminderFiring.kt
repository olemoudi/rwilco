package dev.rwilco.alarm

import android.content.Context
import android.util.Log
import dev.rwilco.data.ReminderRepository
import dev.rwilco.data.SettingsStore
import dev.rwilco.model.FiringOutcome
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.Snooze
import dev.rwilco.model.Status
import dev.rwilco.model.Trigger
import dev.rwilco.geo.PlaceWatchStore
import dev.rwilco.model.Condition
import dev.rwilco.model.PlaceWatchPolicy
import dev.rwilco.model.TriggerRule
import dev.rwilco.model.allHoldAt
import dev.rwilco.model.togetherRule
import dev.rwilco.model.knownInAdvance
import dev.rwilco.model.firingPlan
import dev.rwilco.model.missedFire
import dev.rwilco.model.momentRungFor
import dev.rwilco.model.outcomeOfFiring
import dev.rwilco.model.rulesCombine
import dev.rwilco.model.statusAfterDismissal
import dev.rwilco.notify.AlertNotifications
import dev.rwilco.notify.AlertPresenter
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Duration
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
    /** Where the phone was last seen, for the "y sólo si estoy en X" conditions. */
    private val placeWatch: PlaceWatchStore,
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
        // A place is judged when it happens; a moment was judged when it was armed. Judging an
        // alarm again here would silence a firing the phone slept through — the catch-up runs
        // long after the window it was armed inside.
        val rule = ruleIndex?.let { reminder.rules.getOrNull(it) }
        // Under "a la vez" the rule is judged with every other one folded into it as a state:
        // the moment this one happened is only a firing if all of them are true then. A null
        // is a set that cannot hold at all — two instants asked to coincide.
        val judged = ruleIndex?.let { reminder.togetherRule(it) }
        if (ruleIndex != null && judged == null) {
            Log.i(TAG, "$id asks for two moments at once, which never happens")
            return
        }
        if (judged != null && !conditionsHold(judged, now)) {
            Log.i(TAG, "$id came round outside what its rule asks for")
            return
        }
        // Nothing rings for a moment that is not armed.
        //
        // A place happens when it happens and has no armed moment to check; everything else has
        // one, and once it has rung the scheduler clears it. Without this, any stray delivery
        // rings again — a stale alarm from a process that has since restarted, the same
        // broadcast twice — and a timer somebody has not got round to dealing with sits there
        // going off. A catch-up says [late] and is the app itself asking on purpose.
        val armed = reminder.armedFor
        val eventDriven = rule?.trigger is Trigger.Location
        if (!eventDriven && late == null && (armed == null || armed > now.plusSeconds(EARLY_GRACE_SECONDS))) {
            Log.i(TAG, "$id has nothing armed for now (armed=$armed); ignoring a stray firing")
            return
        }
        // Two eyes on every place — the phone's geofence and the app's own watch — and one
        // arrival. Whichever sees it second is telling us what we already rang about.
        val lastFired = reminder.lastFiredAt
        if (rule?.trigger is Trigger.Location && lastFired != null && Duration.between(lastFired, now) < PLACE_ECHO) {
            Log.i(TAG, "$id already rang for this place ${Duration.between(lastFired, now).seconds}s ago")
            return
        }
        // A snooze set after the alarm was armed (from the notification, a moment ago) wins.
        val snoozed = reminder.snoozedUntil
        if (snoozed != null && snoozed > now) {
            scheduler.rearmAll()
            return
        }
        // Under ALL a moment is first of all something that happened: only the one that
        // completes the set rings, and the rest are written down and waited on.
        when (val outcome = outcomeOfFiring(reminder, ruleIndex)) {
            is FiringOutcome.Wait -> {
                Log.i(TAG, "$id noted rule $ruleIndex; still waiting for the rest")
                repository.setFiredRules(id, outcome.fired)
                scheduler.rearmAll()
                return
            }
            FiringOutcome.Ring -> Unit
        }
        Log.i(TAG, "firing $id${if (late != null) " (late)" else ""}")
        // Recorded against the moment it rang FOR, not the millisecond the alarm arrived. See
        // momentRungFor: a place is the one firing that must not reach for the armed moment,
        // because that moment belongs to whatever else the reminder is still waiting for.
        val rangFor = momentRungFor(now, reminder.armedFor, late, eventDriven)
        repository.markFired(id, rangFor)
        if (reminder.ruleMatch == RuleMatch.ALL && reminder.rulesCombine) {
            repository.setFiredRules(id, reminder.rules.indices.toSet())
        }
        AlertPresenter.show(context, reminder, firingPlan(reminder.actions), late)
        scheduler.rearmAll()
    }

    /**
     * Whether a rule's conditions allow it to ring now — two questions, asked of different sets.
     *
     * A place trigger has no armed moment behind it: nothing has ever checked its time windows,
     * so all of them are checked here. Everything else was armed for a moment the scheduler
     * already found a window for, and asking again would silence a firing the phone slept
     * through — a catch-up runs long after the window it was armed inside.
     *
     * What is asked of every trigger is the *place* conditions, because they are the ones
     * nothing could ask in advance ([knownInAdvance]): the scheduler leaves them out and arms
     * the alarm, and this is where "y sólo si estoy en casa" actually gets answered. It is
     * answered from the place watch's last fix, and only while that fix still speaks for now —
     * past [PlaceWatchPolicy.SPEED_MEMORY] it is no fix at all, and no fix means the condition
     * holds. Ringing once too often beats the reminder that never arrives.
     */
    private suspend fun conditionsHold(rule: TriggerRule, now: Instant): Boolean {
        val asked = if (rule.trigger is Trigger.Location) rule.conditions else rule.conditions.filterNot { it.knownInAdvance }
        if (asked.isEmpty()) return true
        // Where the phone is comes LAST, and only if everything a clock can settle has already
        // said yes. An hour that has passed costs nothing to check; a position costs a store
        // read of a fix somebody paid a radio for, and there is no sense paying it to find out
        // something that was already decided.
        val (places, hours) = asked.partition { it is Condition.AtPlace }
        if (!hours.allHoldAt(now, clock.zone)) return false
        if (places.isEmpty()) return true
        val where = placeWatch.read().lastFix?.takeIf { Duration.between(it.at, now) <= PlaceWatchPolicy.SPEED_MEMORY }
        return places.allHoldAt(now, clock.zone, where)
    }

    /** "Hecho": finished if nothing can ring again, otherwise just this occurrence dealt with. */
    suspend fun dismiss(id: String) {
        val reminder = repository.get(id) ?: return
        val now = clock.instant()
        val defaultTime = settingsStore.settings.first().defaultTime
        val status = statusAfterDismissal(reminder, now, clock.zone, defaultTime)
        repository.snooze(id, null)
        // A round dealt with is a round over: what had already happened stops counting.
        repository.setFiredRules(id, emptySet())
        // The moment every recurrence counts from — "six hours after the last one" is six hours
        // after this, not after whenever the alarm happened to go off.
        repository.setLastDealtAt(id, now)
        repository.setStatus(id, status)
        AlertNotifications.cancel(context, id)
        scheduler.rearmAll()
    }

    suspend fun snooze(id: String, snooze: Snooze) {
        val now = clock.instant()
        val settings = settingsStore.settings.first()
        repository.snooze(id, snooze.until(now, clock.zone, settings.weekendDay, settings.weekendTime))
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
            // The rule the moment belonged to, or the whole thing is recorded against the wrong one.
            fire(reminder.id, late = at, ruleIndex = reminder.armedRule)
        }
    }

    private companion object {
        const val TAG = "RwilcoAlarms"

        /** Inside this of the last ring, a second sighting of the same place is the same arrival. */
        val PLACE_ECHO: Duration = Duration.ofMinutes(5)

        /** Alarms arrive late, never early — but a few seconds of slack costs nothing. */
        const val EARLY_GRACE_SECONDS = 5L
    }
}
