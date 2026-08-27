package dev.rwilco.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** What a reminder will do next, as far as the model can know without the scheduler. */
sealed interface NextFire {
    /** Null when the moment comes from a recurrence rather than from a rule of its own. */
    val trigger: Trigger?

    /**
     * A definite moment. [snoozed] means the moment comes from a "remind me later", not from
     * [trigger] — the trigger is carried anyway so the row keeps the icon it is recognised by.
     */
    data class Scheduled(val at: Instant, override val trigger: Trigger?, val snoozed: Boolean = false) : NextFire

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
fun nextFire(
    reminder: Reminder,
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    dayStart: LocalTime = DEFAULT_DAY_START,
    shape: DayShape = DayShape.DEFAULT,
): NextFire? {
    if (reminder.status != Status.ACTIVE) return null
    // A snooze outranks every rule: it is the person saying "not now, then".
    val snoozedUntil = reminder.snoozedUntil
    if (snoozedUntil != null && snoozedUntil > now) {
        return NextFire.Scheduled(snoozedUntil, reminder.rules.firstOrNull()?.trigger, snoozed = true)
    }
    // No rules at all: the recurrence is the whole arrangement, and its moment is the ring.
    if (reminder.rules.isEmpty()) {
        return reminder.recurrenceMoment(now, zone, dayStart, shape)?.let { NextFire.Scheduled(it, null) }
    }
    // Dealt with and asked to come back on a span: the rules rest until the span is up, and
    // then speak again from there — a place is watched again, a clock finds its next moment
    // after the rest. Only when none of them has anything left to say (a date that has been,
    // a countdown that ran out) does the recurrence's own moment ring, which is what "a las
    // ocho, y luego cada seis horas" means.
    val rest = reminder.restUntil(zone, dayStart, shape)
    val from = maxOf(reminder.searchFrom(now), rest ?: now)
    val pending = reminder.pendingRules()
    val candidates = pending.mapNotNull { index ->
        // Under "a la vez" the rule is judged with its siblings folded in as conditions — the
        // same rule the firing will judge — so a moment outside a sibling's window is not
        // offered, let alone armed. A fold of two moments can never ring and yields nothing.
        val rule = reminder.togetherRule(index) ?: return@mapNotNull null
        nextFireOfRule(rule, reminder.id, from, zone, defaultTime, shape)
    }
    if (candidates.isEmpty() && rest != null) {
        return reminder.recurrenceMoment(now, zone, dayStart, shape)?.let { NextFire.Scheduled(it, reminder.rules.firstOrNull()?.trigger) }
    }
    // ANY and TOGETHER alike: the earliest. Under "a la vez" each candidate is already the
    // folded rule's moment — the first instant the whole set holds — so the soonest of them is
    // the ring, and the "last of the pending" reading below belongs to ALL alone.
    if (reminder.ruleMatch != RuleMatch.ALL || !reminder.rulesCombine) {
        val place = candidates.filterIsInstance<NextFire.WhenAt>().firstOrNull()
        // Under "a la vez" a window beside a place is the place's hours, not a moment of its
        // own: its opening only rings if the phone is already there. Home once counted down to
        // it — a clock on a reminder that rings on arrival — and, once that opening had passed
        // with nobody there, to the next day's. The place is the honest answer; the opening is
        // still armed (nextWake), for the morning somebody is there already.
        val together = reminder.ruleMatch == RuleMatch.TOGETHER && reminder.rulesCombine
        val moments = candidates.filterIsInstance<NextFire.Scheduled>()
            .filterNot { together && place != null && it.trigger is Trigger.Interval }
        return moments.minByOrNull { it.at }
            ?: candidates.filterIsInstance<NextFire.Sometime>().minByOrNull { it.at }
            ?: place
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

fun nextWake(
    reminder: Reminder,
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    dayStart: LocalTime = DEFAULT_DAY_START,
    shape: DayShape = DayShape.DEFAULT,
): Wake? {
    if (reminder.status != Status.ACTIVE) return null
    val snoozedUntil = reminder.snoozedUntil
    if (snoozedUntil != null && snoozedUntil > now) return Wake(snoozedUntil, null)
    // A recurrence's moment is the ring itself: there is no rule behind it to tick off. It is
    // the alarm when there are no rules, and after a rest when the rules have nothing left to
    // say (see nextFire); a place among the rules is something left to say, and arms nothing.
    if (reminder.rules.isEmpty()) return reminder.recurrenceMoment(now, zone, dayStart, shape)?.let { Wake(it, null) }
    val rest = reminder.restUntil(zone, dayStart, shape)
    val from = maxOf(reminder.searchFrom(now), rest ?: now)
    val candidates = reminder.pendingRules().mapNotNull { index ->
        val rule = reminder.togetherRule(index) ?: return@mapNotNull null
        nextFireOfRule(rule, reminder.id, from, zone, defaultTime, shape)?.let { index to it }
    }
    if (candidates.isEmpty() && rest != null) return reminder.recurrenceMoment(now, zone, dayStart, shape)?.let { Wake(it, null) }
    return candidates
        .mapNotNull { (index, next) -> next.momentOrNull()?.let { Wake(it, index) } }
        .minByOrNull { it.at }
}

/**
 * A rule's next fire: the first moment its trigger produces that all of its conditions hold at.
 *
 * Walks candidate moments rather than solving for them, because "every day at nine, and only in
 * June" is a search either way — and it stops after [MAX_CANDIDATES] so a rule that can never
 * be satisfied ("at 09:00, and only between 18:00 and 22:00") answers "never" instead of
 * looping — which is also how [warnings] knows to say so. A place is judged when it happens,
 * not now, so it comes back untouched.
 *
 * Only the conditions that can be asked about a future moment are asked ([knownInAdvance]).
 * Nothing knows where somebody will be next Tuesday, so a place condition is left out here and
 * the alarm is armed regardless; `ReminderFiring` asks it for real when the alarm goes off.
 */
fun nextFireOfRule(
    rule: TriggerRule,
    reminderId: String,
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    shape: DayShape = DayShape.DEFAULT,
): NextFire? {
    var after = now
    repeat(MAX_CANDIDATES) {
        val candidate = nextFireOf(rule.trigger, reminderId, after, zone, defaultTime, shape) ?: return null
        val at = when (candidate) {
            is NextFire.Scheduled -> candidate.at
            is NextFire.Sometime -> candidate.at
            is NextFire.WhenAt -> return candidate
        }
        if (rule.conditions.filter { it.knownInAdvance }.allHoldAt(at, zone)) return candidate
        after = at
    }
    return null
}

/** Enough to walk two months of daily moments, or a fortnight of a five-a-day random window. */
private const val MAX_CANDIDATES = 64

/** One trigger's next fire, or null when it has nothing left to do. */
fun nextFireOf(
    trigger: Trigger,
    reminderId: String,
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    shape: DayShape = DayShape.DEFAULT,
): NextFire? =
    when (trigger) {
        // atZone resolves a wall time that does not exist (a DST gap) forward, and one that
        // exists twice (a DST overlap) to its first occurrence — see NextFireTest.
        is Trigger.AtDateTime -> trigger.at.atZone(zone).toInstant().future(now)?.let { NextFire.Scheduled(it, trigger) }
        is Trigger.OnDate ->
            trigger.date.atTime(defaultTime).atZone(zone).toInstant().future(now)?.let { NextFire.Scheduled(it, trigger) }
        // The hour nobody chose, drawn from the day this person is actually up for. Scheduled
        // and not Sometime: the moment is settled and the app can say it. Not knowing when it
        // will ring is what Trigger.Random is for; this is not having had to decide.
        is Trigger.DayRandom -> RandomDraw.inDay(reminderId, trigger.date, shape.awakeOn(trigger.date), zone)
            .future(now)
            ?.let { NextFire.Scheduled(it, trigger) }
        is Trigger.AtTime -> nextAtTime(trigger, now, zone)?.let { NextFire.Scheduled(it, trigger) }
        is Trigger.Repeat -> nextRepeat(trigger, reminderId, now, zone, shape)?.let { NextFire.Scheduled(it, trigger) }
        // The window opening is the moment it becomes true, and the only moment it produces.
        is Trigger.Interval -> nextAtTime(
            // No days on a window means every day; nextAtTime reads an empty set as "never",
            // which is right for a weekly appointment and wrong for a shape of the day.
            Trigger.AtTime(trigger.from, trigger.days.ifEmpty { DayOfWeek.entries.toSet() }),
            now,
            zone,
        )?.let { NextFire.Scheduled(it, trigger) }
        // Not yet stamped (a preset's copy, a draft): it would start now, so that is what it
        // answers — which is also what the editor should show while it is being written.
        is Trigger.Countdown -> (trigger.startedAt ?: now).plusSeconds(trigger.minutes * 60L)
            .future(now)
            ?.let { NextFire.Scheduled(it, trigger) }
        is Trigger.Location -> NextFire.WhenAt(trigger)
        is Trigger.Random -> nextRandom(trigger, reminderId, now, zone)
    }

private fun Instant.future(now: Instant): Instant? = takeIf { it > now }

/**
 * The next moment a recurrence produces.
 *
 * From yesterday rather than today, because a day's moment is not always inside that day: a
 * window that runs to half one in the morning can put Friday's draw on Saturday, and looking
 * only from today would step over it. A handful of dates is enough to walk past the ones
 * already gone — the dates are in order, so the first one still ahead is the answer.
 */
private fun nextRepeat(trigger: Trigger.Repeat, reminderId: String, now: Instant, zone: ZoneId, shape: DayShape): Instant? {
    val today = now.atZone(zone).toLocalDate()
    return trigger.occurrences(today.minusDays(1))
        .take(REPEAT_PROBE)
        .map { trigger.momentOn(it, reminderId, zone, shape) }
        .firstOrNull { it > now }
}

/** Enough to step over the moments of the last day or two and find the next one still coming. */
private const val REPEAT_PROBE = 8

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

/**
 * The calendar's next moment after [after], with its own fences applied; null when the
 * recurrence is not a calendar, or when the series has run out ([RepeatEnd]).
 *
 * The same walk [nextFireOfRule] does for a rule, and for the same reason: "el día 1, y sólo si
 * estoy en casa" is a search, and it has to stop after [MAX_CANDIDATES] so a calendar that can
 * never clear its fences answers *never* instead of looping.
 */
fun Reminder.calendarMoment(after: Instant, zone: ZoneId, shape: DayShape): Instant? =
    (recurrence as? Recurrence.Calendar)?.nextMoment(id, after, zone, shape)

/** See [Reminder.calendarMoment]. Taken apart from the reminder so a warning can ask it too. */
fun Recurrence.Calendar.nextMoment(reminderId: String, after: Instant, zone: ZoneId, shape: DayShape): Instant? {
    val fences = conditions.filter { it.knownInAdvance }
    var from = after
    repeat(MAX_CANDIDATES) {
        val at = nextRepeat(repeat, reminderId, from, zone, shape) ?: return null
        if (fences.allHoldAt(at, zone)) return at
        from = at
    }
    return null
}

/** The calendar's next date with no fences applied: whether the series itself has anything left. */
internal fun Recurrence.Calendar.nextDateMoment(reminderId: String, after: Instant, zone: ZoneId, shape: DayShape): Instant? =
    nextRepeat(repeat, reminderId, after, zone, shape)

/**
 * When a reminder that has been dealt with comes back: the anchored recurrence's span, counted
 * from the last "hecho". Until then its rules rest — nothing is armed, no place is watched, an
 * arrival is not a ring — and from then they speak again. Null when nothing rests: no anchored
 * recurrence, never dealt with, or no rules to rest (the recurrence is then the ring itself,
 * see [recurrenceMoment]).
 *
 * A rest counted in days ends with the day, not at [dayStart], whenever any rule names an hour
 * of its own — because then the recurrence's job is to say *which day* it comes back on and the
 * rules' job is to say when in it. Ending it at nine in the morning put nine in front of every
 * rule due earlier: "al llegar al trabajo, entre las siete y las ocho, al día siguiente" came
 * back to a window that had closed an hour before it was allowed to look, every day, for ever.
 * The plainest shape had it too — "todos los días a las nueve" with a day that starts at nine
 * ended its rest exactly on the moment, which is not *after* it, so it rang every other day.
 *
 * [dayStart] still governs a rest with nothing to defer to: a reminder that is only a place can
 * ring at any hour it is watched, and without it "the next day" would begin one minute past
 * midnight — the same evening, to anybody who was out. A rest counted in hours is exact and is
 * never moved.
 */
fun Reminder.restUntil(zone: ZoneId, dayStart: LocalTime, shape: DayShape = DayShape.DEFAULT): Instant? {
    if (!recurrence.isAnchored || rules.isEmpty()) return null
    // Nothing rests until it has been dealt with, whichever moment the span is counted from:
    // a reminder still waiting for an answer is overdue, not resting.
    val dealt = lastDealtAt ?: return null
    // A calendar names the day it comes back on; a span counts one out from what happened.
    val back = calendarMoment(recurrenceAnchor(dealt), zone, shape)
        ?: nextRecurrence(recurrence, recurrenceAnchor(dealt), zone, dayStart)
        ?: return null
    if (!recurrence.countsInDays) return back
    if (rules.none { it.trigger.namesAnHour }) return back
    return back.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
}

/**
 * The moment a recurrence counts its span from: the firing when it was asked to, and dealing
 * with it otherwise.
 *
 * [dealt] is the fallback for both, and it has to be: a reminder marked done from Home without
 * ever ringing has no firing to count from, and "cada 6 h desde que suena" must still come
 * back. It is also what keeps the two anchors the same answer for everything that is dealt
 * with the moment it rings, which is most things.
 */
private fun Reminder.recurrenceAnchor(dealt: Instant): Instant =
    if (recurrence.countsFromRinging) lastFiredAt ?: dealt else dealt

/**
 * The moment the recurrence itself rings, when it is the recurrence's turn to say: with no
 * rules at all — "cada 6 h" as a whole reminder — from the moment it was written or last dealt
 * with; and after a rest ([restUntil]) when the rules have nothing left to say. Null otherwise.
 */
fun Reminder.recurrenceMoment(
    now: Instant,
    zone: ZoneId,
    dayStart: LocalTime,
    shape: DayShape = DayShape.DEFAULT,
): Instant? {
    if (!recurrence.isAnchored) return null
    // A calendar knows its own next date and needs no anchor: from now, and past the one that
    // already rang ([searchFrom]), which is the same walk a repeating rule has always had.
    if (recurrence.isCalendar) return calendarMoment(searchFrom(now), zone, shape)
    // Not restUntil: when the recurrence is the thing that rings, the hour the day starts at is
    // exactly the hour it should ring at — there are no rules here with an hour to defer to.
    val dealt = if (rules.isEmpty()) lastDealtAt ?: createdAt else lastDealtAt ?: return null
    val at = nextRecurrence(recurrence, recurrenceAnchor(dealt), zone, dayStart)
    if (at == null) return null
    // Spent, the same way a rule's moment is (see [searchFrom]) — and here it matters more.
    //
    // A recurrence counts from the moment it was DEALT WITH, so a reminder that rings and is
    // ignored has an anchor that does not move: the same past moment would be handed back for
    // ever, armed for ever, and an alarm in the past arrives at once. That is not ringing twice,
    // it is ringing until somebody makes it stop. Once its moment has rung the answer is
    // nothing — Home files it under overdue — until dealing with it moves the anchor on.
    val fired = lastFiredAt ?: return at
    return at.takeIf { it > fired }
}

/**
 * Where to start looking for the next moment: after now, and after the last one that rang.
 *
 * A firing is recorded against the moment it was FOR, not the millisecond the alarm happened to
 * arrive, so this is what makes a moment spent. Without it a reminder that rings a breath early
 * — an alarm is allowed to be — has its own moment still in the future when the scheduler looks
 * again, arms it a second time, and rings twice for one appointment.
 *
 * The millisecond is inclusive because that is the grain everything is stored at: a moment
 * inside the millisecond that rang is the moment that rang.
 */
private fun Reminder.searchFrom(now: Instant): Instant {
    val fired = lastFiredAt?.plusMillis(1) ?: return now
    return if (fired > now) fired else now
}
