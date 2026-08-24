package dev.rwilco.model

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

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
    val cleared = reminder.copy(status = Status.ACTIVE, snoozedUntil = null)
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

/** The snooze offers on the alert screen and in the notification. */
enum class Snooze(val minutes: Long) {
    TEN_MINUTES(10),
    ONE_HOUR(60),
    TOMORROW(-1),
    ;

    /** Tomorrow means the default time tomorrow, not twenty-four hours from now. */
    fun until(now: Instant, zone: ZoneId, defaultTime: LocalTime): Instant = when (this) {
        TOMORROW -> now.atZone(zone).toLocalDate().plusDays(1).atTime(defaultTime).atZone(zone).toInstant()
        else -> now.plusSeconds(minutes * 60)
    }
}
