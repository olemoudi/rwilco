@file:UseSerializers(InstantSerializer::class, DayOfWeekSerializer::class, LocalTimeSerializer::class)

package dev.rwilco.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * When a reminder comes back after it has been dealt with.
 *
 * The triggers say when it rings the first time; this says whether there is a next time, and
 * when. They are different questions, and the answer to the second is usually anchored on the
 * person rather than the calendar: pills are "six hours after the last one", not "at 06:00,
 * 12:00, 18:00 and 00:00 whatever happened". So most of these count from the moment the
 * reminder was dealt with — which is the only moment that knows anything.
 */
@Serializable
sealed interface Recurrence {

    /** Done is done. The default, and the only one that needs no explaining. */
    @Serializable
    @SerialName("none")
    data object None : Recurrence

    /**
     * The triggers decide again. Only a random window says this now — "tres veces al día" is
     * its own answer to "when does it come back" and the tile that writes it is the only one
     * left that names dates of its own. It still decodes for the reminders written when a
     * repeating time was a trigger too; those are folded into [Calendar] on the way in
     * (`foldRepeats`).
     */
    @Serializable
    @SerialName("by_trigger")
    data object ByTrigger : Recurrence

    /**
     * A calendar: the dates a series names and the hour inside them, with the fences it has to
     * clear.
     *
     * This is what used to be a *trigger* — the "una hora que se repite" tile — and being two
     * things was the whole problem: "cada semana" was a trigger on one screen and a recurrence
     * on another, and nothing said which of the two a reminder had. A repeat is not a way of
     * starting; it is the answer to "¿y vuelve?", so it lives here with the rest of that
     * answer.
     *
     * [repeat] is the shape itself, unchanged and still [Trigger.Repeat]: it is the form every
     * phone already has on disk, its `@SerialName`s are frozen, and a second copy of the same
     * seven fields is a second place for the arithmetic to disagree. [conditions] are the
     * fences the rule it used to be could carry ("y sólo si estoy en casa") — the one thing
     * that would have been lost in the move, and the reason they are here.
     */
    @Serializable
    @SerialName("calendar")
    data class Calendar(
        val repeat: Trigger.Repeat,
        val conditions: List<Condition> = emptyList(),
    ) : Recurrence

    /**
     * A span after [from]. Hours are exact — "cada 6 h" means six hours — while days, weeks,
     * months and years land on the day's start hour (a setting), because "al día siguiente" is
     * a morning, not a time of night.
     */
    @Serializable
    @SerialName("after")
    data class After(
        val amount: Int,
        val unit: RecurrenceUnit,
        val from: RecurrenceFrom = RecurrenceFrom.DEALT,
    ) : Recurrence

    /**
     * The [ordinal]th [day] of each month; [LAST_ORDINAL] for the last one.
     *
     * Nothing writes one of these any more — it is a [Calendar] of a month with a
     * [MonthlyOn.Nth] in it, said twice — but it stays because it is what somebody's phone and
     * somebody's saved presets are full of, and it still means exactly what it meant. Opening
     * one in "Vuelve" rewrites it as the calendar it always was.
     */
    @Serializable
    @SerialName("monthly_weekday")
    data class MonthlyWeekday(val ordinal: Int, val day: DayOfWeek) : Recurrence
}

enum class RecurrenceUnit { HOURS, DAYS, WEEKS, MONTHS, YEARS }

/**
 * Which moment a span is counted from — the one thing about "cada 6 h" that two people mean
 * differently, and that the app used to decide for them.
 *
 * [DEALT] is "six hours between doses": take it late and the next one moves with you. [RANG] is
 * "six hours apart, on the hour": answer it late and the rhythm does not drift, which is what
 * anybody who set 08:00, 14:00, 20:00 meant and what the app could not say until now. Both are
 * right for somebody, which is exactly why it is asked rather than assumed.
 *
 * A consequence worth knowing: under [RANG] a reminder that is ignored still has an anchor that
 * moves, so it comes back on its rhythm rather than waiting to be acknowledged. Under [DEALT]
 * the anchor only moves when somebody deals with it, which is what stops it ringing for ever.
 */
enum class RecurrenceFrom { DEALT, RANG }

/** Whether the span is counted from the firing rather than from dealing with it. */
val Recurrence.countsFromRinging: Boolean
    get() = this is Recurrence.After && from == RecurrenceFrom.RANG

/**
 * The same span, whatever moment it counts from.
 *
 * The anchor is a separate answer to a separate question — "cada 6 h" and "al sonar" are two
 * halves of one sentence — so a button that says "cada 6 h" stays lit when the anchor changes
 * under it, and picking it again does not undo the anchor.
 */
fun Recurrence.sameSpanAs(other: Recurrence): Boolean =
    if (this is Recurrence.After && other is Recurrence.After) amount == other.amount && unit == other.unit
    else this == other

/** [other]'s span, kept counting from wherever this one was counting from. */
fun Recurrence.withSpanOf(other: Recurrence): Recurrence =
    if (this is Recurrence.After && other is Recurrence.After) other.copy(from = from) else other

/** "The last Sunday of the month" rather than a numbered one. */
const val LAST_ORDINAL = 5

const val MIN_RECURRENCE_AMOUNT = 1
const val MAX_RECURRENCE_AMOUNT = 99

/** Whether the recurrence works out its own moments, rather than handing the job to the triggers. */
val Recurrence.isAnchored: Boolean
    get() = this is Recurrence.After || this is Recurrence.MonthlyWeekday || this is Recurrence.Calendar

/**
 * Whether it names dates of its own rather than counting a span from something that happened.
 *
 * The difference that matters everywhere a moment is worked out: a calendar knows its next date
 * without being told when the last one was dealt with, and a span knows nothing else. It is also
 * the difference between a recurrence that can *end* ([RepeatEnd]) and one that cannot.
 */
val Recurrence.isCalendar: Boolean get() = this is Recurrence.Calendar

/** The fences on the recurrence's own moment; empty for everything that is not a calendar. */
val Recurrence.conditions: List<Condition>
    get() = (this as? Recurrence.Calendar)?.conditions.orEmpty()

/** The same calendar with its fences replaced; anything else is left exactly as it is. */
fun Recurrence.withConditions(conditions: List<Condition>): Recurrence =
    if (this is Recurrence.Calendar) copy(conditions = conditions) else this

/**
 * The calendar behind a recurrence, for the sheet that edits one: what is set if it is already a
 * calendar, the same shape a legacy [Recurrence.MonthlyWeekday] always was, and null otherwise.
 */
fun Recurrence.asRepeat(): Trigger.Repeat? = when (this) {
    is Recurrence.Calendar -> repeat
    is Recurrence.MonthlyWeekday -> Trigger.Repeat(
        startsOn = java.time.LocalDate.EPOCH,
        unit = RepeatUnit.MONTH,
        monthly = MonthlyOn.Nth(if (ordinal >= LAST_ORDINAL) -1 else ordinal, day),
    )
    else -> null
}

/** Whether dealing with a firing leaves anything behind at all. */
val Recurrence.repeats: Boolean get() = this != Recurrence.None

/**
 * The next moment, counted from [anchor] — when the reminder was last dealt with, or when it was
 * written if it never has been.
 *
 * [dayStart] is what "the next day" means to this person (09:00 by default): a span measured in
 * days, weeks or months lands there rather than at whatever hour the last one happened to be
 * dealt with, and never before the span itself is up. Hours are left exact, because somebody
 * who says "every six hours" means six hours.
 */
/**
 * Whether this recurrence measures its span in whole days or more, which is what makes it a
 * thing that lands on a *day* rather than at an instant. See `Reminder.restUntil`.
 */
val Recurrence.countsInDays: Boolean
    get() = when (this) {
        Recurrence.None, Recurrence.ByTrigger -> false
        is Recurrence.After -> unit != RecurrenceUnit.HOURS
        // A calendar names days and nothing shorter, whatever hour it puts inside one.
        is Recurrence.MonthlyWeekday, is Recurrence.Calendar -> true
    }

/**
 * The next moment a *span* produces, counted from [anchor].
 *
 * A calendar is deliberately not one of them and answers null here: its moments come from the
 * dates it names rather than from anything that happened, and working one out needs the reminder
 * they belong to — the hour nobody chose is drawn by (reminder, day). `Reminder.calendarMoment`
 * is the question for a calendar, and every caller here asks it first.
 */
fun nextRecurrence(
    recurrence: Recurrence,
    anchor: Instant,
    zone: ZoneId,
    dayStart: LocalTime,
): Instant? = when (recurrence) {
    Recurrence.None, Recurrence.ByTrigger, is Recurrence.Calendar -> null
    is Recurrence.After -> when (recurrence.unit) {
        RecurrenceUnit.HOURS -> anchor.plusSeconds(recurrence.amount * 3_600L)
        RecurrenceUnit.DAYS -> anchor.atDayStart(zone, dayStart) { it.plusDays(recurrence.amount.toLong()) }
        RecurrenceUnit.WEEKS -> anchor.atDayStart(zone, dayStart) { it.plusWeeks(recurrence.amount.toLong()) }
        RecurrenceUnit.MONTHS -> anchor.atDayStart(zone, dayStart) { it.plusMonths(recurrence.amount.toLong()) }
        // The 29th of February lands on the 28th rather than skipping three years in four,
        // which is what plusYears does and what anybody with that birthday expects.
        RecurrenceUnit.YEARS -> anchor.atDayStart(zone, dayStart) { it.plusYears(recurrence.amount.toLong()) }
    }
    is Recurrence.MonthlyWeekday -> nextMonthlyWeekday(recurrence, anchor, zone, dayStart)
}

/**
 * Move the anchor's date by [step], then take the day's start hour. If that lands on or before
 * the anchor — dealing with something at 23:00 and asking for "tomorrow at 09:00" cannot mean
 * this morning — the next day is taken instead.
 */
private inline fun Instant.atDayStart(
    zone: ZoneId,
    dayStart: LocalTime,
    step: (java.time.LocalDate) -> java.time.LocalDate,
): Instant {
    val here = atZone(zone)
    var candidate = step(here.toLocalDate()).atTime(dayStart).atZone(zone).toInstant()
    while (candidate <= this) candidate = candidate.atZone(zone).toLocalDate().plusDays(1).atTime(dayStart).atZone(zone).toInstant()
    return candidate
}

/** The first (or second, or last) such weekday in a month, from the month the anchor is in. */
private fun nextMonthlyWeekday(
    recurrence: Recurrence.MonthlyWeekday,
    anchor: Instant,
    zone: ZoneId,
    dayStart: LocalTime,
): Instant {
    val here = anchor.atZone(zone)
    var month = here.toLocalDate().withDayOfMonth(1)
    repeat(MONTHS_TO_TRY) {
        val adjuster = if (recurrence.ordinal >= LAST_ORDINAL) {
            TemporalAdjusters.lastInMonth(recurrence.day)
        } else {
            TemporalAdjusters.dayOfWeekInMonth(recurrence.ordinal, recurrence.day)
        }
        val candidate = month.with(adjuster).atTime(dayStart).atZone(zone).toInstant()
        if (candidate > anchor) return candidate
        month = month.plusMonths(1)
    }
    // Unreachable for any real ordinal, but a loop that cannot end is worse than a late answer.
    return month.with(TemporalAdjusters.firstInMonth(recurrence.day)).atTime(dayStart).atZone(zone).toInstant()
}

private const val MONTHS_TO_TRY = 3

/**
 * A recurrence kept under a name, so the same answer is one tap away next time. [name] empty
 * means the shape speaks for itself ("cada 6 h") — that is what the built-in ones are.
 */
@Serializable
data class RecurrencePreset(
    val id: String,
    val recurrence: Recurrence,
    val name: String = "",
    val uses: Int = 0,
    val lastUsedAt: Instant? = null,
)

/**
 * [preset] written into this list: **in its own place** when it is already there, at the end when
 * it is new.
 *
 * In place is the whole point. The order of the list is the last tie-break the sort below falls
 * back on, and the four built-in recurrences are tied on everything else — no uses, never used —
 * so one rebuilt at the end of the list drops behind the rest and off the row of buttons, which
 * only has room for three. Editing "al día siguiente" to give it a name made it vanish from the
 * card, which reads as losing it rather than renaming it.
 */
fun List<RecurrencePreset>.keeping(preset: RecurrencePreset): List<RecurrencePreset> =
    if (none { it.id == preset.id }) this + preset
    else map { if (it.id == preset.id) preset else it }

/** Most used first, then the most recently used, then the order they were given in. */
fun recurrencePresetsByPopularity(presets: List<RecurrencePreset>): List<RecurrencePreset> =
    presets.sortedWith(compareByDescending<RecurrencePreset> { it.uses }.thenByDescending { it.lastUsedAt ?: Instant.EPOCH })

fun RecurrencePreset.used(now: Instant): RecurrencePreset = copy(uses = uses + 1, lastUsedAt = now)

/**
 * What everybody needs before they have needed anything else: the day after, the week after,
 * the month after, and the six hours that medicine is measured in. Unnamed, because their own
 * shape says what they are.
 */
fun defaultRecurrencePresets(): List<RecurrencePreset> = listOf(
    RecurrencePreset(id = "builtin-day", recurrence = Recurrence.After(1, RecurrenceUnit.DAYS)),
    RecurrencePreset(id = "builtin-6h", recurrence = Recurrence.After(6, RecurrenceUnit.HOURS)),
    RecurrencePreset(id = "builtin-week", recurrence = Recurrence.After(1, RecurrenceUnit.WEEKS)),
    RecurrencePreset(id = "builtin-month", recurrence = Recurrence.After(1, RecurrenceUnit.MONTHS)),
)
