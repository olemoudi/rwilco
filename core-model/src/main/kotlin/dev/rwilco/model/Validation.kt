package dev.rwilco.model

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

const val MAX_TEXT_LENGTH = 500
const val MAX_LABEL_LENGTH = 40
/**
 * Fifty metres is a doorway rather than a neighbourhood, and it is the floor because below it
 * nothing can tell you are there: a place only counts as entered by a fix at least as accurate
 * as the place is wide, so a tight one waits for GPS — which is what the watch turns on when a
 * line is close and the phone moving — and the phone's own geofences get vaguer the smaller the
 * circle. Tight works; it just leans on the satellites.
 */
const val MIN_RADIUS_M = 50
const val MAX_RADIUS_M = 1000
/** A countdown of nothing is not a countdown; a week is as far as the sheet lets anybody go. */
const val MIN_COUNTDOWN_MINUTES = 1
const val MAX_COUNTDOWN_MINUTES = 7 * 24 * 60
const val MIN_RANDOM_TIMES = 1
const val MAX_RANDOM_TIMES = 5

/**
 * What blocks saving — which is only the words, and a trigger that is nonsense in itself.
 *
 * Not having a trigger is not an error: a reminder with nothing but text and a tag is a note on
 * a shelf, and keeping a list under "compra" that nothing ever rings is a thing people do.
 * Nor is having no action: a moment that passes quietly is still the person's choice.
 */
sealed interface ValidationError {
    data object TextBlank : ValidationError
    data object TextTooLong : ValidationError
    data class BadTrigger(val index: Int, val problem: TriggerProblem) : ValidationError

    /**
     * The calendar in "Vuelve" is nonsense in itself — a series told to stop before it starts,
     * a gap of nothing. No index: there is one recurrence, and it is not one of the rules.
     */
    data class BadRecurrence(val problem: TriggerProblem) : ValidationError
}

/** How far apart a recurrence may be asked to repeat, and how many times it may be asked to. */
const val MIN_EVERY = 1
const val MAX_EVERY = 99
const val MIN_TIMES = 1
const val MAX_TIMES = 999

enum class TriggerProblem {
    DAYS_EMPTY,
    COUNTDOWN_OUT_OF_RANGE,
    RADIUS_OUT_OF_RANGE,
    COORDINATES_INVALID,
    LABEL_TOO_LONG,
    TIMES_OUT_OF_RANGE,
    WINDOW_EMPTY,
    EVERY_OUT_OF_RANGE,
    ENDS_BEFORE_START,
}

/**
 * What is worth a word but not a wall: the reminder still saves.
 *
 * Nothing here blocks anything. A person is allowed to write a reminder that cannot ring — it
 * may be half-written, or on its way somewhere — and the app's job is to say so, not to argue.
 */
sealed interface ValidationWarning {
    /** A one-shot moment already behind us. */
    data class InPast(val index: Int) : ValidationWarning

    /**
     * The rule can never ring: its trigger's moments and its own conditions never coincide.
     * "Todos los lunes a las 9:00, y sólo si es entre las 18:00 y las 22:00."
     */
    data class NeverFires(val index: Int) : ValidationWarning

    /**
     * Two places on one rule that cannot both be true of the same phone: circles that do not
     * touch, or the same circle asked for and ruled out. "Al llegar a casa, y sólo si estoy en
     * la oficina."
     */
    data class PlacesConflict(val index: Int) : ValidationWarning

    /**
     * Under [RuleMatch.ALL] one rule that can never happen takes every other rule down with it:
     * the set never completes, so the reminder never rings at all.
     */
    data class NeverCompletes(val index: Int) : ValidationWarning

    /**
     * A place rule and a clock rule, both bare, under [RuleMatch.ALL]. Legal and probably not
     * what was meant: "todos" rings with the *last* of them to happen, so this asks for a
     * reminder that waits for both in any order and at any distance apart. Somebody writing
     * "al llegar a casa" and "a las 21:00" together usually means one conditioned rule —
     * "al llegar a casa, y sólo si es por la tarde" — which is what a condition is for.
     */
    data class BetterAsCondition(val placeIndex: Int, val clockIndex: Int) : ValidationWarning

    /**
     * Under [RuleMatch.TOGETHER], two rules that are each true at one instant. Instants do not
     * coincide, so nothing ever rings. "A las nueve Y a las diez" is the shape of it, and so is
     * "al llegar a casa Y a las nueve" — arriving is a moment too.
     */
    data class MomentsCannotCoincide(val index: Int) : ValidationWarning
}

fun validate(
    text: String,
    rules: List<TriggerRule>,
    recurrence: Recurrence = Recurrence.None,
): List<ValidationError> {
    val errors = ArrayList<ValidationError>()
    if (text.isBlank()) errors += ValidationError.TextBlank
    if (text.length > MAX_TEXT_LENGTH) errors += ValidationError.TextTooLong
    rules.forEachIndexed { index, rule ->
        problemOf(rule.trigger)?.let { errors += ValidationError.BadTrigger(index, it) }
        rule.conditions.forEach { condition ->
            problemOf(condition)?.let { errors += ValidationError.BadTrigger(index, it) }
        }
    }
    problemOf(recurrence)?.let { errors += ValidationError.BadRecurrence(it) }
    return errors
}

/**
 * What is worth saying about the calendar in "Vuelve", and is not worth blocking a save over.
 *
 * The same two things worth saying about a rule, one card up. [ValidationWarning] is indexed by
 * rule and a recurrence has no index, so this is asked on its own rather than bent into that
 * list — and it is asked at all because the fences moved here with the calendar, and a fence
 * nothing can ever clear is exactly as silent here as it was on a rule.
 */
enum class RecurrenceWarning {
    /** The series has run out: an ending already behind us, or a count already spent. */
    OVER,

    /** It still names dates, but never one its own fences allow. */
    NEVER_FIRES,
}

fun recurrenceWarning(
    recurrence: Recurrence,
    now: Instant,
    zone: ZoneId,
    shape: DayShape = DayShape.DEFAULT,
): RecurrenceWarning? {
    val calendar = recurrence as? Recurrence.Calendar ?: return null
    if (calendar.nextDateMoment("", now, zone, shape) == null) return RecurrenceWarning.OVER
    // The same search the scheduler does, so what it cannot find, nothing will.
    return if (calendar.nextMoment("", now, zone, shape) == null) RecurrenceWarning.NEVER_FIRES else null
}

/**
 * A recurrence that is nonsense in itself. Only a calendar can be: a span is two numbers the
 * stepper cannot take out of range, and the shapes nothing writes any more were checked when
 * they were written.
 */
fun problemOf(recurrence: Recurrence): TriggerProblem? {
    // The stepper cannot write a span below one; a vault, a preset or a hand-edited store
    // can, and "cada 0 horas" is a moment that is always already behind — armed, rung, rung
    // again the instant it is dealt with.
    if (recurrence is Recurrence.After && recurrence.amount !in MIN_RECURRENCE_AMOUNT..MAX_RECURRENCE_AMOUNT) return TriggerProblem.EVERY_OUT_OF_RANGE
    val calendar = recurrence as? Recurrence.Calendar ?: return null
    return problemOf(calendar.repeat) ?: calendar.conditions.firstNotNullOfOrNull { problemOf(it) }
}

fun problemOf(condition: Condition): TriggerProblem? = when (condition) {
    // Empty is every day here on purpose: see [Condition.OnDays].
    is Condition.OnDays -> null
    // A window that starts where it ends is not a window; one that crosses midnight is.
    is Condition.TimeWindow -> TriggerProblem.WINDOW_EMPTY.takeIf { condition.from == condition.to }
    // Both days count, so a range of one day is a range; one that ends before it starts is not.
    is Condition.DateRange -> TriggerProblem.ENDS_BEFORE_START.takeIf { condition.to < condition.from }
    is Condition.AtPlace -> when {
        condition.lat !in -90.0..90.0 || condition.lng !in -180.0..180.0 -> TriggerProblem.COORDINATES_INVALID
        condition.radiusM !in MIN_RADIUS_M..MAX_RADIUS_M -> TriggerProblem.RADIUS_OUT_OF_RANGE
        condition.label.length > MAX_LABEL_LENGTH -> TriggerProblem.LABEL_TOO_LONG
        else -> null
    }
}

fun problemOf(trigger: Trigger): TriggerProblem? = when (trigger) {
    is Trigger.AtDateTime, is Trigger.OnDate, is Trigger.DayRandom -> null
    is Trigger.Repeat -> {
        val ends = trigger.ends
        when {
            trigger.every !in MIN_EVERY..MAX_EVERY -> TriggerProblem.EVERY_OUT_OF_RANGE
            ends is RepeatEnd.After && ends.times !in MIN_TIMES..MAX_TIMES -> TriggerProblem.TIMES_OUT_OF_RANGE
            // A series told to stop before it starts is a series with nothing in it.
            ends is RepeatEnd.On && ends.date < trigger.startsOn -> TriggerProblem.ENDS_BEFORE_START
            else -> null
        }
    }
    is Trigger.AtTime -> TriggerProblem.DAYS_EMPTY.takeIf { trigger.days.isEmpty() }
    // No days is every day here, unlike AtTime: a window is a shape of the day, not a weekly
    // appointment. A window that starts where it ends is not a window; one that wraps is.
    is Trigger.Interval -> TriggerProblem.WINDOW_EMPTY.takeIf { trigger.from == trigger.to }
    // An hour is an hour; no days is every day, the same as a window and unlike AtTime.
    is Trigger.TimeOfDay -> null
    // Here the days ARE the trigger, so none of them is nothing at all — not "every day".
    is Trigger.Weekday -> TriggerProblem.DAYS_EMPTY.takeIf { trigger.days.isEmpty() }
    // Both days count, so a single-day range is fine; one that ends before it starts is not.
    is Trigger.DateRange -> TriggerProblem.ENDS_BEFORE_START.takeIf { trigger.to < trigger.from }
    is Trigger.Countdown -> TriggerProblem.COUNTDOWN_OUT_OF_RANGE.takeIf { trigger.minutes !in MIN_COUNTDOWN_MINUTES..MAX_COUNTDOWN_MINUTES }
    // The day it names is worked out, so the only thing that can be wrong is how far it counts
    // — and a stretch of the day with no width in it, the same as everywhere else.
    is Trigger.RelativeDate -> when {
        (trigger.day as? RelativeDay.In)?.let { it.amount !in RELATIVE_AMOUNT } == true -> TriggerProblem.EVERY_OUT_OF_RANGE
        trigger.window?.let { it.from == it.to } == true -> TriggerProblem.WINDOW_EMPTY
        else -> null
    }
    is Trigger.Location -> when {
        trigger.lat !in -90.0..90.0 || trigger.lng !in -180.0..180.0 -> TriggerProblem.COORDINATES_INVALID
        trigger.radiusM !in MIN_RADIUS_M..MAX_RADIUS_M -> TriggerProblem.RADIUS_OUT_OF_RANGE
        trigger.label.length > MAX_LABEL_LENGTH -> TriggerProblem.LABEL_TOO_LONG
        else -> null
    }
    is Trigger.Random -> when {
        trigger.timesPer !in MIN_RANDOM_TIMES..MAX_RANDOM_TIMES -> TriggerProblem.TIMES_OUT_OF_RANGE
        // A window has to hold a minute per draw, or two draws would land on the same one.
        RandomDraw.windowMinutes(trigger) < trigger.timesPer -> TriggerProblem.WINDOW_EMPTY
        else -> null
    }
}

/**
 * Everything worth saying about a set of rules that saving will not stop. Needs a clock,
 * unlike [validate].
 *
 * [match] is what the rules mean together, and it changes the stakes rather than the findings:
 * under [RuleMatch.ANY] a rule that can never ring is one dud among several and the reminder
 * still works, while under [RuleMatch.ALL] it is the whole reminder, because a set that never
 * completes never rings.
 *
 * [reminderId] is the seed everything drawn is drawn from ([RandomDraw]), so it is the
 * difference between judging this reminder and judging a different one. A random window's
 * moments come from (id, period): asked with the wrong id, "nunca sonará" is a coin flip — it
 * was said of reminders that ring and left unsaid of ones that never will. Empty is the honest
 * answer for a shape with no identity yet (a preset), and the editor mints the draft's id when
 * it opens rather than at the save, so what it says and what gets written agree. Last in the
 * list because every caller predates it.
 */
fun warnings(
    rules: List<TriggerRule>,
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    match: RuleMatch = RuleMatch.ANY,
    shape: DayShape = DayShape.DEFAULT,
    reminderId: String = "",
): List<ValidationWarning> {
    // Judged as they will be saved: a day left to the day is narrowed to what is left of it at
    // the save (settleDays), and a warning read off the unnarrowed window said "ya ha pasado" of
    // a reminder that was going to ring tonight.
    // Both the way a save settles them: a day narrowed to what is left of it, and a relative
    // day resolved to the date it means. Without the second, the walk below re-resolves the
    // shape from wherever it has reached — so it looks like something that comes round again,
    // and a rule that will never fire once written was reported as fine.
    val rules = settleDays(settleRelativeDates(rules, now, zone), now, zone, shape)
    val found = ArrayList<ValidationWarning>()
    val doomed = ArrayList<Int>()
    // Under "a la vez" every rule is judged with the others folded into it as conditions, which
    // is exactly what it will be judged by when the alarm goes off (Reminder.ruleInSet) — and
    // in the same order, which is the one thing about this that has to match.
    val folded = if (match == RuleMatch.TOGETHER && rules.size > 1) {
        rules.indices.map { index ->
            val others = rules.filterIndexed { at, _ -> at != index }
            rules[index].let { it.copy(conditions = it.conditions + others.flatMap { o -> o.conditions } + others.mapNotNull { o -> o.trigger.asState(shape) }) }
        }
    } else {
        rules
    }
    // Counted on the rules as written, for the same reason: whether a rule is only ever true at
    // an instant is a fact about what somebody asked for, not about the moment a set gives it.
    val moments = if (match == RuleMatch.TOGETHER && rules.size > 1) rules.count { it.trigger.isMoment } else 0
    rules.forEachIndexed { index, bare ->
        val rule = folded[index]
        // With the rule's own hour fences, exactly as nextFireOfRule reaches them: a day with no
        // hour opens at the first minute they allow, so "el jueves, sólo de 16 a 17" is judged on
        // the 16:00 it will actually ring at rather than on breakfast time.
        val onItsOwn = nextFireOf(rule.trigger, reminderId, now, zone, defaultTime, shape, rule.windows())
        val oneShot = rule.trigger.isOneShot
        when {
            // Nothing left of the trigger itself: a date that has been and gone.
            onItsOwn == null && oneShot -> {
                found += ValidationWarning.InPast(index)
                doomed += index
            }
            // A shape that comes round on its own and has no moment at all: a random window
            // whose own hour fences leave it no minute to draw (the draw is made inside them).
            onItsOwn == null -> {
                found += ValidationWarning.NeverFires(index)
                doomed += index
            }
            // The trigger still has moments, but never one its own conditions allow. This is
            // nextFireOfRule giving up after MAX_CANDIDATES, which is the same search the
            // scheduler does — so what it cannot find, nothing will.
            onItsOwn != null && nextFireOfRule(rule, reminderId, now, zone, defaultTime, shape) == null -> {
                found += ValidationWarning.NeverFires(index)
                doomed += index
            }
        }
        if (rule.placesConflict()) {
            found += ValidationWarning.PlacesConflict(index)
            doomed += index
        }
        // Two instants asked to be the same instant. Said on every moment in the set, because
        // there is no one of them to blame: it is the pair that is the problem.
        if (moments > 1 && bare.trigger.isMoment) found += ValidationWarning.MomentsCannotCoincide(index)
    }
    if (match == RuleMatch.ALL && rules.size > 1) {
        for (index in doomed.distinct()) found += ValidationWarning.NeverCompletes(index)
    }
    betterAsCondition(rules, match)?.let { found += it }
    return found
}

/**
 * A place rule and a clock rule sitting side by side under "todos", both of them bare.
 *
 * Only when both are bare: somebody who has already put a condition on one of them has met
 * conditions and does not need telling about them. The first pair is enough — one suggestion
 * is advice and five is nagging.
 */
private fun betterAsCondition(rules: List<TriggerRule>, match: RuleMatch): ValidationWarning.BetterAsCondition? {
    // Only under ALL. Under TOGETHER the person has already said "at once", which is the thing
    // this advice was going to suggest.
    if (match != RuleMatch.ALL || rules.size < 2) return null
    val place = rules.indexOfFirst { it.conditions.isEmpty() && it.trigger is Trigger.Location }
    val clock = rules.indexOfFirst {
        it.conditions.isEmpty() && (it.trigger is Trigger.AtDateTime || it.trigger is Trigger.AtTime)
    }
    return if (place >= 0 && clock >= 0) ValidationWarning.BetterAsCondition(place, clock) else null
}

/**
 * Whether the circles this rule talks about can all be true of one phone at one moment.
 *
 * The rule's own trigger counts as one of them when it is a place: firing on arrival means
 * being inside it, and firing on departure means being outside. Then, pair by pair: two places
 * that both have to hold must overlap, and a place that has to hold cannot sit entirely inside
 * one that must not. Two places that both have to be *avoided* never conflict — there is always
 * somewhere else to stand.
 */
fun TriggerRule.placesConflict(): Boolean {
    val circles = ArrayList<Condition.AtPlace>()
    (trigger as? Trigger.Location)?.let {
        circles += Condition.AtPlace(it.lat, it.lng, it.radiusM, it.label, inside = it.presence == Presence.INSIDE)
    }
    conditions.mapNotNullTo(circles) { it.place }
    for (i in circles.indices) {
        for (j in i + 1 until circles.size) {
            if (circles[i].conflictsWith(circles[j])) return true
        }
    }
    return false
}

private fun Condition.AtPlace.conflictsWith(other: Condition.AtPlace): Boolean {
    val apart = distanceMeters(lat, lng, other.lat, other.lng)
    return when {
        // Both have to hold: the circles have to touch somewhere.
        inside && other.inside -> apart > radiusM + other.radiusM
        // One has to hold and the other must not: impossible when the first is swallowed whole.
        inside -> apart + radiusM <= other.radiusM
        other.inside -> apart + other.radiusM <= radiusM
        // Neither has to hold: the rest of the world is available.
        else -> false
    }
}
