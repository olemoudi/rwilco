package dev.rwilco.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * What happens around a reminder actually ringing. Pure, so the scheduler, the alarm receiver
 * and the notification buttons all decide the same way and a JVM test can hold them to it.
 */

/**
 * What a reminder becomes once the person has dealt with a firing.
 *
 * Done means done only when there is nothing left to ring: a one-shot moment that has passed.
 * Anything that can come round again — a repeating time, a place, a random window — stays
 * active, because "I have watered the plants" is not "stop reminding me to water the plants".
 */
fun statusAfterDismissal(reminder: Reminder, now: Instant, zone: ZoneId, defaultTime: LocalTime): Status {
    // Dealt with means the round is over: what had already happened under ALL stops counting,
    // and the question is whether the reminder can come round again from scratch.
    val cleared = reminder.copy(status = Status.ACTIVE, snoozedUntil = null, firedRules = emptySet())
    return if (nextFire(cleared, now, zone, defaultTime) == null) Status.DONE else Status.ACTIVE
}

/**
 * The moment an alarm was set for and never rang — the phone was off, or the app was killed
 * before the receiver ran — or null when nothing was missed.
 *
 * Deliberately "armed and not fired" rather than "in the past": a reminder that rang and was
 * ignored is already visible as overdue, and telling somebody about it twice is noise.
 */
fun missedFire(reminder: Reminder, now: Instant): Instant? {
    if (reminder.status != Status.ACTIVE) return null
    val armed = reminder.armedFor ?: return null
    if (armed > now) return null
    val fired = reminder.lastFiredAt
    return if (fired == null || fired < armed) armed else null
}

/** How the person is told about a firing, given what they asked for. */
data class FiringPlan(
    val fullScreen: Boolean,
    val notification: Boolean,
    val sound: Boolean,
    val vibrate: Boolean,
) {
    /**
     * A full-screen alert rings for itself (a looping tone while the screen is up), so the
     * notification that carries it must stay silent or the two overlap.
     */
    val notificationSound: Boolean get() = sound && !fullScreen
    val notificationVibrate: Boolean get() = vibrate && !fullScreen
}

fun firingPlan(actions: Set<Action>): FiringPlan = FiringPlan(
    fullScreen = Action.FULL_SCREEN in actions,
    // A full-screen alert always leaves a notification behind: it is what the person finds if
    // the system refused the takeover, or if they left the screen without deciding.
    notification = Action.NOTIFICATION in actions || Action.FULL_SCREEN in actions,
    sound = Action.SOUND in actions,
    vibrate = Action.VIBRATE in actions,
)

/**
 * What a rule's moment does to the reminder as a whole.
 *
 * Under ANY every moment rings. Under ALL a moment is first of all a fact to write down, and
 * only the one that completes the set rings — which is why this is a decision and not an
 * `if` in the receiver: the alarm, the geofence and the catch-up after a reboot all arrive
 * here by different doors and must answer the same way.
 */
sealed interface FiringOutcome {
    /** Tell the person. */
    data object Ring : FiringOutcome

    /** Not yet: this one happened, [fired] is what has happened so far, and the rest is waited on. */
    data class Wait(val fired: Set<Int>) : FiringOutcome
}

fun outcomeOfFiring(reminder: Reminder, ruleIndex: Int?): FiringOutcome {
    // A snooze's moment (no rule behind it) is the ring itself, as is anything under ANY.
    if (ruleIndex == null || reminder.ruleMatch == RuleMatch.ANY || !reminder.rulesCombine) return FiringOutcome.Ring
    if (ruleIndex !in reminder.rules.indices) return FiringOutcome.Ring
    val fired = reminder.firedRules.filter { it in reminder.rules.indices }.toSet() + ruleIndex
    return if (fired.size == reminder.rules.size) FiringOutcome.Ring else FiringOutcome.Wait(fired)
}

/**
 * The snooze offers on the alert screen and in the notification.
 *
 * They are the answers a person actually gives an alarm: not yet, later today, tomorrow, at the
 * weekend, next week. Everything but the first two keeps the wall-clock time rather than adding
 * hours, because "mañana a la misma hora" is what somebody means.
 */
enum class Snooze {
    TEN_MINUTES,
    TWO_HOURS,
    TOMORROW,
    WEEKEND,
    NEXT_WEEK,
    ;

    /**
     * When it comes back. [weekendDay]/[weekendTime] are a setting (Friday at 20:30 by default)
     * because "el finde" starts at different hours for different people.
     */
    fun until(now: Instant, zone: ZoneId, weekendDay: DayOfWeek, weekendTime: LocalTime): Instant {
        val here = now.atZone(zone)
        return when (this) {
            TEN_MINUTES -> now.plusSeconds(10 * 60)
            TWO_HOURS -> now.plusSeconds(2 * 60 * 60)
            // Same wall-clock time, so a clock change in between does not move it an hour.
            TOMORROW -> here.plusDays(1).toInstant()
            NEXT_WEEK -> here.plusWeeks(1).toInstant()
            WEEKEND -> {
                val candidate = here.toLocalDate().with(TemporalAdjusters.nextOrSame(weekendDay))
                    .atTime(weekendTime).atZone(zone)
                // Already past this week's: the weekend being talked about is the next one.
                if (candidate.toInstant() > now) candidate.toInstant()
                else here.toLocalDate().with(TemporalAdjusters.next(weekendDay)).atTime(weekendTime).atZone(zone).toInstant()
            }
        }
    }
}
