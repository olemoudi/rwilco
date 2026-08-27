package dev.rwilco.alarm

import android.content.Context
import android.util.Log
import dev.rwilco.data.ReminderRepository
import dev.rwilco.diag.Diag
import dev.rwilco.data.SettingsStore
import dev.rwilco.model.dayShape
import dev.rwilco.model.AppSettings
import dev.rwilco.model.FiringOutcome
import dev.rwilco.model.FiringPlan
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
import dev.rwilco.model.lateForPresentation
import dev.rwilco.model.nextSoundIn
import dev.rwilco.model.firingPlan
import dev.rwilco.model.missedFire
import dev.rwilco.model.momentRungFor
import dev.rwilco.model.outcomeOfFiring
import dev.rwilco.model.owedUnderAll
import dev.rwilco.model.holdsAt
import dev.rwilco.model.sideOf
import dev.rwilco.model.presenceAlreadyRang
import dev.rwilco.model.rulesCombine
import dev.rwilco.model.statusAfterDismissal
import dev.rwilco.notify.AlertNotifications
import dev.rwilco.notify.AlertPresenter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val repeater = SoundRepeater(context)

    /**
     * One decision at a time. A firing is a read, a decision and a write, and two of them for
     * the same reminder can arrive within the same second through different doors: the alarm
     * a re-arm set for a moment already in the past arrives at once, while the catch-up that
     * found that same moment is on its way to ring it; the geofence and the place watch both
     * see one arrival. Run side by side, both read a row that says "not rung yet" and both
     * ring. Run one after the other, the second reads what the first wrote and stands down.
     */
    private val lock = Mutex()

    /**
     * The moment arrived. [late] is when it should have arrived, if the phone slept through it;
     * [ruleIndex] says which rule rang, which is what lets a place be judged against the
     * conditions on it ("al llegar a casa, y sólo si es por la tarde"). An alarm needs no such
     * check: the moment it was armed for already satisfied them.
     */
    suspend fun fire(id: String, late: Instant? = null, ruleIndex: Int? = null) = lock.withLock {
        val reminder = repository.get(id) ?: return@withLock Diag.note(TAG_DIAG, "r=${short(id)} gone")
        if (reminder.status != Status.ACTIVE) return@withLock Diag.note(TAG_DIAG, "r=${short(id)} not active (${reminder.status})")
        val now = clock.instant()
        // A catch-up is decided from a row read before the re-arm; by the time it gets here the
        // moment it is about may have rung on its own — the alarm for a past moment arrives at
        // once — and ringing it again is exactly the double the lock above exists to stop.
        val fired = reminder.lastFiredAt
        if (late != null && fired != null && !fired.isBefore(late)) {
            Log.i(TAG, "$id already rang for the moment the catch-up is about")
            Diag.note(TAG_DIAG, "r=${short(id)} catch-up dropped: already rang $fired for $late")
            return@withLock
        }
        // A place is judged when it happens; a moment was judged when it was armed. Judging an
        // alarm again here would silence a firing the phone slept through — the catch-up runs
        // long after the window it was armed inside.
        val rule = ruleIndex?.let { reminder.rules.getOrNull(it) }
        // Under "a la vez" the rule is judged with every other one folded into it as a state:
        // the moment this one happened is only a firing if all of them are true then. A null
        // is a set that cannot hold at all — two instants asked to coincide.
        val judged = ruleIndex?.let { reminder.togetherRule(it) }
        // Every way of NOT ringing below re-arms before it leaves. The alarm that brought us
        // here is spent, and a drop that left nothing behind was a reminder silent until the
        // six-hourly net came round — or for ever, if the process died first.
        if (ruleIndex != null && judged == null) {
            Log.i(TAG, "$id asks for two moments at once, which never happens")
            Diag.note(TAG_DIAG, "r=${short(id)} dropped: two moments at once (rule $ruleIndex)")
            scheduler.rearmAll()
            return@withLock
        }
        if (judged != null && !conditionsHold(judged, now)) {
            Log.i(TAG, "$id came round outside what its rule asks for")
            Diag.note(TAG_DIAG, "r=${short(id)} dropped: conditions of rule $ruleIndex do not hold")
            scheduler.rearmAll()
            return@withLock
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
            Diag.note(TAG_DIAG, "r=${short(id)} dropped: nothing armed (armed=$armed now=$now rule=$ruleIndex)")
            // A clock set back past the grace lands here too: the moment is still ahead by the
            // new clock, and re-arming is what rings it when it comes.
            scheduler.rearmAll()
            return@withLock
        }
        // Two eyes on every place — the phone's geofence and the app's own watch — and one
        // arrival. Whichever sees it second is telling us what we already rang about.
        val lastFired = reminder.lastFiredAt
        val place = rule?.trigger as? Trigger.Location
        if (place != null && lastFired != null && Duration.between(lastFired, now) < PLACE_ECHO) {
            Log.i(TAG, "$id already rang for this place ${Duration.between(lastFired, now).seconds}s ago")
            Diag.note(TAG_DIAG, "r=${short(id)} dropped: place echo ${Duration.between(lastFired, now).seconds}s after the last ring")
            return@withLock
        }
        // A state rings once a round; a crossing rings for every doorway. See presenceAlreadyRang.
        if (place != null && reminder.presenceAlreadyRang(place)) {
            Log.i(TAG, "$id already rang for being there; waiting for the round to start again")
            Diag.note(TAG_DIAG, "r=${short(id)} dropped: state place already rang at $lastFired")
            return@withLock
        }
        // A snooze set after the alarm was armed (from the notification, a moment ago) wins.
        val snoozed = reminder.snoozedUntil
        if (snoozed != null && snoozed > now) {
            Diag.note(TAG_DIAG, "r=${short(id)} dropped: snoozed until $snoozed")
            scheduler.rearmAll()
            return@withLock
        }
        // Under ALL a moment is first of all something that happened: only the one that
        // completes the set rings, and the rest are written down and waited on.
        when (val outcome = outcomeOfFiring(reminder, ruleIndex)) {
            is FiringOutcome.Wait -> {
                Log.i(TAG, "$id noted rule $ruleIndex; still waiting for the rest")
                Diag.note(TAG_DIAG, "r=${short(id)} rule $ruleIndex happened; waiting for ${outcome.fired}")
                repository.setFiredRules(id, outcome.fired)
                scheduler.rearmAll()
                return@withLock
            }
            FiringOutcome.Ring -> Unit
        }
        Log.i(TAG, "firing $id${if (late != null) " (late)" else ""}")
        // Everything the showing needs is read BEFORE the moment is spent. A settings read
        // that failed between markFired and the notification spent a moment nothing ever
        // showed — and missedFire could never tell, because the row said it had rung. The
        // defaults are a fine way to ring; a swallowed exception is not.
        val settings = runCatching { settingsStore.settings.first() }.getOrElse {
            Log.e(TAG, "settings would not read; ringing with the defaults", it)
            AppSettings()
        }
        val plan = firingPlan(reminder.actions)
        // Recorded against the moment it rang FOR, not the millisecond the alarm arrived. See
        // momentRungFor: a place is the one firing that must not reach for the armed moment,
        // because that moment belongs to whatever else the reminder is still waiting for.
        val rangFor = momentRungFor(now, reminder.armedFor, late, eventDriven)
        Diag.note(TAG_DIAG, "r=${short(id)} RANG for $rangFor rule=$ruleIndex${if (late != null) " (late for $late)" else ""} plan=${plan.summary()}")
        repository.markFired(id, rangFor)
        if (reminder.ruleMatch == RuleMatch.ALL && reminder.rulesCombine) {
            repository.setFiredRules(id, reminder.rules.indices.toSet())
        }
        // A moment the phone slept through by a minute or two is still that moment, and rings
        // like it; only one it slept through by a good while arrives as the quiet "did not ring
        // on time" note (lateForPresentation).
        AlertPresenter.show(context, reminder, plan, lateForPresentation(late, now), settings.vibration, settings.alertSound, ruleIndex = ruleIndex)
        // "Hasta que reciba caso": the first play has gone out, so line up the second.
        if (plan.insistent) {
            nextSoundIn(played = 1, plays = settings.soundPlays, gapMinutes = settings.soundGapMinutes)
                ?.let { gap -> repeater.schedule(id, played = 1, rangAt = rangFor, at = now + gap, ruleIndex = ruleIndex) }
        }
        scheduler.rearmAll()
    }

    /**
     * The same sound again, a few minutes on, because nobody has dealt with the reminder yet.
     *
     * Everything that could have ended the round is asked here rather than remembered: gone,
     * paused, finished, snoozed, or dealt with since it rang. The notification is re-posted
     * rather than a fresh sound played — it re-alerts on its own channel, which is the sound,
     * and it puts the card back in front of somebody who has scrolled past it. Never the
     * full-screen takeover: once was the alarm, this is the reminder of the alarm.
     */
    suspend fun playAgain(id: String, played: Int, rangAt: Instant, ruleIndex: Int? = null) = lock.withLock {
        val reminder = repository.get(id) ?: return@withLock
        val now = clock.instant()
        if (reminder.status != Status.ACTIVE) return@withLock
        val dealt = reminder.lastDealtAt
        if (dealt != null && !dealt.isBefore(rangAt)) return@withLock
        val snoozed = reminder.snoozedUntil
        if (snoozed != null && snoozed > now) return@withLock
        val settings = settingsStore.settings.first()
        val plan = firingPlan(reminder.actions)
        if (!plan.insistent) return@withLock
        Log.i(TAG, "$id has not been dealt with; play ${played + 1} of ${settings.soundPlays}")
        AlertPresenter.show(context, reminder, plan, late = null, vibration = settings.vibration, sound = settings.alertSound, takeScreen = false, ruleIndex = ruleIndex)
        nextSoundIn(played + 1, settings.soundPlays, settings.soundGapMinutes)
            ?.let { gap -> repeater.schedule(id, played + 1, rangAt, now + gap, ruleIndex) }
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
        val watch = placeWatch.read()
        val where = watch.lastFix?.takeIf { Duration.between(it.at, now) <= PlaceWatchPolicy.SPEED_MEMORY }
        // **Ask the watch, not the fix.** The watch keeps which side of every circle it last saw
        // the phone on, and that memory knows two things a raw measurement does not: what the
        // system's geofences reported (a crossing writes straight into it, with no fix of its
        // own) and which way its own doubt was resolved. Measuring the fix again instead was a
        // second, worse opinion — and on a fifty-metre circle, the tightest the app allows and
        // smaller than an ordinary network fix is accurate, it resolved to "yes" wherever the
        // phone was. A reminder rang twenty minutes after the phone's geofences had said the
        // phone had gone.
        //
        // Only while a fix still speaks for now, though. Past that the memory is old news like
        // everything else, and the house rule takes over: what nobody can vouch for holds,
        // because the failure somebody notices is the one that never arrives.
        return places.all { condition ->
            val circle = condition as Condition.AtPlace
            val remembered = if (where != null) watch.sideOf(circle.lat, circle.lng, circle.radiusM) else null
            if (remembered != null) remembered == circle.inside else condition.holdsAt(now, clock.zone, where)
        }
    }

    /**
     * A rule of a "todos" set stops being met: the phone walked back into the place it had left.
     *
     * The other three readings of a list of rules have nothing like this, and neither did this
     * one until a place under "todos" was read for what it is — a *state*. "Cuando salga de la
     * oficina, y de 18:30 a 20:00" is met by being out of the office, and somebody who goes back
     * for their keys is not out of it any more; ticking that off for good and ringing at 18:30
     * would be answering a question nobody asked. So the tick comes back off, the set is waiting
     * on that rule again, and the alarms are re-armed around what is left.
     *
     * Nothing else here can come undone: a date that has passed has passed. Only a place, only
     * under "todos", and only from the crossing opposite the one the rule waits for — which is
     * what the watch hands over ([Crossing.TAKES_BACK]).
     */
    suspend fun untick(id: String, ruleIndex: Int) = lock.withLock {
        val reminder = repository.get(id) ?: return@withLock
        if (reminder.status != Status.ACTIVE) return@withLock
        if (reminder.ruleMatch != RuleMatch.ALL || !reminder.rulesCombine) return@withLock
        if (ruleIndex !in reminder.firedRules) return@withLock
        Diag.note(TAG_DIAG, "r=${short(id)} rule $ruleIndex is not met any more")
        Log.i(TAG, "$id rule $ruleIndex came undone")
        repository.setFiredRules(id, reminder.firedRules - ruleIndex)
        scheduler.rearmAll()
    }

    /**
     * "Hecho": finished if nothing can ring again, otherwise just this occurrence dealt with.
     *
     * The noise and the notification go first, and go whether or not the reminder still exists:
     * the buttons on a notification outlive the row they were posted for (the reminder was
     * deleted from Home with the card still in the shade), and "Hecho" on one of those has to
     * take it down rather than leave a button that does nothing.
     */
    suspend fun dismiss(id: String) = lock.withLock {
        Diag.note(TAG_DIAG, "r=${short(id)} dealt with")
        repeater.cancel(id)
        AlertNotifications.cancel(context, id)
        val reminder = repository.get(id) ?: return@withLock
        val now = clock.instant()
        val settings = settingsStore.settings.first()
        val status = statusAfterDismissal(reminder, now, clock.zone, settings.defaultTime, settings.dayShape)
        repository.snooze(id, null)
        // A round dealt with is a round over: what had already happened stops counting.
        repository.setFiredRules(id, emptySet())
        // The moment every recurrence counts from — "six hours after the last one" is six hours
        // after this, not after whenever the alarm happened to go off.
        repository.setLastDealtAt(id, now)
        repository.setStatus(id, status)
        scheduler.rearmAll()
    }

    suspend fun snooze(id: String, snooze: Snooze) = lock.withLock {
        Diag.note(TAG_DIAG, "r=${short(id)} snoozed ($snooze)")
        repeater.cancel(id)
        AlertNotifications.cancel(context, id)
        if (repository.get(id) == null) return@withLock
        val now = clock.instant()
        val settings = settingsStore.settings.first()
        repository.snooze(id, snooze.until(now, clock.zone, settings.weekendDay, settings.weekendTime))
        scheduler.rearmAll()
    }

    /**
     * Re-arms everything and speaks up about what was missed while the phone was off. Used at
     * launch, after a reboot, and by the safety-net worker — not on every edit, where a missed
     * firing is not news.
     */
    suspend fun rearmAndCatchUp() {
        val missed = scheduler.rearmAll()
        val settings = settingsStore.settings.first()
        for (reminder in missed) {
            val at = missedFire(reminder, clock.instant()) ?: continue
            // The rule the moment belonged to, or the whole thing is recorded against the wrong one.
            fire(reminder.id, late = at, ruleIndex = reminder.armedRule)
            // Under ALL only the earliest pending moment is ever armed, and the next only once
            // the first is written down. A phone off across two of them wakes owing both, and
            // the second — never armed, so never "missed" — would otherwise leave the set
            // waiting for something that has already happened. Each is fired in turn; the
            // last one to complete the set rings.
            var left = reminder.rules.size
            while (left-- > 0) {
                val current = repository.get(reminder.id) ?: break
                val owed = owedUnderAll(current, at, clock.instant(), clock.zone, settings.defaultTime, settings.dayShape).firstOrNull() ?: break
                fire(reminder.id, late = owed.at, ruleIndex = owed.ruleIndex)
            }
        }
    }

    private companion object {
        const val TAG = "RwilcoAlarms"
        const val TAG_DIAG = "fire"

        /** Eight characters of a UUID: enough to follow one reminder through a report. */
        fun short(id: String): String = id.take(8)

        /** `FS+N+S+V`, which is what a firing is actually asked to do. */
        fun FiringPlan.summary(): String = buildString {
            if (fullScreen) append("FS+")
            if (notification) append("N+")
            if (sound) append(if (insistent) "S!+" else "S+")
            if (vibrate) append("V+")
        }.trimEnd('+').ifEmpty { "nothing" }

        /** Inside this of the last ring, a second sighting of the same place is the same arrival. */
        val PLACE_ECHO: Duration = Duration.ofMinutes(5)

        /** Alarms arrive late, never early — but a few seconds of slack costs nothing. */
        const val EARLY_GRACE_SECONDS = 5L
    }
}
