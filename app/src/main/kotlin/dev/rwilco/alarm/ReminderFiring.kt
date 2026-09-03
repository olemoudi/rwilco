package dev.rwilco.alarm

import android.content.Context
import android.util.Log
import dev.rwilco.data.FiringKind
import dev.rwilco.data.ReminderRepository
import dev.rwilco.diag.Diag
import dev.rwilco.data.SettingsStore
import dev.rwilco.model.dayShape
import dev.rwilco.model.hushedByTheHour
import dev.rwilco.model.hushed
import dev.rwilco.model.AppSettings
import dev.rwilco.model.FiringOutcome
import dev.rwilco.model.FiringPlan
import dev.rwilco.model.Recurrence
import dev.rwilco.model.NET_GAIN
import dev.rwilco.model.netSpeaksAloud
import dev.rwilco.notify.AlertAudio
import dev.rwilco.notify.Sounds
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.spanHasTakenOver
import dev.rwilco.model.Snooze
import dev.rwilco.model.Status
import dev.rwilco.model.Trigger
import dev.rwilco.geo.PlaceWatchStore
import dev.rwilco.model.Condition
import dev.rwilco.model.PlaceWatchPolicy
import dev.rwilco.model.allHoldAt
import dev.rwilco.model.awaitingAnswer
import dev.rwilco.model.ruleInSet
import dev.rwilco.model.knownInAdvance
import dev.rwilco.model.lateForPresentation
import dev.rwilco.model.nextSoundIn
import dev.rwilco.model.soundFor
import dev.rwilco.model.firingPlan
import dev.rwilco.model.isPlaceEcho
import dev.rwilco.model.missedFire
import dev.rwilco.model.netDue
import dev.rwilco.diag.diagLine
import dev.rwilco.model.PlaceWatchState
import dev.rwilco.model.momentDealtWith
import dev.rwilco.model.momentRungFor
import dev.rwilco.model.outcomeOfFiring
import dev.rwilco.model.owedUnderAll
import dev.rwilco.model.holdsAt
import dev.rwilco.model.sideOf
import dev.rwilco.model.speaksFor
import dev.rwilco.model.presenceAlreadyRang
import dev.rwilco.model.rulesCombine
import dev.rwilco.model.statusAfterDismissal
import dev.rwilco.notify.AlertNotifications
import dev.rwilco.notify.AlertPresenter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
import dev.rwilco.model.notificationSnoozeOffers
import dev.rwilco.model.Fix
import dev.rwilco.model.GeofenceIds
import dev.rwilco.model.Transition
import dev.rwilco.model.distanceMeters
import dev.rwilco.model.snoozeDetail

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
    suspend fun fire(id: String, late: Instant? = null, ruleIndex: Int? = null, viaSnoozePlace: Boolean = false) = lock.withLock {
        val reminder = repository.get(id) ?: return@withLock Diag.note(TAG_DIAG, "r=${short(id)} gone")
        if (reminder.status != Status.ACTIVE) return@withLock Diag.note(TAG_DIAG, "r=${short(id)} not active (${reminder.status})")
        // The crossing a "cuando llegue a…" waits for. A fence outlives the snooze it was set
        // for by as long as a sync takes, so a crossing for a snooze no longer waiting is nothing.
        if (viaSnoozePlace && reminder.snoozedToPlace == null) return@withLock Diag.note(TAG_DIAG, "r=${short(id)} dropped: a place crossing for a snooze no longer waiting")
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
        // Everything the showing needs is read BEFORE the moment is spent. A settings read
        // that failed between markFired and the notification spent a moment nothing ever
        // showed — and missedFire could never tell, because the row said it had rung. Read
        // this early because the fold below needs the shape of the day too.
        val settings = settings()
        // Under "a la vez" the rule is judged with every other one folded into it as a state:
        // the moment this one happened is only a firing if all of them are true then. A null
        // is a set that cannot hold at all — two instants asked to coincide.
        val judged = ruleIndex?.let { reminder.ruleInSet(it, settings.dayShape) }
        // Every way of NOT ringing below re-arms before it leaves. The alarm that brought us
        // here is spent, and a drop that left nothing behind was a reminder silent until the
        // six-hourly net came round — or for ever, if the process died first.
        //
        // Spent HERE, in so many words: a re-arm pass holds a moment that is armed and has not
        // been answered (ReminderScheduler.rearmAll), so a moment judged and found wanting has
        // to be written off before the pass, or it would be held — and re-judged, and dropped —
        // for ever, with the next one never armed. Only the moment this delivery is about, and
        // never for a place: a place has no armed moment of its own, and the one on the row
        // belongs to whatever else the reminder is waiting for.
        val eventDriven = rule?.trigger is Trigger.Location || viaSnoozePlace
        suspend fun spendArmed() {
            val armedNow = reminder.armedFor ?: return
            if (!eventDriven && armedNow <= now.plusSeconds(EARLY_GRACE_SECONDS)) repository.setArmedFor(id, null, null)
        }
        if (ruleIndex != null && judged == null) {
            Log.i(TAG, "$id asks for two moments at once, which never happens")
            Diag.note(TAG_DIAG, "r=${short(id)} dropped: two moments at once (rule $ruleIndex)")
            spendArmed()
            scheduler.rearmAll()
            return@withLock
        }
        // "Justo el plazo" has taken the rules out of the loop: the reminder rings on the span's
        // own moment now and nothing of theirs decides anything. A clock rule cannot reach here
        // (nothing arms it any more), but a place has no armed moment by design — so a circle
        // still registered with the system would go on ringing, once a round, for ever. The
        // watch and the fences drop it too; this is the door they all come through. See
        // [Reminder.spanHasTakenOver].
        if (ruleIndex != null && reminder.spanHasTakenOver) {
            Log.i(TAG, "$id comes back on its span now; rule $ruleIndex no longer decides")
            Diag.note(TAG_DIAG, "r=${short(id)} dropped: the span has taken over (rule $ruleIndex)")
            spendArmed()
            scheduler.rearmAll()
            return@withLock
        }
        val failed = judged?.let { firstFailing(it.conditions, askAll = it.trigger is Trigger.Location, now, moment = late ?: now) }
        if (failed != null) {
            Log.i(TAG, "$id came round outside what its rule asks for")
            // **Which** condition, not just that one of them said no (0.78.0). "conditions of
            // rule 1 do not hold" is the same line whether the hour was wrong, the day was
            // wrong or the phone was somewhere else — three completely different bugs — and
            // the one that turned up in anger took a watch log and an afternoon to tell apart.
            Diag.note(TAG_DIAG, "r=${short(id)} dropped: rule $ruleIndex wants ${failed.said(placeWatch.read())}")
            spendArmed()
            scheduler.rearmAll()
            return@withLock
        }
        // A calendar's own "y sólo si" ("todos los lunes a las 9, y sólo si estoy en casa") is
        // a moment with no rule behind it, and its place fences — the ones nothing could ask
        // in advance — were asked by nobody: the calendar rang wherever the phone was. A snooze
        // has no rule behind it either, and is not asked: it is the person's own "not now,
        // then" about a ring that already happened.
        val calendarFences = (reminder.recurrence as? Recurrence.Calendar)?.conditions.orEmpty()
            .takeIf { ruleIndex == null && reminder.snoozedUntil == null && !viaSnoozePlace }.orEmpty()
        val calendarFailed = firstFailing(calendarFences, askAll = false, now, moment = late ?: now)
        if (calendarFences.isNotEmpty() && calendarFailed != null) {
            Log.i(TAG, "$id came round outside what its calendar asks for")
            Diag.note(TAG_DIAG, "r=${short(id)} dropped: the calendar wants ${calendarFailed.said(placeWatch.read())}")
            spendArmed()
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
        if (!eventDriven && late == null && (armed == null || armed > now.plusSeconds(EARLY_GRACE_SECONDS))) {
            Log.i(TAG, "$id has nothing armed for now (armed=$armed); ignoring a stray firing")
            Diag.note(TAG_DIAG, "r=${short(id)} dropped: nothing armed (armed=$armed now=$now rule=$ruleIndex)")
            // A clock set back past the grace lands here too: the moment is still ahead by the
            // new clock, and re-arming is what rings it when it comes.
            scheduler.rearmAll()
            return@withLock
        }
        // Two eyes on every place — the phone's geofence and the app's own watch — and one
        // arrival. Whichever sees it second is telling us what we already rang about. But only
        // an echo of THIS circle's ring, or of a ring with no rule behind it (the reminder
        // itself: a snooze's crossing, a recurrence): a sibling rule's nine o'clock must not
        // silence a genuine arrival three minutes later. See isPlaceEcho (core-model).
        val lastFired = reminder.lastFiredAt
        val place = rule?.trigger as? Trigger.Location
        if (place != null && isPlaceEcho(lastFired, reminder.lastFiredRule, ruleIndex, now)) {
            Log.i(TAG, "$id already rang for this place ${Duration.between(lastFired, now).seconds}s ago")
            Diag.note(TAG_DIAG, "r=${short(id)} dropped: place echo ${Duration.between(lastFired, now).seconds}s after the last ring (rule ${reminder.lastFiredRule})")
            scheduler.rearmAll()
            return@withLock
        }
        // A state rings once a round; a crossing rings for every doorway. See presenceAlreadyRang.
        if (place != null && ruleIndex != null && reminder.presenceAlreadyRang(place, ruleIndex)) {
            Log.i(TAG, "$id already rang for being there; waiting for the round to start again")
            Diag.note(TAG_DIAG, "r=${short(id)} dropped: state place already rang at $lastFired")
            scheduler.rearmAll()
            return@withLock
        }
        // A snooze set after the alarm was armed (from the notification, a moment ago) wins.
        // With the same few seconds of slack the armed-moment check above gives: the snooze's
        // own alarm arriving a second early is not a snooze still ahead.
        val snoozed = reminder.snoozedUntil
        if (snoozed != null && snoozed > now.plusSeconds(EARLY_GRACE_SECONDS)) {
            Diag.note(TAG_DIAG, "r=${short(id)} dropped: snoozed until $snoozed")
            scheduler.rearmAll()
            return@withLock
        }
        // Waiting at a place outranks every rule the same way: nothing but that crossing rings
        // it. Nothing on the clock is armed while it waits, so this is a stray delivery.
        if (!viaSnoozePlace && reminder.snoozedToPlace != null) {
            Diag.note(TAG_DIAG, "r=${short(id)} dropped: waiting at a place")
            scheduler.rearmAll()
            return@withLock
        }
        // Under ALL a moment is first of all something that happened: only the one that
        // completes the set rings, and the rest are written down and waited on.
        when (val outcome = outcomeOfFiring(reminder, ruleIndex)) {
            is FiringOutcome.Wait -> {
                Log.i(TAG, "$id noted rule $ruleIndex; still waiting for the rest")
                Diag.note(TAG_DIAG, "r=${short(id)} rule $ruleIndex happened; waiting for ${outcome.fired}")
                // The note first, the spend second: a process dying between the two leaves a
                // moment still owed, which the catch-up notes again (harmless) — the other
                // order left one spent and never noted, a set waiting for a moment gone by.
                repository.setFiredRules(id, outcome.fired)
                spendArmed()
                scheduler.rearmAll()
                return@withLock
            }
            FiringOutcome.Ring -> Unit
        }
        Log.i(TAG, "firing $id${if (late != null) " (late)" else ""}")
        // Recorded against the moment it rang FOR, not the millisecond the alarm arrived. See
        // momentRungFor: a place is the one firing that must not reach for the armed moment,
        // because that moment belongs to whatever else the reminder is still waiting for.
        val rangFor = momentRungFor(now, reminder.armedFor, late, eventDriven)
        // **The hours somebody is asleep take the noise out of everything**, whatever its tiles
        // say, and they do it by the moment the firing is *for* as well as by the hour it
        // arrives — see [hushedByTheHour]. The rehearsal is the one exemption there can be:
        // somebody pressing "probar una alerta" is asking whether the noise works, and the one
        // answer that must never be silence is that one.
        val asleep = !TestAlert.isTest(id) &&
            hushedByTheHour(rangFor, now, clock.zone, settings.dayShape)
        val plan = firingPlan(reminder.actions).let { if (asleep) it.hushed() else it }
        Diag.note(TAG_DIAG, "r=${short(id)} RANG for $rangFor rule=$ruleIndex${if (late != null) " (late for $late)" else ""}${if (viaSnoozePlace) " (place snooze)" else ""} plan=${plan.summary()}")
        // From the sello to the screen in one piece: the receiver runs this under a timeout, and
        // a cancellation landing between markFired and show spent a moment nothing ever showed
        // — one missedFire could never see again, because the row said it had rung.
        withContext(NonCancellable) {
            repository.markFired(id, rangFor, ruleIndex)
            if (reminder.ruleMatch == RuleMatch.ALL && reminder.rulesCombine) {
                repository.setFiredRules(id, reminder.rules.indices.toSet())
            }
            // A moment the phone slept through by a minute or two is still that moment, and
            // rings like it; only one it slept through by a good while arrives as the quiet
            // "did not ring on time" note (lateForPresentation).
            val presentedLate = lateForPresentation(late, now)
            repository.record(id, if (presentedLate != null) FiringKind.MISSED else FiringKind.RANG, now, ruleIndex)
            try {
                // The row read above still carries the place this ring is the end of; shown as
                // is, the notification for the arrival read "pospuesto hasta llegar a casa".
                AlertPresenter.show(context, if (viaSnoozePlace) reminder.copy(snoozedToPlace = null) else reminder, plan, presentedLate, settings.vibration, settings.soundFor(plan), ruleIndex = ruleIndex, defaultTime = settings.defaultTime, snoozes = settings.notificationSnoozeOffers, customMinutes = settings.snoozeCustomMinutes)
                // "Hasta que reciba caso": the first play has gone out, so line up the second.
                if (plan.insistent) {
                    nextSoundIn(played = 1, plays = settings.soundPlays, gapMinutes = settings.soundGapMinutes)
                        ?.let { gap -> repeater.schedule(id, played = 1, rangAt = rangFor, at = now + gap, ruleIndex = ruleIndex) }
                }
            } catch (t: Throwable) {
                // The moment is spent whatever happened on the way to the screen; what must not
                // be lost with it is the NEXT one, which the re-arm below is the only thing
                // that sets. Every other exit from here re-arms before it leaves.
                Log.e(TAG, "showing $id failed", t)
                Diag.note(TAG_DIAG, "r=${short(id)} show FAILED ${t::class.simpleName}")
            } finally {
                scheduler.rearmAll()
            }
        }
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
        if (reminder.snoozedToPlace != null) return@withLock
        val settings = settings()
        val plan = firingPlan(reminder.actions)
        if (!plan.insistent) return@withLock
        // A round of "hasta que reciba caso" started in the evening can still be going at two
        // in the morning, and the whole of a repeat is the sound: hushed, there is nothing left
        // for it to do. So the chain simply ends — the card the first firing left is still in
        // the tray, and the reminder is still owed an answer in the morning.
        if (!TestAlert.isTest(id) && hushedByTheHour(rangAt, now, clock.zone, settings.dayShape)) {
            Diag.note(TAG_DIAG, "r=${short(id)} repeat dropped (asleep)")
            return@withLock
        }
        Log.i(TAG, "$id has not been dealt with; play ${played + 1} of ${settings.soundPlays}")
        AlertPresenter.show(context, reminder, plan, late = null, vibration = settings.vibration, sound = settings.soundFor(plan), takeScreen = false, ruleIndex = ruleIndex, defaultTime = settings.defaultTime, snoozes = settings.notificationSnoozeOffers, customMinutes = settings.snoozeCustomMinutes)
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
     * answered from the place watch's last fix, and only while that fix still speaks for the
     * [moment] being asked about — past [PlaceWatchPolicy.SPEED_MEMORY] either side of it, it
     * is no fix at all, and no fix means the condition holds. Ringing once too often beats the
     * reminder that never arrives.
     *
     * [moment] is [now] for a live firing and the missed moment for a catch-up, and that is
     * the whole reason it is a parameter: "a las nueve, y sólo si estoy en casa", slept through
     * and caught up at noon from the office, is a question about nine o'clock, and a fix taken
     * at noon does not answer it. Asked of now, it said no, and a moment nobody could vouch for
     * was dropped for good.
     */
    /**
     * The condition, as the log should name it: what it asks for, in the diagnostics report's
     * own words ([diagLine]) — and for a place, what the watch believes on top of it, since
     * "you were not there" and "nothing could say where you were" are different answers.
     */
    private fun Condition.said(watch: PlaceWatchState): String = when (this) {
        is Condition.AtPlace -> diagLine() + " (watch says " +
            (watch.sideOf(lat, lng, radiusM, inside)?.let { if (it) "in" else "out" } ?: "nothing") + ")"
        else -> diagLine()
    }

    /**
     * The first condition that says no, or null when every one of them holds — the same walk
     * [conditionsHold] takes, kept apart so the drop can be *named* in the log. Which one it is
     * is the whole difference between "you were somewhere else", "it was the wrong hour" and
     * "it was the wrong day", and the log used to say the same eleven words for all three.
     */
    private suspend fun firstFailing(conditions: List<Condition>, askAll: Boolean, now: Instant, moment: Instant): Condition? {
        val asked = if (askAll) conditions else conditions.filterNot { it.knownInAdvance }
        if (asked.isEmpty()) return null
        val (places, hours) = asked.partition { it is Condition.AtPlace }
        hours.firstOrNull { !it.holdsAt(now, clock.zone) }?.let { return it }
        if (places.isEmpty()) return null
        val watch = placeWatch.read()
        val where = watch.lastFix?.takeIf { it.speaksFor(moment) }
        return places.firstOrNull { condition ->
            val circle = condition as Condition.AtPlace
            val remembered = if (where != null) watch.sideOf(circle.lat, circle.lng, circle.radiusM, circle.inside) else null
            val holds = if (remembered != null) remembered == circle.inside else condition.holdsAt(now, clock.zone, where)
            !holds
        }
    }

    private suspend fun conditionsHold(conditions: List<Condition>, askAll: Boolean, now: Instant, moment: Instant): Boolean {
        val asked = if (askAll) conditions else conditions.filterNot { it.knownInAdvance }
        if (asked.isEmpty()) return true
        // Where the phone is comes LAST, and only if everything a clock can settle has already
        // said yes. An hour that has passed costs nothing to check; a position costs a store
        // read of a fix somebody paid a radio for, and there is no sense paying it to find out
        // something that was already decided.
        val (places, hours) = asked.partition { it is Condition.AtPlace }
        if (!hours.allHoldAt(now, clock.zone)) return false
        if (places.isEmpty()) return true
        val watch = placeWatch.read()
        val where = watch.lastFix?.takeIf { it.speaksFor(moment) }
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
            val remembered = if (where != null) watch.sideOf(circle.lat, circle.lng, circle.radiusM, circle.inside) else null
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
        repository.record(id, FiringKind.UNTICKED, ruleIndex = ruleIndex)
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
        // A rehearsal ("probar una alerta") is not a thing that got done: it goes, rather than
        // landing in "Hechos" where the week is counted.
        if (TestAlert.isTest(id)) {
            repository.delete(id)
            scheduler.rearmAll()
            return@withLock
        }
        val now = clock.instant()
        val settings = settings()
        // **A "hecho" deals with whatever is owed.** Usually that is the firing waiting for an
        // answer. When nothing is — the card says "mañana a las 14:00" and somebody ticks it off
        // this morning — what is being dealt with is that moment, so it is spent and the next
        // one is the day after. Ticking it off again sends it on another day, which is the whole
        // of what somebody means by doing it twice. Only when nothing is waiting: after it rings,
        // the ring IS what is being answered, and taking tomorrow's with it would skip a day.
        val consumed = reminder.momentDealtWith(now, clock.zone, settings.defaultTime, settings.dayStart, settings.dayShape)
        // Asked of the reminder as this "hecho" leaves it — the anchor stamped too, not only the
        // moment spent. Without the anchor a calendar in "Vuelve" beside a date already gone
        // had no rest to come back from (restUntil reads lastDealtAt), so "el 26 a las 20:00,
        // y vuelve cada mes" was finished by the first "hecho" it ever got. And the last date
        // of a series dealt with ahead of time still finishes it rather than waiting for a
        // moment nobody is going to be told about.
        val dealt = reminder.copy(lastDealtAt = now, dealtThrough = consumed ?: reminder.dealtThrough)
        val status = statusAfterDismissal(dealt, now, clock.zone, settings.defaultTime, settings.dayShape)
        // One write: the snooze goes, a round dealt with is a round over (what had already
        // happened stops counting), and the moment every recurrence counts from is stamped —
        // "six hours after the last one" is six hours after this, not after whenever the alarm
        // happened to go off. Four writes could be cut in two by a process dying, and a round
        // closed with its anchor unmoved is a reminder that never comes back.
        // A "hecho" that spends no moment (an answer to a ring) keeps what was dealt with ahead:
        // written as null it wiped the rounds already skipped, and they came back.
        repository.dealtWith(id, now, status, consumed ?: reminder.dealtThrough)
        // The word for what this was: an answer to a ring, or a round of something that comes
        // back let pass ahead of it. A one-off finished ahead of its moment is still "hecho".
        val skipped = consumed != null && reminder.recurrence != Recurrence.None && !reminder.awaitingAnswer(now)
        repository.record(id, if (skipped) FiringKind.SKIPPED else FiringKind.DEALT, now)
        scheduler.rearmAll()
    }

    /**
     * The safety net's word: one quiet notification about a firing nobody ever answered.
     *
     * Everything is asked again here rather than trusted from when the alarm was armed, because
     * the net waits a long time by design — a day, on something that is not coming back — and
     * every answer there is happens inside that wait: dealt with, paused, put off, or rung
     * again, which starts a fresh net rather than this one. `nudgeAt` is the single place all
     * of that is decided, so the scheduler and this agree by construction.
     *
     * The row is written before the notification goes out, which is the same order every other
     * door here uses: a word said twice about one firing is the nagging this exists not to be,
     * and a word that failed to post is quieter than the app intended rather than louder.
     */
    suspend fun nudge(id: String) = lock.withLock {
        val reminder = repository.get(id) ?: return@withLock Diag.note(TAG_DIAG, "r=${short(id)} gone")
        val now = clock.instant()
        val settings = settings()
        val due = reminder.netDue(now, clock.zone, settings.defaultTime, settings.safetyNet, settings.dayStart, settings.dayShape)
        if (due == null || due.at > now.plusSeconds(EARLY_GRACE_SECONDS)) {
            Log.i(TAG, "$id: the safety net has nothing to say (due=${due?.at})")
            Diag.note(TAG_DIAG, "r=${short(id)} net dropped (due=${due?.at} now=$now)")
            scheduler.rearmAll()
            return@withLock
        }
        Log.i(TAG, "safety net for $id, about the moment at ${due.about} (${due.word})")
        Diag.note(TAG_DIAG, "r=${short(id)} NET said (${due.word}), about the moment at ${due.about}")
        repository.setNudgedAt(id, now)
        repository.record(id, FiringKind.NET, now, detail = due.word.name)
        AlertNotifications.post(
            context = context,
            reminder = reminder,
            // Nothing it was asked to do when it rings applies here: this is not the ring.
            plan = FiringPlan(fullScreen = false, notification = true, sound = false, vibrate = false),
            late = null,
            fullScreen = false,
            // Which way it got away is the whole of what the word has to say, and the moment it
            // is about is what the clock on it counts up from.
            nudge = due.word,
            nudgeAbout = due.about,
            defaultTime = settings.defaultTime,
            snoozes = settings.notificationSnoozeOffers,
            customMinutes = settings.snoozeCustomMinutes,
        )
        // **And it says it out loud, quietly.** The card goes on the mutest channel there is
        // and the app makes the noise itself, because a channel's tone plays at whatever the
        // slider says and there is no such thing as half a notification. The ordinary,
        // non-insistent tone — this is not the insistent kind of thing — at half an alarm, and
        // only while somebody is up to hear it. See [netSpeaksAloud].
        if (netSpeaksAloud(now, clock.zone, settings.dayShape)) {
            Sounds.uri(context, settings.soundFor(insistent = false))?.let { tone ->
                AlertAudio.playOnce(context, tone, NET_GAIN, settings.alertToHeadphones)
            }
        }
        scheduler.rearmAll()
    }

    /**
     * "Posponer": it rings again then, and not at its own moment until it has.
     *
     * **The row is written before the notification comes down.** The other way round, anything
     * that went wrong in between — a settings read that would not answer was the one that could
     * — left the alert gone from the shade and the snooze never written: the reminder went back
     * to counting down to its own next moment, a fortnight off, as if nobody had answered it at
     * all. An alert still in the shade is an answer somebody can give again; a silent reminder
     * that ignored the answer is not.
     */
    suspend fun snooze(id: String, snooze: Snooze) = lock.withLock {
        // A notification outlives the row it was posted for (see [dismiss]), and "Posponer" on
        // one of those has nothing to write — but it still has a card to take down.
        if (repository.get(id) == null) {
            repeater.cancel(id)
            AlertNotifications.cancel(context, id)
            return@withLock
        }
        val now = clock.instant()
        val settings = settings()
        val until = snooze.until(now, clock.zone, settings.weekendDay, settings.weekendTime, settings.dayStart, settings.snoozeCustomMinutes)
        repository.snooze(id, until)
        Diag.note(TAG_DIAG, "r=${short(id)} snoozed ($snooze) until $until")
        repository.record(id, FiringKind.SNOOZED, now, detail = until.toString())
        repeater.cancel(id)
        AlertNotifications.cancel(context, id)
        scheduler.rearmAll()
    }

    /**
     * "Cuando llegue a…" / "al salir de aquí": put off until the phone crosses [place]'s line.
     *
     * Nothing on the clock is armed — the circle is the alarm — and it reaches the fences and
     * the watch through the scheduling key, which carries the place. [fix] is where the phone is
     * now, when anything knows: the watch is told which side of the line it starts on, so the
     * first crossing it sees is a crossing and not a first look. "Al salir de aquí" always
     * knows, since the circle was drawn around that very position; "al llegar a casa" knows
     * when the watch has looked lately, and is baselined by its next look otherwise. [remember]
     * is the watch's own door for that memory (`PlaceWatcher.remember`), handed in rather than
     * held, because the watch is built after this and calls back into it.
     */
    suspend fun snoozeToPlace(id: String, place: Trigger.Location, fix: Fix?, remember: suspend (String, Transition) -> Unit) {
        val written = lock.withLock {
            if (repository.get(id) == null) {
                repeater.cancel(id)
                AlertNotifications.cancel(context, id)
                return@withLock false
            }
            val now = clock.instant()
            repository.snooze(id, null, place)
            Diag.note(
            TAG_DIAG,
            "r=${short(id)} snoozed to a place #${GeofenceIds.tag(place.lat, place.lng, place.radiusM)} ${place.radiusM}m " +
                "(${place.snoozeDetail().substringBefore(':')}, side ${if (fix == null) "unknown" else "known"})",
        )
            repository.record(id, FiringKind.SNOOZED, now, detail = place.snoozeDetail())
            repeater.cancel(id)
            AlertNotifications.cancel(context, id)
            true
        }
        if (!written) return
        // After the row and outside this lock: the watch takes its own to write it, and the
        // watch's look takes that lock before it reaches `fire`, which takes this one.
        if (fix != null) {
            val inside = distanceMeters(fix.lat, fix.lng, place.lat, place.lng) <= place.radiusM
            remember(GeofenceIds.encodeSnooze(id, place), if (inside) Transition.ENTER else Transition.EXIT)
        }
        scheduler.rearmAll()
    }

    /**
     * "Quitar el posponer": back to its own moment, as if the answer had never been given.
     * Nothing to take down — a snooze already took the notification with it — so this is the
     * row and the alarm, under the same lock as everything else that writes them.
     */
    suspend fun unsnooze(id: String) = lock.withLock {
        if (repository.get(id) == null) return@withLock
        repository.snooze(id, null)
        Diag.note(TAG_DIAG, "r=${short(id)} snooze cancelled")
        scheduler.rearmAll()
    }

    /**
     * The settings, or the defaults if they will not read.
     *
     * Every answer this class gives needs them for something, and none of them is worth losing
     * over it: a swallowed exception here used to take the whole answer with it — the "hecho"
     * that never marked anything done, the "posponer" that only took the notification down.
     * The defaults are a fine way to ring, to postpone and to finish.
     *
     * Bounded as well as caught, because most of this runs inside a broadcast that the system
     * gives about ten seconds to: a read off the disk that will not come back would run that
     * clock out and take the answer with it, and a store that slow has nothing to say that is
     * worth a lost "posponer".
     */
    private suspend fun settings(): AppSettings = runCatching {
        withTimeoutOrNull(SETTINGS_TIMEOUT_MS) { settingsStore.settings.first() }
    }.getOrElse {
        Log.e(TAG, "settings would not read", it)
        null
    } ?: AppSettings().also { Log.w(TAG, "going with the default settings") }

    /**
     * Re-arms everything and speaks up about what was missed while the phone was off. Used at
     * launch, after a reboot, and by the safety-net worker — not on every edit, where a missed
     * firing is not news.
     */
    suspend fun rearmAndCatchUp() {
        val missed = scheduler.rearmAll()
        val settings = settings()
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

        /** Alarms arrive late, never early — but a few seconds of slack costs nothing. */
        const val EARLY_GRACE_SECONDS = 5L

        /**
         * Long enough for a cold process to read its own settings off the disk, short enough to
         * be well inside the window a broadcast has to answer in.
         */
        const val SETTINGS_TIMEOUT_MS = 5_000L
    }
}
