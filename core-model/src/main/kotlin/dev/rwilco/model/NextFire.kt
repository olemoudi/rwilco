package dev.rwilco.model

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** What a reminder will do next, as far as the model can know without the scheduler. */
sealed interface NextFire {
    val trigger: Trigger

    /** A definite moment. */
    data class Scheduled(val at: Instant, override val trigger: Trigger) : NextFire

    /**
     * A random moment: [at] is the deterministic draw the scheduler will use; the UI shows the
     * window, because a random reminder that announces its time is not random.
     */
    data class Sometime(
        val at: Instant,
        val windowStart: Instant,
        val windowEnd: Instant,
        override val trigger: Trigger.Random,
    ) : NextFire

    /** When the phone gets somewhere; no moment to show. */
    data class WhenAt(override val trigger: Trigger.Location) : NextFire
}

/**
 * The next thing this reminder does: the earliest definite moment if there is one, else the
 * earliest random draw, else the place it is waiting for. Null for a paused or done reminder,
 * and for an active one whose every moment has passed (Home lists those as overdue).
 */
fun nextFire(reminder: Reminder, now: Instant, zone: ZoneId, defaultTime: LocalTime): NextFire? {
    if (reminder.status != Status.ACTIVE) return null
    val candidates = reminder.triggers.mapNotNull { nextFireOf(it, reminder.id, now, zone, defaultTime) }
    return candidates.filterIsInstance<NextFire.Scheduled>().minByOrNull { it.at }
        ?: candidates.filterIsInstance<NextFire.Sometime>().minByOrNull { it.at }
        ?: candidates.filterIsInstance<NextFire.WhenAt>().firstOrNull()
}

/** One trigger's next fire, or null when it has nothing left to do. */
fun nextFireOf(trigger: Trigger, reminderId: String, now: Instant, zone: ZoneId, defaultTime: LocalTime): NextFire? =
    when (trigger) {
        // atZone resolves a wall time that does not exist (a DST gap) forward, and one that
        // exists twice (a DST overlap) to its first occurrence — see NextFireTest.
        is Trigger.AtDateTime -> trigger.at.atZone(zone).toInstant().future(now)?.let { NextFire.Scheduled(it, trigger) }
        is Trigger.OnDate ->
            trigger.date.atTime(defaultTime).atZone(zone).toInstant().future(now)?.let { NextFire.Scheduled(it, trigger) }
        is Trigger.AtTime -> nextAtTime(trigger, now, zone)?.let { NextFire.Scheduled(it, trigger) }
        is Trigger.Location -> NextFire.WhenAt(trigger)
        is Trigger.Random -> nextRandom(trigger, reminderId, now, zone)
    }

private fun Instant.future(now: Instant): Instant? = takeIf { it > now }

private fun nextAtTime(trigger: Trigger.AtTime, now: Instant, zone: ZoneId): Instant? {
    if (trigger.days.isEmpty()) return null
    val today = now.atZone(zone).toLocalDate()
    for (offset in 0L..7L) {
        val date = today.plusDays(offset)
        if (date.dayOfWeek !in trigger.days) continue
        val at = date.atTime(trigger.time).atZone(zone).toInstant()
        if (at > now) return at
    }
    return null
}

/** Scans the current period and a few ahead: enough to cross any gap the day filter can make. */
private fun nextRandom(trigger: Trigger.Random, reminderId: String, now: Instant, zone: ZoneId): NextFire.Sometime? {
    val today = now.atZone(zone).toLocalDate()
    val first = RandomDraw.periodIndex(today, trigger.period)
    val horizon = if (trigger.period == Period.DAY) 8 else 2
    for (step in 0 until horizon) {
        val draws = RandomDraw.draws(trigger, reminderId, first + step, zone)
        val at = draws.firstOrNull { it > now } ?: continue
        val day = at.atZone(zone).toLocalDate()
        return NextFire.Sometime(
            at = at,
            windowStart = day.atTime(trigger.from).atZone(zone).toInstant(),
            windowEnd = day.atTime(trigger.to).atZone(zone).toInstant(),
            trigger = trigger,
        )
    }
    return null
}
