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

    /** rearmAll: what the row says the alarm is set for. */
    fun arm(): Wake? {
        val wake = nextWake(reminder, now, zone, defaultTime, dayStart, shape)
        reminder = reminder.copy(armedFor = wake?.at, armedRule = wake?.ruleIndex)
        return wake
    }

    /** The next alarm arrives: the clock jumps to it and the row transitions. Null when nothing is armed. */
    fun step(deal: (Ring) -> Deal = { Deal.Ignore }): Ring? {
        val wake = arm() ?: return null
        now = wake.at
        return fire(wake.ruleIndex, late = null, deal)
    }

    /** Alarm after alarm until the next one is past [until]; the rings that went out meanwhile. */
    fun run(until: Instant, maxSteps: Int = 5_000, deal: (Ring) -> Deal = { Deal.Ignore }): List<Ring> {
        val before = rings.size
        repeat(maxSteps) {
            val wake = arm() ?: return rings.drop(before)
            if (wake.at > until) return rings.drop(before)
            now = wake.at
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
        return rings.drop(before)
    }

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
                    firedRules = emptySet(),
                    lastDealtAt = now,
                    dealtThrough = consumed ?: reminder.dealtThrough,
                    status = status,
                    doneAt = now.takeIf { status == Status.DONE },
                    updatedAt = now,
                )
            }
            is Deal.Later -> reminder = reminder.copy(snoozedUntil = deal.snooze.until(now, zone, weekendDay, weekendTime))
        }
        arm()
    }

    private fun fire(ruleIndex: Int?, late: Instant?, deal: (Ring) -> Deal): Ring? {
        val row = reminder
        if (row.status != Status.ACTIVE) return null
        val fired = row.lastFiredAt
        if (late != null && fired != null && !fired.isBefore(late)) return null
        val judged = ruleIndex?.let { row.ruleInSet(it, shape) }
        if (ruleIndex != null && judged == null) {
            arm()
            return null
        }
        val armed = row.armedFor
        val eventDriven = ruleIndex?.let { row.rules.getOrNull(it) }?.trigger is Trigger.Location
        if (!eventDriven && late == null && (armed == null || armed > now.plusSeconds(5))) {
            arm()
            return null
        }
        val snoozed = row.snoozedUntil
        if (snoozed != null && snoozed > now) {
            arm()
            return null
        }
        when (val outcome = outcomeOfFiring(row, ruleIndex)) {
            is FiringOutcome.Wait -> {
                reminder = row.copy(firedRules = outcome.fired)
                noted += Wake(now, ruleIndex)
                arm()
                return null
            }
            FiringOutcome.Ring -> Unit
        }
        val rangFor = momentRungFor(now, row.armedFor, late, eventDriven)
        reminder = row.copy(lastFiredAt = rangFor, lastFiredRule = ruleIndex, snoozedUntil = null)
        if (row.ruleMatch == RuleMatch.ALL && row.rulesCombine) reminder = reminder.copy(firedRules = row.rules.indices.toSet())
        val ring = Ring(now, rangFor, ruleIndex, late)
        rings += ring
        deal(deal(ring))
        return ring
    }
}
