package dev.rwilco.model

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** What a reminder will do next, as far as the model can know without the scheduler. */
sealed interface NextFire {
    val trigger: Trigger

    /**
     * A definite moment. [snoozed] means the moment comes from a "remind me later", not from
     * [trigger] — the trigger is carried anyway so the row keeps the icon it is recognised by.
     */
    data class Scheduled(val at: Instant, override val trigger: Trigger, val snoozed: Boolean = false) : NextFire

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
 * The next thing this reminder does: for ANY the earliest definite moment if there is one, else
 * the earliest random draw, else the place it is waiting for. Null for a paused or done
 * reminder, and for an active one whose every moment has passed (Home lists those as overdue).
 *
 * For ALL it is the *last* of the ones still pending, because that is the one that rings — and
 * if a place is among them there is no date to give at all, so it answers with the place.
 */
fun nextFire(reminder: Reminder, now: Instant, zone: ZoneId, defaultTime: LocalTime): NextFire? {
    if (reminder.status != Status.ACTIVE) return null
    // A snooze outranks every rule: it is the person saying "not now, then".
    val snoozedUntil = reminder.snoozedUntil
    if (snoozedUntil != null && snoozedUntil > now) {
        val trigger = reminder.rules.firstOrNull()?.trigger ?: return null
        return NextFire.Scheduled(snoozedUntil, trigger, snoozed = true)
    }
    val pending = reminder.pendingRules()
    val candidates = pending.mapNotNull { index ->
        nextFireOfRule(reminder.rules[index], reminder.id, now, zone, defaultTime)
    }
    if (reminder.ruleMatch == RuleMatch.ANY || !reminder.rulesCombine) {
        return candidates.filterIsInstance<NextFire.Scheduled>().minByOrNull { it.at }
            ?: candidates.filterIsInstance<NextFire.Sometime>().minByOrNull { it.at }
            ?: candidates.filterIsInstance<NextFire.WhenAt>().firstOrNull()
    }
    // ALL: one rule that can never happen again is one the set can never complete.
    if (candidates.size < pending.size) return null
    candidates.filterIsInstance<NextFire.WhenAt>().firstOrNull()?.let { return it }
    return candidates.maxByOrNull { it.momentOrNull() ?: Instant.MIN }
}

/** The moment a candidate carries, where it has one; a place has none by nature. */
private fun NextFire.momentOrNull(): Instant? = when (this) {
    is NextFire.Scheduled -> at
    is NextFire.Sometime -> at
    is NextFire.WhenAt -> null
}

/**
 * What the scheduler should set an alarm for, and which rule that moment belongs to.
 *
 * The earliest pending moment either way — under ALL too, where the alarm is not a ring but a
 * note to take: the phone has to be awake at each of them to know it happened, and the ring
 * falls out of the last one. A null [Wake.ruleIndex] means the moment is the ring itself,
 * which is what a snooze is.
 */
data class Wake(val at: Instant, val ruleIndex: Int?)

fun nextWake(reminder: Reminder, now: Instant, zone: ZoneId, defaultTime: LocalTime): Wake? {
    if (reminder.status != Status.ACTIVE) return null
    val snoozedUntil = reminder.snoozedUntil
    if (snoozedUntil != null && snoozedUntil > now) return Wake(snoozedUntil, null)
    return reminder.pendingRules()
        .mapNotNull { index ->
            val at = nextFireOfRule(reminder.rules[index], reminder.id, now, zone, defaultTime)?.momentOrNull()
            at?.let { Wake(it, index) }
        }
        .minByOrNull { it.at }
}

/**
 * A rule's next fire: the first moment its trigger produces that all of its conditions hold at.
 *
 * Walks candidate moments rather than solving for them, because "every day at nine, and only in
 * June" is a search either way — and it stops after [MAX_CANDIDATES] so a rule that can never
 * be satisfied ("at 09:00, and only between 18:00 and 22:00") answers "never" instead of
 * looping. A place is judged when it happens, not now, so it comes back untouched.
 */
fun nextFireOfRule(rule: TriggerRule, reminderId: String, now: Instant, zone: ZoneId, defaultTime: LocalTime): NextFire? {
    var after = now
    repeat(MAX_CANDIDATES) {
        val candidate = nextFireOf(rule.trigger, reminderId, after, zone, defaultTime) ?: return null
        val at = when (candidate) {
            is NextFire.Scheduled -> candidate.at
            is NextFire.Sometime -> candidate.at
            is NextFire.WhenAt -> return candidate
        }
        if (rule.conditions.allHoldAt(at, zone)) return candidate
        after = at
    }
    return null
}

/** Enough to walk two months of daily moments, or a fortnight of a five-a-day random window. */
private const val MAX_CANDIDATES = 64

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
