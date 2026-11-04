package dev.rwilco.model

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

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

    /**
     * When the phone gets somewhere; no moment to show. [snoozed] means the place is where a
     * "remind me when I get there" is waiting, not a rule of the reminder's own.
     */
    data class WhenAt(override val trigger: Trigger.Location, val snoozed: Boolean = false) : NextFire
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
    // A snooze outranks every rule: it is the person saying "not now, then" — or "not here".
    reminder.snoozedToPlace?.let { return NextFire.WhenAt(it, snoozed = true) }
    val snoozedUntil = reminder.snoozedUntil
    if (snoozedUntil != null && snoozedUntil > now) {
        // A routine's moment has no rule behind it, snoozed or not: its rules ask, and a card
        // wearing the icon of a rule that can never ring would be saying the wrong thing.
        return NextFire.Scheduled(snoozedUntil, reminder.rules.firstOrNull()?.trigger.takeUnless { reminder.isRoutine }, snoozed = true)
    }
    // A routine: the span since the last "hecho" is the ring, and the rules never are — they ask
    // (or count as done) and nothing more, which is the scheduler's business (`nextPrompt`), not
    // this one's. Before the rest on purpose: with rules and no "hecho" yet, restUntil has
    // nothing to say and the deadline would otherwise be waited for by nobody.
    // **A contact's moment is not its own to know.** It is the opening the whole list decides
    // between them (`Contacts.kt`, [contactQueue]), and no function that can see one reminder
    // can answer it. The scheduler arms it from the queue; here it is nothing — and deliberately
    // not the deadline below, which would ring at the day's start, outside every slot, on the
    // actions the row happens to carry.
    if (reminder.isContact) return null
    if (reminder.isRoutine) {
        return reminder.recurrenceMoment(now, zone, dayStart, shape)?.let { NextFire.Scheduled(it, null) }
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
    // "Exactamente cada N": once it has been dealt with, the span's own moment IS the ring and
    // the rules stop deciding — that is what makes thirty days thirty days rather than the next
    // Friday after them. The rules still say when it goes off the FIRST time, which is why this
    // is asked of the rest and not of the reminder. See [SpanLanding.EXACT].
    if (reminder.spanHasTakenOver) {
        return reminder.recurrenceMoment(now, zone, dayStart, shape)
            ?.let { NextFire.Scheduled(it, reminder.rules.firstOrNull()?.trigger) }
    }
    val from = reminder.rulesLookFrom(now, rest)
    val pending = reminder.pendingRules()
    val candidates = pending.mapNotNull { index ->
        // Under "a la vez" the rule is judged with its siblings folded in as conditions — the
        // same rule the firing will judge — so a moment outside a sibling's window is not
        // offered, let alone armed. A fold of two moments can never ring and yields nothing.
        val rule = reminder.ruleInSet(index, shape, zone) ?: return@mapNotNull null
        nextFireOfRule(rule, reminder.id, from, zone, defaultTime, shape, reminder.freshlyWritten)
    }
    if (candidates.isEmpty() && rest != null) {
        return reminder.recurrenceMoment(now, zone, dayStart, shape)?.let { NextFire.Scheduled(it, reminder.rules.firstOrNull()?.trigger) }
    }
    // ANY and TOGETHER alike: the earliest. Under "a la vez" each candidate is already the
    // folded rule's moment — the first instant the whole set holds — so the soonest of them is
    // the ring, and the "last of the pending" reading below belongs to ALL alone.
    if (reminder.ruleMatch != RuleMatch.ALL || !reminder.rulesCombine) {
        val place = candidates.filterIsInstance<NextFire.WhenAt>().firstOrNull()
        // Under "a la vez" a *state* beside a place is the place's hours, not a moment of its
        // own: its opening only rings if the phone is already there. Home once counted down to
        // it — a clock on a reminder that rings on arrival — and, once that opening had passed
        // with nobody there, to the next day's. The place is the honest answer; the opening is
        // still armed (nextWake), for the morning somebody is there already.
        //
        // Every state, not just the window it was written for. A day with no hour and a stretch
        // of the calendar are openings in exactly the same way now ([openingOf]), and a day that
        // reached here as its own rewritten opening used to slip through as a plain moment.
        val together = reminder.ruleMatch == RuleMatch.TOGETHER && reminder.rulesCombine
        val moments = candidates.filterIsInstance<NextFire.Scheduled>()
            .filterNot { together && place != null && it.trigger?.isMoment == false }
        return moments.minByOrNull { it.at }
            ?: candidates.filterIsInstance<NextFire.Sometime>().minByOrNull { it.at }
            ?: place
    }
    // ALL: one rule that can never happen again is one the set can never complete.
    if (candidates.size < pending.size) return null
    candidates.filterIsInstance<NextFire.WhenAt>().firstOrNull()?.let { return it }
    return candidates.maxByOrNull { it.momentOrNull() ?: Instant.MIN }
}

/** The moment this answer carries, where it has one; a place has none by nature. */
val NextFire.moment: Instant? get() = momentOrNull()

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
    // Put off until a place: the circle is the alarm, and there is nothing on the clock.
    if (reminder.snoozedToPlace != null) return null
    val snoozedUntil = reminder.snoozedUntil
    if (snoozedUntil != null && snoozedUntil > now) return Wake(snoozedUntil, null)
    // A routine arms its deadline and nothing else: its rules are questions, on an alarm of
    // their own (`nextPrompt`), never a moment to ring. See nextFire.
    // **A contact's moment is not its own to know.** It is the opening the whole list decides
    // between them (`Contacts.kt`, [contactQueue]), and no function that can see one reminder
    // can answer it. The scheduler arms it from the queue; here it is nothing — and deliberately
    // not the deadline below, which would ring at the day's start, outside every slot, on the
    // actions the row happens to carry.
    if (reminder.isContact) return null
    if (reminder.isRoutine) return reminder.recurrenceMoment(now, zone, dayStart, shape)?.let { Wake(it, null) }
    // A recurrence's moment is the ring itself: there is no rule behind it to tick off. It is
    // the alarm when there are no rules, and after a rest when the rules have nothing left to
    // say (see nextFire); a place among the rules is something left to say, and arms nothing.
    if (reminder.rules.isEmpty()) return (reminder.recurrenceMoment(now, zone, dayStart, shape) ?: reminder.comeBackMoment(now, zone, dayStart, shape))?.let { Wake(it, null) }
    val rest = reminder.restUntil(zone, dayStart, shape)
    // The same as nextFire: under "exactamente cada N" the moment to arm is the span's own, and
    // it is the ring rather than a note to take (see [SpanLanding.EXACT]).
    if (reminder.spanHasTakenOver) {
        return reminder.recurrenceMoment(now, zone, dayStart, shape)?.let { Wake(it, null) }
    }
    val from = reminder.rulesLookFrom(now, rest)
    val candidates = reminder.pendingRules().mapNotNull { index ->
        val rule = reminder.ruleInSet(index, shape, zone) ?: return@mapNotNull null
        nextFireOfRule(rule, reminder.id, from, zone, defaultTime, shape, reminder.freshlyWritten)?.let { index to it }
    }
    if (candidates.isEmpty()) {
        val back = rest?.let { reminder.recurrenceMoment(now, zone, dayStart, shape) } ?: reminder.comeBackMoment(now, zone, dayStart, shape)
        if (back != null || rest != null) return back?.let { Wake(it, null) }
    }
    return candidates
        .mapNotNull { (index, next) -> next.momentOrNull()?.let { Wake(it, index) } }
        .minByOrNull { it.at }
}

/**
 * A rule's next fire: the first moment its trigger produces that all of its conditions hold at.
 *
 * Walks candidate moments rather than solving for them, because "every day at nine, and only in
 * June" is a search either way. A walk that keeps failing stops at [SEARCH_HORIZON] — so a rule
 * that can never be satisfied ("at 09:00, and only between 18:00 and 22:00") answers "never"
 * instead of looping, which is also how [warnings] knows to say so — and a candidate that holds
 * is the answer wherever it is. It used to stop after sixty-four *candidates*, which for a daily
 * moment is nine weeks: "a las nueve, sólo del 1 al 15 de agosto" written in April was called
 * never, by the editor and the scheduler alike, and the net could not catch it either. A
 * stretch of the calendar still ahead is not walked up to a day at a time: nothing before its
 * first day can clear it, so the walk goes straight there ([skipTo]), and one already behind
 * never holds again and is not walked at all. A place is judged when it happens, not now, so it
 * comes back untouched.
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
    /** When the reminder was written, while nothing has happened to it since ([Reminder.freshlyWritten]). */
    writtenAt: Instant? = null,
): NextFire? {
    val fences = rule.conditions.filter { it.knownInAdvance }
    if (fences.overFor(now, zone)) return null
    openAtWriting(rule, writtenAt, now, zone, shape, fences)?.let { return it }
    var after = now
    val horizon = now + SEARCH_HORIZON
    // The rule's own hour fences reach the opening, so a day with no hour opens at the first
    // minute they allow instead of being rejected below (see openingOf) — and its day fences
    // reach a random draw, which is made on the days they allow (RandomDraw.draws).
    val windows = rule.windows()
    val dayFences = fences.filter { it is Condition.OnDays || it is Condition.OnMonthDays || it is Condition.DateRange }
    repeat(MAX_CANDIDATES) {
        val candidate = nextFireOf(rule.trigger, reminderId, after, zone, defaultTime, shape, windows, dayFences) ?: return null
        val at = when (candidate) {
            is NextFire.Scheduled -> candidate.at
            is NextFire.Sometime -> candidate.at
            is NextFire.WhenAt -> return candidate
        }
        if (fences.allHoldAt(at, zone)) return candidate
        if (at > horizon) return null
        after = fences.skipTo(at, zone)
    }
    return null
}

/**
 * **A stretch open when it is written counts from then** (2026-11-04). "De 17 a 19" saved at
 * half past five, "los viernes" saved on a Friday, "del 25 al 28" saved on the 28th after its
 * hour: each answered with its *next* opening — tomorrow, next Friday, never — as if the stretch
 * it was written in had already been spent. A state that holds at the minute it is written, and
 * still holds now, rings the minute after it was written, inside the rule's own fences and in
 * the person's waking hours; otherwise the walk goes on as ever.
 */
internal fun openAtWriting(rule: TriggerRule, writtenAt: Instant?, now: Instant, zone: ZoneId, shape: DayShape, fences: List<Condition>): NextFire? {
    val written = writtenAt ?: return null
    val trigger = rule.trigger
    if (trigger !is Trigger.Interval && trigger !is Trigger.Weekday && trigger !is Trigger.DateRange) return null
    val state = trigger.asState(shape) ?: return null
    val at = written.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60)
    // **Only while that minute is still ahead.** Once it has come — rung, or delivered and dropped
    // because the rule's place said no — the stretch is the place-condition circle's to ring
    // (`stretchOwed`), and handing the same minute back armed an alarm in the past, which arrives
    // at once, is dropped again, and so on for the rest of the evening (2026-11-04 review). A
    // phone that was off across it is the missed-moment path's (the armed moment is held).
    if (at <= now) return null
    if (!state.holdsAt(written, zone) || !state.holdsAt(at, zone)) return null
    if (!fences.allHoldAt(at, zone) || !shape.awakeAt(at, zone)) return null
    // A day is the person's from when they get up: "los sábados" written at half past twelve on
    // Friday night is Saturday's, and opens at Saturday's waking hour, not now.
    if (trigger !is Trigger.Interval && at.atZone(zone).toLocalDateTime() < shape.awakeOn(at.atZone(zone).toLocalDate()).from) return null
    return NextFire.Scheduled(at, trigger)
}

/**
 * When the reminder was written, while it is still the reminder as written: never rung, never
 * dealt with, nothing done ahead. What [openAtWriting] counts a stretch from.
 */
val Reminder.freshlyWritten: Instant?
    get() = createdAt.takeIf { lastFiredAt == null && lastDealtAt == null && dealtThrough == null }

/**
 * How far a walk that keeps failing is allowed to go before it answers "never". Only a
 * *failing* walk: a candidate that holds is returned wherever it lies. Five years is past any
 * calendar somebody writes and cheap to walk a day at a time.
 */
private val SEARCH_HORIZON: Duration = Duration.ofDays(5 * 366)

/** A belt under [SEARCH_HORIZON], for a trigger whose moments are closer together than days. */
private const val MAX_CANDIDATES = 2000

/**
 * Whether a stretch of the calendar among these fences is already behind [now]: it will never
 * hold again, and there is nothing to walk.
 */
internal fun List<Condition>.overFor(now: Instant, zone: ZoneId): Boolean {
    val today = now.atZone(zone).toLocalDate()
    return any { it is Condition.DateRange && it.to < today }
}

/**
 * Where the walk goes on from once [at] has failed: just past it, or — when a stretch of the
 * calendar still ahead is among the fences — to the eve of that stretch, because no moment
 * before its first day can clear it and walking there a day at a time is what ran the old walk
 * out of candidates. The latest first day of them, since every fence has to hold at once.
 */
internal fun List<Condition>.skipTo(at: Instant, zone: ZoneId): Instant {
    val day = at.atZone(zone).toLocalDate()
    val ahead = filterIsInstance<Condition.DateRange>().filter { it.from > day }.maxOfOrNull { it.from } ?: return at
    return ahead.atStartOfDay(zone).toInstant().minusMillis(1)
}

/**
 * One trigger's next fire, or null when it has nothing left to do.
 *
 * [fences] only reach the two shapes that *leave* the hour to the day — a date with none and a
 * calendar with none — and move their opening to the first minute the fences allow. Everything
 * with an hour of its own is left to the walk in [nextFireOfRule], which asks the fences of each
 * moment in turn.
 */
fun nextFireOf(
    trigger: Trigger,
    reminderId: String,
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    shape: DayShape = DayShape.DEFAULT,
    fences: List<Condition.TimeWindow> = emptyList(),
    /** Day fences, which only a random draw reaches: see [RandomDraw.draws]. */
    dayFences: List<Condition> = emptyList(),
): NextFire? =
    when (trigger) {
        // atZone resolves a wall time that does not exist (a DST gap) forward, and one that
        // exists twice (a DST overlap) to its first occurrence — see NextFireTest.
        is Trigger.AtDateTime -> trigger.at.atZone(zone).toInstant().future(now)?.let { NextFire.Scheduled(it, trigger) }
        // The hour nobody chose is the person's [defaultTime] — moved to when they get up on a day
        // it would find them asleep ([DayShape.awakeHourOn], 2026-11-04): nine on a Saturday for
        // somebody up at ten rang hushed, which is the card with no sound.
        is Trigger.OnDate ->
            shape.awakeHourOn(trigger.date, defaultTime).atZone(zone).toInstant().future(now)?.let { NextFire.Scheduled(it, trigger) }
        // The hour nobody chose: the opening of the stretch it was given, or of the day this
        // person is actually up for. Not a draw — see [openingOf]. Scheduled and not Sometime:
        // the moment is settled and the app can say it out loud. Not knowing when it will ring
        // is what Trigger.Random is for; this is not having had to decide.
        is Trigger.DayRandom ->
            openingOf(trigger.window?.on(trigger.date) ?: shape.awakeOn(trigger.date), fences)
                .atZone(zone)
                .toInstant()
                .future(now)
                ?.let { NextFire.Scheduled(it, trigger) }
        is Trigger.AtTime -> nextAtTime(trigger, now, zone)?.let { NextFire.Scheduled(it, trigger) }
        is Trigger.Repeat -> nextRepeat(trigger, reminderId, now, zone, shape, fences)?.let { NextFire.Scheduled(it, trigger) }
        // The next such time on a day it allows. The same walk the window's opening takes, and
        // the same reading of an empty day set: a shape of the day, not a weekly appointment.
        is Trigger.TimeOfDay -> nextAtTime(
            Trigger.AtTime(trigger.time, trigger.days.ifEmpty { DayOfWeek.entries.toSet() }),
            now,
            zone,
        )?.let { NextFire.Scheduled(it, trigger) }
        // The third shape that leaves the hour to the day, and read exactly like the first: the
        // stretch this person is up for on that day, opened at the first minute the rule's own
        // hour fences allow. Its *state* is the whole day and not that stretch (see
        // [Trigger.asState]) — "los viernes" means the whole of Friday to anybody who says it —
        // but a ring has to land at an hour somebody is awake to hear it.
        is Trigger.Weekday -> nextWeekday(trigger, now, zone, shape, fences)?.let { NextFire.Scheduled(it, trigger) }
        // The window opening is the moment it becomes true, and the only moment it produces.
        is Trigger.Interval -> nextInterval(trigger, now, zone, fences)?.let { NextFire.Scheduled(it, trigger) }
        // A stretch of the calendar names no hour, so it opens at the one a date with no hour
        // has always meant — and at the same hour on each day it is still open, which is what
        // Trigger.Interval does with a stretch of the day, one unit up. Nothing else in the app
        // would survive being written at six in the evening: a range that only ever rang at
        // 09:00 on its first day was, for anybody who wrote one that afternoon, a reminder that
        // silently never rang and an editor saying "ya ha pasado" of a fortnight still open.
        // Bounded by the range and spent the moment somebody deals with it, so it is not the
        // open-ended repeat that "Vuelve" alone is allowed to say.
        is Trigger.DateRange -> {
            val today = now.atZone(zone).toLocalDate()
            generateSequence(maxOf(trigger.from, today)) { it.plusDays(1) }
                .takeWhile { it <= trigger.to }
                // Today's hour and tomorrow's: one of the two is ahead, or the range is over.
                .take(2)
                // At the hour they get up, on a day [defaultTime] would find them asleep ("este
                // finde", a Saturday at nine for somebody up at ten, rang hushed: 2026-11-04).
                // **And at the first minute its own hours allow** ([openingOf]), the way a day
                // with no hour has always opened: "del 1 al 15, y sólo si de 18 a 22" offered
                // nine o'clock, its fence refused it every day, and the editor said "nunca
                // sonará" of a fortnight of evenings.
                .map { day ->
                    (if (fences.isEmpty()) shape.awakeHourOn(day, defaultTime) else openingOf(shape.awakeOn(day), fences)).atZone(zone).toInstant()
                }
                .firstOrNull { it > now }
                ?.let { NextFire.Scheduled(it, trigger) }
        }
        // Not yet stamped (a preset's copy, a draft): it would start now, so that is what it
        // answers — which is also what the editor should show while it is being written.
        is Trigger.Countdown -> (trigger.startedAt ?: now).plusSeconds(trigger.minutes * 60L)
            .future(now)
            ?.let { NextFire.Scheduled(it, trigger) }
        // Never stamped and never stored on a reminder (see settleRelativeDates): asked
        // before it is written, it answers for the day it would be written on — which is what
        // the editor and the preset list should show, and what the reminder will actually get.
        is Trigger.RelativeDate -> nextFireOf(trigger.on(now.atZone(zone).toLocalDate(), shape.wakingDate(now.atZone(zone).toLocalDateTime())), reminderId, now, zone, defaultTime, shape, fences)
        is Trigger.Location -> NextFire.WhenAt(trigger)
        // Drawn inside the rule's hour fences, like the two shapes above that leave the hour
        // to the day (see RandomDraw.draws).
        is Trigger.Random -> nextRandom(trigger, reminderId, now, zone, fences, dayFences)
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
private fun nextRepeat(
    trigger: Trigger.Repeat,
    reminderId: String,
    now: Instant,
    zone: ZoneId,
    shape: DayShape,
    fences: List<Condition.TimeWindow> = emptyList(),
): Instant? {
    val today = now.atZone(zone).toLocalDate()
    return trigger.occurrences(today.minusDays(1))
        .take(REPEAT_PROBE)
        .map { trigger.momentOn(it, reminderId, zone, shape, fences) }
        .firstOrNull { it > now }
}

/** Enough to step over the moments of the last day or two and find the next one still coming. */
private const val REPEAT_PROBE = 8

/**
 * The next allowed day's opening, at most a fortnight out: a week reaches every weekday, and the
 * day after covers an opening that today's has already gone past.
 */
private fun nextWeekday(
    trigger: Trigger.Weekday,
    now: Instant,
    zone: ZoneId,
    shape: DayShape,
    fences: List<Condition.TimeWindow>,
): Instant? {
    if (trigger.days.isEmpty()) return null
    val today = now.atZone(zone).toLocalDate()
    for (offset in 0L..7L) {
        val date = today.plusDays(offset)
        if (date.dayOfWeek !in trigger.days) continue
        val at = openingOf(shape.awakeOn(date), fences).atZone(zone).toInstant()
        if (at > now) return at
    }
    return null
}

/**
 * When a stretch of the day next becomes true: the first minute of it the rule's own hour
 * fences allow, on the next day the window names.
 *
 * **The opening used to be taken bare** — the window's own `from` and nothing else — while the
 * two other shapes that leave the hour to the day ([Trigger.Weekday], [Trigger.DayRandom]) have
 * always opened at the first minute their fences allow ([openingOf]). So "de 09:00 a 11:00, y
 * sólo si es de 10:00 a 12:00" offered nine o'clock, its own fence refused it, the walk offered
 * the next day's nine o'clock, and after [MAX_CANDIDATES] days of that the rule came out as one
 * that can never ring — silently, and in the editor as "esto no puede sonar nunca". It rings at
 * ten, which is what it says.
 *
 * The same silence reached every "a la vez" with a window in it, because that fold *is* a fence:
 * "de 09:00 a 11:00" beside "de 10:00 a 12:00" is a set that plainly holds at ten and never
 * rang. With no fences at all this is the walk it always was, minute for minute.
 *
 * An empty day set is every day, as it is everywhere a window is a shape of the day rather than
 * a weekly appointment.
 */
private fun nextInterval(
    trigger: Trigger.Interval,
    now: Instant,
    zone: ZoneId,
    fences: List<Condition.TimeWindow>,
): Instant? {
    val days = trigger.days.ifEmpty { DayOfWeek.entries.toSet() }
    val today = now.atZone(zone).toLocalDate()
    for (offset in 0L..7L) {
        val date = today.plusDays(offset)
        if (date.dayOfWeek !in days) continue
        val at = openingOf(DayWindow(trigger.from, trigger.to).on(date), fences).atZone(zone).toInstant()
        if (at > now) return at
    }
    return null
}

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

/**
 * Scans the current period and a few ahead: enough to cross any gap the day filter can make —
 * or, with fences, any gap the fences can make, which is a year of days or of weeks. A fence
 * with a date on it (a dated sibling folded in under "a la vez") names the one period worth
 * drawing; two that disagree name none.
 */
private fun nextRandom(
    trigger: Trigger.Random,
    reminderId: String,
    now: Instant,
    zone: ZoneId,
    fences: List<Condition.TimeWindow> = emptyList(),
    days: List<Condition> = emptyList(),
): NextFire.Sometime? {
    val today = now.atZone(zone).toLocalDate()
    // A stretch of dates still ahead is where the scan starts: from today, a year of periods could
    // run out before it and call a draw two years off "never" (2026-11-04 review).
    val start = days.filterIsInstance<Condition.DateRange>().maxOfOrNull { it.from }?.takeIf { it > today } ?: today
    val first = RandomDraw.periodIndex(start, trigger.period)
    val dates = fences.mapNotNull { it.date }.distinct()
    if (dates.size > 1) return null
    val periods = when {
        dates.isNotEmpty() -> listOf(RandomDraw.periodIndex(dates.single(), trigger.period))
        fences.isEmpty() && days.isEmpty() -> first until first + (if (trigger.period == Period.DAY) 8 else 2)
        else -> first until first + (if (trigger.period == Period.DAY) 400 else 60)
    }
    for (index in periods) {
        val draws = RandomDraw.draws(trigger, reminderId, index, zone, fences, days)
        val at = draws.firstOrNull { it > now } ?: continue
        // A window that crosses midnight puts its small-hours draws on the next calendar day;
        // the window they were drawn from is the one that opened the evening before.
        val local = at.atZone(zone)
        val crosses = trigger.to <= trigger.from
        val day = if (crosses && local.toLocalTime() < trigger.from) local.toLocalDate().minusDays(1) else local.toLocalDate()
        return NextFire.Sometime(
            at = at,
            windowStart = day.atTime(trigger.from).atZone(zone).toInstant(),
            windowEnd = (if (crosses) day.plusDays(1) else day).atTime(trigger.to).atZone(zone).toInstant(),
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
 * estoy en casa" is a search, and a failing one has to stop ([SEARCH_HORIZON]) so a calendar
 * that can never clear its fences answers *never* instead of looping.
 */
fun Reminder.calendarMoment(after: Instant, zone: ZoneId, shape: DayShape): Instant? =
    (recurrence as? Recurrence.Calendar)?.nextMoment(id, after, zone, shape)

/** See [Reminder.calendarMoment]. Taken apart from the reminder so a warning can ask it too. */
fun Recurrence.Calendar.nextMoment(reminderId: String, after: Instant, zone: ZoneId, shape: DayShape): Instant? {
    val fences = conditions.filter { it.knownInAdvance }
    // The hour fences reach the opening itself (openingOf): "el primer viernes de cada mes, sólo
    // de 16 a 17" with no hour opens at 16:00, instead of opening at breakfast and being
    // rejected every month.
    val windows = conditions.filterIsInstance<Condition.TimeWindow>()
    if (fences.overFor(after, zone)) return null
    var from = after
    val horizon = after + SEARCH_HORIZON
    // No belt under the horizon here: a calendar's moments are a day apart at the least, and
    // nextRepeat only ever answers strictly after [from], so the horizon alone ends the walk.
    while (true) {
        val at = nextRepeat(repeat, reminderId, from, zone, shape, windows) ?: return null
        if (fences.allHoldAt(at, zone)) return at
        if (at > horizon) return null
        from = fences.skipTo(at, zone)
    }
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
    // A routine's rules never rest: a question is worth asking every time, and the span is not
    // theirs to wait for — it rings on its own (see [Reminder.isRoutine]).
    if (isRoutine) return null
    // **"La próxima vez que llegue a…" is a promise about the next arrival** (2026-11-04). Given
    // under «desde que suena», the rest counted from the ring it answered held the doorway quiet,
    // and the next arrival — the one the button named — rang nothing. Until that ring, the rules
    // speak as if nothing rested them.
    if (snoozedToRules) return null
    // Nothing rests until it has been dealt with — or, for a span counted from the ringing,
    // until it has rung: that is the anchor somebody picks because they are not going to
    // answer, and read off lastDealtAt alone it never spoke until they did. Otherwise a
    // reminder still waiting for an answer is overdue, not resting.
    val dealt = lastDealtAt ?: lastFiredAt.takeIf { recurrence.countsFromRinging } ?: return null
    // A calendar names the day it comes back on; a span counts one out from what happened.
    val anchor = recurrenceAnchor(dealt, zone, shape, dayStart)
    // Whole days said in hours ("cada 24 h") keep the wall clock across a change of time: 24 h on
    // from the 27th of March's nine o'clock is ten on the 28th, which skipped the 28th's nine.
    val wallDays = (recurrence as? Recurrence.After)?.takeIf { it.unit == RecurrenceUnit.HOURS && it.amount % 24 == 0 }
        ?.let { anchor.atZone(zone).plusDays(it.amount / 24L).toInstant() }
    val back = wallDays ?: calendarMoment(anchor, zone, shape)
        ?: nextRecurrence(recurrence, anchor, zone, dayStart)
        ?: return null
    // To the minute: a ring is written down when its alarm arrives, a moment past the hour, and
    // the rest counted from it would end that same moment past the rule's next hour.
    if (!recurrence.countsInDays) return back.truncatedTo(ChronoUnit.MINUTES)
    // "El más cercano": the span's day bent to the nearest day the rules allow, which is the
    // only reading of the three that can land the rest BEFORE the span is up. That is the whole
    // point of it — thirty days to the nearest Friday is sometimes the Friday two days early —
    // and it is why the day is moved here rather than left to the rules' own walk, which only
    // ever looks forward. See [SpanLanding].
    val moved = if (recurrence.landing == SpanLanding.NEAREST) {
        val here = back.atZone(zone)
        val nearest = nearestAllowedDay(here.toLocalDate(), daysNamedByRules())
        if (nearest == here.toLocalDate()) back else nearest.atTime(here.toLocalTime()).atZone(zone).toInstant()
    } else {
        back
    }
    if (rules.none { it.trigger.namesAnHour }) return moved
    return moved.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
}

/**
 * Where the rules start looking for their next moment: past the one already spent
 * ([searchFrom] — it rang, or it was dealt with ahead of time) and past any rest the recurrence
 * is holding them quiet for ([restUntil]).
 *
 * Named because three places need exactly this answer and only two of them had it. [nextFire]
 * and [nextWake] spelled it out; Home's cards did not, and asked each rule from *now* — so a
 * "los lunes, y vuelve cada semana" dealt with on a Thursday went on drawing the Monday it had
 * just been dealt through, on the one screen somebody would look at to check.
 *
 * A breath before the rest's end, because the rules look for moments strictly *after* where they
 * start: a moment that falls exactly where the rest ends is the one the rest was waiting for.
 * A rest in hours lands on the rule's hour ("a las 9, y vuelve cada 24 h desde que suena"), and
 * looking from the rest itself skipped it — every other day (0.172.0).
 */
fun Reminder.rulesLookFrom(now: Instant, rest: Instant?): Instant = maxOf(searchFrom(now), rest?.minusMillis(1) ?: now)

/**
 * Whether an "exactly the span" recurrence has taken the rules out of the loop.
 *
 * [SpanLanding.EXACT] says thirty days means thirty days, and the price is that the rules stop
 * deciding once there is a span to count — which is stated on the button. `nextFire`/`nextWake`
 * honour it by answering with the recurrence's own moment, and that is enough for everything
 * driven by an alarm: a clock rule is never armed again, so it can never ring.
 *
 * **A place is the door that stays open.** It has no armed moment to check by design — an
 * arrival happens when it happens — so a circle still registered with the system would go on
 * ringing a reminder whose rules no longer decide anything, once a round, for ever. So the same
 * question is asked of the watch (`watchedCircles`), of the fences (`geofenceChoices`) and of
 * the firing itself, which is the door they all come through.
 *
 * Said without a clock on purpose, so the fences can ask it too: it is exactly the condition
 * under which [restUntil] has a rest to give, minus the arithmetic.
 */
val Reminder.spanHasTakenOver: Boolean
    get() = recurrence.landsExactly && rules.isNotEmpty() &&
        (lastDealtAt != null || (recurrence.countsFromRinging && lastFiredAt != null))

/**
 * The days of the week the rules limit this reminder to; empty when they name none.
 *
 * The union across the rules and their fences, which is the reading "cualquiera" asks for and
 * the harmless one everywhere else: under "a la vez" a set whose rules name *different* days
 * cannot ring at all, and one that names days in only one of them has only those to union.
 */
fun Reminder.daysNamedByRules(): Set<DayOfWeek> =
    rules.flatMapTo(LinkedHashSet()) { rule -> rule.trigger.namedDays + rule.conditions.flatMap { it.namedDays } }

/**
 * The moment a recurrence counts its span from: the firing when it was asked to, and dealing
 * with it otherwise.
 *
 * [dealt] is the fallback for both, and it has to be: a reminder marked done from Home without
 * ever ringing has no firing to count from, and "cada 6 h desde que suena" must still come
 * back. It is also what keeps the two anchors the same answer for everything that is dealt
 * with the moment it rings, which is most things.
 */
private fun Reminder.recurrenceAnchor(dealt: Instant, zone: ZoneId, shape: DayShape, dayStart: LocalTime = DEFAULT_DAY_START): Instant {
    // A routine counts from the last "hecho" and from nothing else: there is no moment dealt
    // with ahead (momentDealtWith answers null), and a [dealtThrough] left behind by an "after"
    // recurrence edited into a routine must not push the count into the future.
    if (isRoutine) return dealt
    val from = when {
        recurrence.countsFromRinging -> rangFor(zone, shape) ?: dealt
        else -> answeredRingDay(dealt, zone, dayStart) ?: dealt
    }
    // A moment dealt with before it arrived is the moment the span counts from: ticking off
    // tomorrow's two o'clock this morning makes the next one a day after *that*, not a day
    // after the morning it was ticked off in.
    return listOfNotNull(from, dealtThrough).max()
}

/**
 * The moment the last ring was **for**, as a span «desde que suena» counts it: its rule's own
 * moment, when that rule keeps an hour, and otherwise the ring itself.
 *
 * A ring put off ten minutes rings ten minutes late. Counted from there, "a las 9, y vuelve cada
 * 24 h desde que suena" ended its rest at 9:10 the next day — past its own nine o'clock — and
 * skipped a whole day (2026-11-04). The snooze's ring is written against the rule it put off
 * ([ruleRungBy]), so the moment that rule last had, up to a day before the ring, is the one the
 * rhythm runs from. A catch-up rung late for its moment reads the same way, which is what "on
 * the hour" was always meant to be.
 */
private fun Reminder.rangFor(zone: ZoneId, shape: DayShape): Instant? {
    val fired = lastFiredAt ?: return null
    val trigger = lastFiredRule?.let { rules.getOrNull(it) }?.trigger ?: return fired
    // Only the shapes with an hour of their own: the rest are a default or a draw, and the ring
    // is as good a reading of them as any.
    if (trigger !is Trigger.AtDateTime && trigger !is Trigger.AtTime && trigger !is Trigger.TimeOfDay && trigger !is Trigger.Interval) return fired
    var latest = fired
    var from = fired - RANG_LOOKBACK
    while (true) {
        // The default hour is never asked of these four; any value will do.
        val at = nextFireOf(trigger, id, from, zone, DEFAULT_DAY_START, shape)?.moment ?: break
        if (at > fired) break
        latest = at
        from = at
    }
    return latest
}

/**
 * Under «desde el hecho» in whole days, a "hecho" that answers a ring counts from **the ring's
 * day** (2026-11-04). "A las 22:00, y vuelve al día siguiente": answered at a quarter past
 * midnight, the next day was counted from the small hours and that evening was skipped; a weekly
 * Monday answered on Tuesday morning skipped a whole week. The day being answered is the day it
 * rang on. The hour stays the "hecho"'s own, so the span is never shorter than it says by more
 * than the lateness of the answer — and never longer.
 *
 * "Answers a ring": the latest ring is not after the "hecho", and the "hecho" came before the
 * span counted from that ring was up — later than that it is a "hecho" about something else.
 */
private fun Reminder.answeredRingDay(dealt: Instant, zone: ZoneId, dayStart: LocalTime): Instant? {
    if (recurrence !is Recurrence.After || !recurrence.countsInDays) return null
    val rang = lastFiredAt?.takeIf { it <= dealt } ?: return null
    val ringDay = rang.atZone(zone).toLocalDate()
    val dealtHere = dealt.atZone(zone)
    if (ringDay == dealtHere.toLocalDate()) return null
    val spanUp = nextRecurrence(recurrence, rang, zone, dayStart) ?: return null
    if (dealt >= spanUp) return null
    // Never before the ring itself: under "a la misma hora" a hecho at 00:15 for a 23:00 ring
    // would otherwise count from the ring's day at 00:15 and end its rest at the hecho.
    return maxOf(rang, ringDay.atTime(dealtHere.toLocalTime()).atZone(zone).toInstant())
}

/** How far back a ring looks for the moment it was for: a snooze is seldom longer than a day. */
private val RANG_LOOKBACK: Duration = Duration.ofDays(1)

/**
 * «Desde el hecho», rung, and nobody has answered: the span counts from the ring until somebody
 * does (the owner, 2026-11-04). A swipe is not an answer, and a reminder whose rules had nothing
 * left to say — a date that has been, a list of one place — used to wait for a "hecho" that never
 * came: one word from the safety net, and then silence for as long as it lived. Now it comes
 * back a span after it rang, and again a span after that, until it is dealt with.
 */
internal val Reminder.unansweredComesBack: Boolean
    get() = !isRoutine && ringUnanswered &&
        ((recurrence is Recurrence.After && !recurrence.countsFromRinging) || recurrence is Recurrence.Calendar || recurrence is Recurrence.MonthlyWeekday)

/**
 * For a span «desde el hecho», the rest a ring nobody answered would have had if it had been
 * answered as it rang: what a state place that has had its say waits out before it asks again
 * ([presenceAlreadyRang], `watchedCircles`). Null for anything else.
 */
internal fun Reminder.unansweredRest(zone: ZoneId, dayStart: LocalTime, shape: DayShape): Instant? =
    if (unansweredComesBack) copy(lastDealtAt = lastFiredAt).restUntil(zone, dayStart, shape) else null

/**
 * The alarm for [unansweredComesBack]: one span after the ring nobody answered. **Only the
 * alarm's answer** ([nextWake]), never [nextFire]'s: Home keeps the card where a ring still
 * waiting for its answer belongs — Vencidos, with the moment it missed — and the "hecho" that
 * finally comes is about that ring, not a moment ahead to be spent ([momentDealtWith]).
 */
private fun Reminder.comeBackMoment(now: Instant, zone: ZoneId, dayStart: LocalTime, shape: DayShape): Instant? {
    if (!unansweredComesBack) return null
    val rang = lastFiredAt ?: return null
    // **Never a moment already gone** (2026-11-04 review). A come-back is not owed: a calendar
    // date its own fence turned away, handed back as the next alarm, arrived at once and was
    // turned away again, for as long as the fence said no; and a ring unanswered since September
    // rang the moment this build first armed it. A come-back armed and then slept through is the
    // missed-moment path's, which holds the armed moment.
    val after = maxOf(rang.plusMillis(1), now)
    // **A calendar comes back on its own dates, answered or not**: "el 26 a las 20:00, y vuelve
    // el 26 de cada mes", rung and not answered, waited for a "hecho" for ever while its card
    // said "en sus propias fechas". Its next date after the ring is the alarm.
    if (recurrence is Recurrence.Calendar) return calendarMoment(after, zone, shape)
    val first = nextRecurrence(recurrence, recurrenceAnchor(rang, zone, shape, dayStart), zone, dayStart) ?: return null
    return if (first > now) first else nextRecurrence(recurrence, now, zone, dayStart)
}

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
    // With rules, only once it has been dealt with — or has rung, for a span that counts from
    // the ringing (see restUntil). A routine counts from where its count starts — the day it was
    // written, or the moment somebody named ([routineAnchor]) — until it has been done once,
    // rules or no rules: its rules are questions, not a first ring.
    val dealt = when {
        isRoutine -> routineAnchor()
        rules.isEmpty() -> lastDealtAt ?: createdAt
        else -> (lastDealtAt ?: lastFiredAt.takeIf { recurrence.countsFromRinging }) ?: return null
    }
    val at = nextRecurrence(recurrence, recurrenceAnchor(dealt, zone, shape, dayStart), zone, dayStart)
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
 * Whether this reminder **cannot ring at all** — not "has nothing left", which is the ordinary
 * end of a reminder, but "asks for something that can never happen".
 *
 * "Todos los lunes a las 9:00, y sólo si es de 18:00 a 22:00" is the shape of it. [warnings]
 * says so while it is being written, and after the save the app said nothing about it ever
 * again: it went to Home's overdue list with no row to explain itself — the moment it missed is
 * worked out from a past moment it never had — and the safety net let it go for the same
 * reason. The one arrangement the app can *prove* will fail was the one it said least about.
 *
 * So it is one predicate, asked twice: by the editor over the save button and by Home on the
 * card. And it is [warnings] underneath rather than arithmetic of its own, because that walk
 * already draws the line this needs and is already what the person was shown: **a shape with no
 * possible moment** (`NeverFires`, `PlacesConflict`, `MomentsCannotCoincide`, and under "todos"
 * the `NeverCompletes` one dud rule inflicts on the set) is this; **a moment that has simply
 * been and gone** (`InPast`) is not, and has a sentence of its own. A date already past when it
 * was written is exactly that second thing, which is why this cannot be "nothing ahead and
 * nothing behind": [lastMomentGone] walks from the day the reminder was written and never sees
 * it.
 *
 * Two clauses of its own around that. **Active**, because a paused reminder is quiet by
 * request. And **something ahead already** answers no before any of it: a reminder with a next
 * moment is not in question, whatever else its rules say.
 */
fun Reminder.cannotRing(
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    dayStart: LocalTime = DEFAULT_DAY_START,
    shape: DayShape = DayShape.DEFAULT,
): Boolean {
    if (status != Status.ACTIVE) return false
    if (nextFire(this, now, zone, defaultTime, dayStart, shape) != null) return false
    // No rules: the recurrence is the whole arrangement, and only a calendar can be impossible
    // in itself — a span always has a next step. OVER is a series that ran and ended, which is
    // not this.
    if (rules.isEmpty()) {
        return recurrence.isAnchored && recurrenceWarning(recurrence, now, zone, shape) == RecurrenceWarning.NEVER_FIRES
    }
    return warnings(rules, now, zone, defaultTime, ruleMatch, shape, id, deadline).any {
        it is ValidationWarning.NeverFires ||
            it is ValidationWarning.NeverCompletes ||
            it is ValidationWarning.MomentsCannotCoincide ||
            it is ValidationWarning.PlacesConflict
    }
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
internal fun Reminder.searchFrom(now: Instant): Instant {
    // Two ways a moment is spent: it rang, or it was dealt with before it could ([dealtThrough]).
    val spent = listOfNotNull(lastFiredAt, dealtThrough).maxOrNull()?.plusMillis(1) ?: return now
    return if (spent > now) spent else now
}
