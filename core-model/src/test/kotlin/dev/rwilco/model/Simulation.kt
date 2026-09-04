package dev.rwilco.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * A phone, in memory: the row a reminder is, the alarm set for it, and the person answering.
 *
 * Mirrors what the app does around a firing, step for step — `ReminderScheduler.rearmAll`
 * (arm), `ReminderFiring.fire` (the guards, the ring, the note under ALL), `dismiss` and
 * `snooze` (what "hecho" and "más tarde" write), and `rearmAndCatchUp` (a phone that was off:
 * `missedFire`, the late ring, `owedUnderAll`). What only a phone can answer — where it is —
 * is left out, so a place condition holds, which is the house rule anyway.
 *
 * The point is to wind a shape forward through months of rings and say, of every one, when it
 * rang and why; a scenario that fails here is a finding about the model, not about the test.
 */
class Simulation(
    initial: Reminder,
    var now: Instant,
    val zone: ZoneId = Fixtures.zone,
    val defaultTime: LocalTime = Fixtures.defaultTime,
    val dayStart: LocalTime = DEFAULT_DAY_START,
    val shape: DayShape = DayShape.DEFAULT,
    val weekendDay: DayOfWeek = DayOfWeek.FRIDAY,
    val weekendTime: LocalTime = LocalTime.of(20, 30),
) {
    /** How the person answered a ring. */
    sealed interface Deal {
        data object Ignore : Deal
        data object Done : Deal
        data class Later(val snooze: Snooze) : Deal

        /** "Cuando llegue a…" / "al salir de aquí": put off until the phone crosses [place]'s line. */
        data class Elsewhere(val place: Trigger.Location) : Deal
    }

    /** One ring as the row recorded it: when the alarm arrived, the moment it was recorded against, and why. */
    data class Ring(val at: Instant, val rangFor: Instant, val ruleIndex: Int?, val late: Instant?) {
        fun local(zone: ZoneId): LocalDateTime = at.atZone(zone).toLocalDateTime()
    }

    var reminder: Reminder = initial
        private set
    val rings = mutableListOf<Ring>()

    /** Under ALL: moments written down without ringing (FiringOutcome.Wait). */
    val noted = mutableListOf<Wake>()

    /** The deadlines that passed with the set incomplete, and let the round go (Reminder.lapsed). */
    val lapses = mutableListOf<Instant>()

    /**
     * rearmAll: what the row says the alarm is set for. A moment armed, come and not answered is
     * held rather than moved on — the delivery in flight rings it — exactly as the scheduler does.
     */
    fun arm(): Wake? {
        val missed = missedFire(reminder, now)
        if (missed != null) return Wake(missed, reminder.armedRule)
        val wake = nextWake(reminder, now, zone, defaultTime, dayStart, shape)
        reminder = reminder.copy(armedFor = wake?.at, armedRule = wake?.ruleIndex)
        return wake
    }

    /**
     * The deadline's own alarm, when the set has one running: the second alarm the scheduler
     * keeps per reminder (`ReminderScheduler.armLapse`). Only for a reminder still active — a
     * paused one keeps the moment and is asked again when it comes back.
     */
    fun lapseAt(): Instant? = reminder.expiresAt?.takeIf { reminder.status == Status.ACTIVE && reminder.hasDeadline }

    /**
     * The deadline's alarm arrives: what `ReminderFiring.expire` decides, in the same order. A
     * firing still owed — a moment the phone slept through — goes first, so the catch-up can
     * note or ring it for the moment it was about; and anything the person did, a ring waiting
     * for an answer or a snooze, means the deadline no longer applies and is simply dropped.
     * [force] is the check `fire` makes on its own way in, where the moment at hand is the
     * owed one and there is nothing to wait for.
     */
    fun expire(force: Boolean = false): Boolean {
        val row = reminder
        val at = row.expiresAt ?: return false
        if (row.status != Status.ACTIVE || !row.expiryDue(now)) return false
        if (!force && missedFire(row, now) != null) return false
        if (row.deadlineOutranked(now)) {
            reminder = row.copy(expiresAt = null)
            arm()
            return false
        }
        reminder = row.lapsed(at, zone, defaultTime, dayStart, shape)
        lapses += at
        arm()
        return true
    }

    /** Whether the next thing to arrive is the deadline rather than a ring. */
    private fun lapseFirst(wake: Wake?, lapse: Instant?): Boolean =
        lapse != null && (wake == null || lapse < wake.at) && missedFire(reminder, now) == null

    /** The next alarm arrives: the clock jumps to it and the row transitions. Null when nothing is armed. */
    fun step(deal: (Ring) -> Deal = { Deal.Ignore }): Ring? {
        val wake = arm()
        val lapse = lapseAt()
        if (lapseFirst(wake, lapse)) {
            now = maxOf(now, lapse!!)
            expire()
            return null
        }
        if (wake == null) return null
        now = maxOf(now, wake.at)
        return fire(wake.ruleIndex, late = null, deal)
    }

    /** Alarm after alarm until the next one is past [until]; the rings that went out meanwhile. */
    fun run(until: Instant, maxSteps: Int = 5_000, deal: (Ring) -> Deal = { Deal.Ignore }): List<Ring> {
        val before = rings.size
        repeat(maxSteps) {
            val wake = arm()
            val lapse = lapseAt()
            if (lapseFirst(wake, lapse)) {
                if (lapse!! > until) return rings.drop(before)
                now = maxOf(now, lapse)
                expire()
                return@repeat
            }
            if (wake == null) return rings.drop(before)
            if (wake.at > until) return rings.drop(before)
            now = maxOf(now, wake.at)
            fire(wake.ruleIndex, late = null, deal)
        }
        error("still ringing after $maxSteps alarms")
    }

    /**
     * The phone is off until [until]: the armed alarm never arrives. Then the launch pass —
     * the re-arm that moves the row on, the missed moment rung late, and under ALL the
     * one-shot moments that passed after it, in turn — exactly as `rearmAndCatchUp` does.
     */
    fun sleepUntil(until: Instant, deal: (Ring) -> Deal = { Deal.Ignore }): List<Ring> {
        val before = rings.size
        arm()
        now = until
        val missed = missedFire(reminder, now)
        val armedRule = reminder.armedRule
        arm()
        if (missed != null) {
            fire(armedRule, late = missed, deal)
            var left = reminder.rules.size
            while (left-- > 0) {
                val owed = owedUnderAll(reminder, missed, now, zone, defaultTime, shape).firstOrNull() ?: break
                fire(owed.ruleIndex, late = owed.at, deal)
            }
        }
        // The deadline's alarm, delivered at once for a moment already past — after the catch-up
        // has had its say about the moments before it.
        expire()
        return rings.drop(before)
    }

    /**
     * The phone crosses the line a snooze is waiting at — the geofence or the watch reporting
     * [transition] for that circle — exactly as `ReminderFiring.fire(viaSnoozePlace = true)` is
     * reached. Nothing happens for the other side of the line, or with no such snooze: the
     * circle only ever reports the crossing it waits for.
     */
    fun cross(transition: Transition, deal: (Ring) -> Deal = { Deal.Ignore }): Ring? {
        val place = reminder.snoozedToPlace ?: return null
        if (place.presence.asTransition != transition) return null
        return fire(ruleIndex = null, late = null, deal = deal, viaSnoozePlace = true)
    }

    /**
     * The phone crosses the line of rule [ruleIndex]'s own circle — the geofence or the watch
     * reporting it — which is `ReminderFiring.fire(ruleIndex)` with no armed moment behind it,
     * because a place has none.
     */
    fun arrive(ruleIndex: Int, deal: (Ring) -> Deal = { Deal.Ignore }): Ring? = fire(ruleIndex, late = null, deal = deal)

    /** The person answers at [now], writing what `ReminderFiring.dismiss`/`snooze` write. */
    fun deal(deal: Deal) {
        when (deal) {
            Deal.Ignore -> Unit
            Deal.Done -> {
                // The same two questions ReminderFiring.dismiss asks, in the same order: what
                // this "hecho" spends, and what the reminder is once it has.
                val consumed = reminder.momentDealtWith(now, zone, defaultTime, dayStart, shape)
                val dealt = reminder.copy(lastDealtAt = now, dealtThrough = consumed ?: reminder.dealtThrough)
                val status = statusAfterDismissal(dealt, now, zone, defaultTime, shape)
                reminder = reminder.copy(
                    snoozedUntil = null,
                    snoozedToPlace = null,
                    firedRules = emptySet(),
                    lastDealtAt = now,
                    dealtThrough = consumed ?: reminder.dealtThrough,
                    status = status,
                    doneAt = now.takeIf { status == Status.DONE },
                    updatedAt = now,
                )
                // And the next round's window close, counted from the rest, as dismiss writes it.
                reminder = reminder.copy(expiresAt = reminder.roundExpiry(now, zone, defaultTime, dayStart, shape))
            }
            is Deal.Later -> reminder = reminder.copy(snoozedUntil = deal.snooze.until(now, zone, weekendDay, weekendTime), snoozedToPlace = null)
            is Deal.Elsewhere -> reminder = reminder.copy(snoozedUntil = null, snoozedToPlace = deal.place)
        }
        arm()
    }

    private fun fire(ruleIndex: Int?, late: Instant?, deal: (Ring) -> Deal, viaSnoozePlace: Boolean = false): Ring? {
        val row = reminder
        if (row.status != Status.ACTIVE) return null
        if (viaSnoozePlace && row.snoozedToPlace == null) return null
        val fired = row.lastFiredAt
        if (late != null && fired != null && !fired.isBefore(late)) return null
        val judged = ruleIndex?.let { row.ruleInSet(it, shape, zone) }
        val armed = row.armedFor
        val eventDriven = ruleIndex?.let { row.rules.getOrNull(it) }?.trigger is Trigger.Location || viaSnoozePlace
        // ReminderFiring.spendArmed: a moment judged and dropped is written off before the
        // re-arm, or the hold in arm() would keep it for ever.
        fun spendArmed() {
            if (!eventDriven && armed != null && armed <= now.plusSeconds(5)) reminder = reminder.copy(armedFor = null, armedRule = null)
        }
        if (ruleIndex != null && judged == null) {
            spendArmed()
            arm()
            return null
        }
        // The span has taken the rules out of the loop: a circle still registered with the
        // system cannot ring what the rules no longer decide. See [Reminder.spanHasTakenOver].
        if (ruleIndex != null && row.spanHasTakenOver) {
            spendArmed()
            arm()
            return null
        }
        if (!eventDriven && late == null && (armed == null || armed > now.plusSeconds(5))) {
            arm()
            return null
        }
        val snoozed = row.snoozedUntil
        if (snoozed != null && snoozed > now) {
            arm()
            return null
        }
        // A clock alarm delivered while the reminder waits at a place is a stray: the place rings it.
        if (!viaSnoozePlace && row.snoozedToPlace != null) {
            arm()
            return null
        }
        // A place is judged against its hours when it happens (ReminderFiring.firstFailing with
        // askAll): an arrival outside the window a fold or a deadline put on it is nothing. Only
        // the fences a clock can answer — where the phone is, this harness never knows, and what
        // nobody can vouch for holds.
        if (judged != null && judged.trigger is Trigger.Location &&
            !judged.conditions.filter { it.knownInAdvance }.allHoldAt(late ?: now, zone)
        ) {
            spendArmed()
            arm()
            return null
        }
        // The deadline passed before the moment this is about: the round is over, and what just
        // happened belongs to no round. Asked of the moment and not of the clock, so a moment
        // the phone slept through before the deadline is still noted or rung for what it was.
        // Only a rule's own moment: a snooze's ring (no rule behind it) is the person's, and
        // outranks the deadline like everything else they did.
        if (ruleIndex != null && row.expiryDue(late ?: now)) {
            expire(force = true)
            spendArmed()
            arm()
            return null
        }
        val rangFor = momentRungFor(now, row.armedFor, late, eventDriven)
        when (val outcome = outcomeOfFiring(row, ruleIndex)) {
            is FiringOutcome.Wait -> {
                // The timer starts with the first moment of the round, and a state starts nothing.
                val started = ruleIndex?.let { row.timerExpiry(it, rangFor) }
                reminder = row.copy(firedRules = outcome.fired, expiresAt = started ?: row.expiresAt)
                noted += Wake(now, ruleIndex)
                spendArmed()
                arm()
                return null
            }
            FiringOutcome.Ring -> Unit
        }
        reminder = row.copy(lastFiredAt = rangFor, lastFiredRule = ruleIndex, snoozedUntil = null, snoozedToPlace = null, expiresAt = null)
        if (row.ruleMatch == RuleMatch.ALL && row.rulesCombine) reminder = reminder.copy(firedRules = row.rules.indices.toSet())
        val ring = Ring(now, rangFor, ruleIndex, late)
        rings += ring
        deal(deal(ring))
        return ring
    }
}
