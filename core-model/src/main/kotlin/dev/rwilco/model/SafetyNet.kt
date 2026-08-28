package dev.rwilco.model

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The safety net: one quiet word about a reminder that rang and that nobody ever answered.
 *
 * Everything else in this app is about a moment arriving. This is about a moment that arrived
 * and was let go — the notification swiped off the lock screen at a traffic light, the alert
 * read while both hands were full. The reminder is then sitting in "vencidos" saying nothing,
 * for ever, and the app that took the trouble to wake the phone up says nothing either.
 *
 * **It is asked for, per reminder** ([Reminder.safetyNet]), and it fires **once** per firing
 * nobody answered ([Reminder.nudgedAt]) — a net that keeps nagging is an alarm again, which is
 * exactly the thing somebody chose to let go of.
 *
 * **What it waits for depends on whether the reminder is coming back:**
 *
 * - Nothing left to ring (a date that has been, a countdown that ran out): the whole wait,
 *   [SafetyNetSettings.afterHours] — a day by default. There is no rush; nothing else is going
 *   to happen, and a day is the length of "I meant to do that this morning".
 * - Coming back on its own: a **tenth** of the gap between one ring and the next
 *   ([SafetyNetSettings.fraction]), or the whole wait, whichever is **shorter**. The point is to
 *   catch it before the next one arrives and buries it, and the tenth is what keeps that
 *   proportional — 2 h 24 min on something daily, a quarter of an hour on something six-hourly.
 * - Faster than [SafetyNetSettings.minCadenceMinutes] between rings, an hour by default: the net
 *   cannot be armed at all. A reminder that comes back every twenty minutes needs no net; the
 *   next one *is* the net, and a second notification between two of them is noise.
 *
 * A reminder that has never rung has nothing to be caught: the net is about a firing, not about
 * a reminder that was written already too late (which the editor says out loud at the time). A
 * place is treated as having nothing next, because nothing can say when somebody will be back.
 */
@Serializable
data class SafetyNetSettings(
    /** The longest it ever waits, and the whole wait when nothing is coming back. */
    val afterHours: Int = DEFAULT_AFTER_HOURS,
    /** Rings closer together than this cannot carry a net at all. */
    val minCadenceMinutes: Int = DEFAULT_MIN_CADENCE_MINUTES,
    /** One [fraction]th of the gap between two rings: the other half of "whichever is shorter". */
    val fraction: Int = DEFAULT_FRACTION,
) {
    companion object {
        const val DEFAULT_AFTER_HOURS = 24
        const val DEFAULT_MIN_CADENCE_MINUTES = 60
        const val DEFAULT_FRACTION = 10
    }
}

/** What the steppers in Settings may be dragged between. */
object SafetyNetLimits {
    /**
     * An hour is the shortest wait worth calling a net, and three days is the longest: past
     * that it is not catching anything, it is bringing something up.
     */
    val AFTER_HOURS = 1..72
    /** Below a quarter of an hour the next ring is always sooner than any net. */
    val MIN_CADENCE_MINUTES = 15..720
    /** A half is barely a share; a fiftieth of a day is half an hour. */
    val FRACTION = 2..50
}

/**
 * The gap between one ring and the next when nothing gets in the way, or null when there is no
 * next ring at all.
 *
 * Asked of the *shape* and not of the row: a reminder that rang six hours ago and was ignored
 * has no next moment of its own (an anchored recurrence counts from the "hecho", so it waits),
 * and its rhythm is six hours all the same. So a span answers with two of its own steps, a
 * calendar with two of its own dates, and everything else by asking a copy of this reminder
 * with nothing spent what it would do from here.
 */
fun Reminder.ringCadence(
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    dayStart: LocalTime = DEFAULT_DAY_START,
    shape: DayShape = DayShape.DEFAULT,
): Duration? {
    val recurrence = recurrence
    if (recurrence is Recurrence.After || recurrence is Recurrence.MonthlyWeekday) {
        val first = nextRecurrence(recurrence, now, zone, dayStart) ?: return null
        val second = nextRecurrence(recurrence, first, zone, dayStart) ?: return null
        return Duration.between(first, second)
    }
    if (recurrence is Recurrence.Calendar) {
        val first = recurrence.nextMoment(id, now, zone, shape) ?: return null
        val second = recurrence.nextMoment(id, first, zone, shape) ?: return null
        return Duration.between(first, second)
    }
    // The rules' own rhythm: what this reminder would do from here if nobody had touched it.
    val fresh = copy(
        status = Status.ACTIVE,
        snoozedUntil = null,
        firedRules = emptySet(),
        lastFiredAt = null,
        lastDealtAt = null,
    )
    val first = nextWake(fresh, now, zone, defaultTime, dayStart, shape)?.at ?: return null
    val second = nextWake(fresh, first, zone, defaultTime, dayStart, shape)?.at ?: return null
    return Duration.between(first, second)
}

/**
 * Whether this shape rings too often to be worth a net. [cadence] is [ringCadence]; null (a
 * reminder with no next ring) is never too fast, because nothing is coming to bury it.
 */
fun tooFastForNet(cadence: Duration?, settings: SafetyNetSettings): Boolean =
    cadence != null && cadence < Duration.ofMinutes(settings.minCadenceMinutes.toLong())

/** How long the net waits, given the rhythm it is stretched under. See [SafetyNetSettings]. */
fun netWait(cadence: Duration?, settings: SafetyNetSettings): Duration {
    val longest = Duration.ofHours(settings.afterHours.toLong())
    if (cadence == null) return longest
    val share = cadence.dividedBy(settings.fraction.coerceAtLeast(1).toLong())
    return minOf(longest, share)
}

/**
 * When the quiet word is due, or null when it is not owed at all: the net is off, the reminder
 * has been dealt with (or paused, or put off, which are answers), it has never rung, this
 * firing has already been nudged about, or it comes back too fast to be worth catching.
 *
 * Only the moment. Whether it still holds when that moment arrives is asked again then, because
 * everything about it can change in the day it waits.
 */
fun Reminder.nudgeAt(
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    settings: SafetyNetSettings,
    dayStart: LocalTime = DEFAULT_DAY_START,
    shape: DayShape = DayShape.DEFAULT,
): Instant? {
    if (!safetyNet) return null
    if (!awaitingAnswer(now)) return null
    val rang = lastFiredAt ?: return null
    // One word per firing. A second one about the same ring is the nagging this is not.
    nudgedAt?.let { if (!it.isBefore(rang)) return null }
    val cadence = ringCadence(now, zone, defaultTime, dayStart, shape)
    if (tooFastForNet(cadence, settings)) return null
    return rang.plus(netWait(cadence, settings))
}
