package dev.rwilco.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * The days a [Trigger.Repeat] rings on, worked out rather than stored.
 *
 * A recurrence is a sequence of *blocks* — days, weeks, months, years — [Trigger.Repeat.every]
 * apart, counted from the block [Trigger.Repeat.startsOn] is in, and each block yields the dates
 * inside it that the rule names. Blocks, not occurrences: "every two weeks on Monday and
 * Thursday" is a fortnightly block with two days in it, and counting occurrences instead would
 * drift the series a week every time it rang twice.
 *
 * Every block yields at least one date and none is ever skipped, which is what makes the count
 * behind [RepeatEnd.After] exact and what stops a "day 31" from silently missing February.
 */

/**
 * A cap on an endless series, not a limit anybody should reach: nothing here skips, so one
 * block is one step, and callers only ever take a handful of dates from wherever they stand.
 */
private const val MAX_BLOCKS = 600

/** Which days a weekly repeat names. Empty means the one its start day falls on. */
fun Trigger.Repeat.weekDays(): Set<DayOfWeek> = days.ifEmpty { setOf(startsOn.dayOfWeek) }

/** Which day of the month a monthly repeat names. Null means the one its start day falls on. */
fun Trigger.Repeat.monthlyRule(): MonthlyOn = monthly ?: MonthlyOn.Day(startsOn.dayOfMonth)

/**
 * Every date this rings on from [from] onwards, in order, ending where [Trigger.Repeat.ends]
 * says. Lazy: a series with no end is an endless sequence, and callers take what they need.
 */
fun Trigger.Repeat.occurrences(from: LocalDate = startsOn): Sequence<LocalDate> {
    if (every < 1) return emptySequence()
    val end = ends
    // "After N times" has to be counted from the first one, so that walk starts at the
    // beginning; the other two can start at the block [from] is in, because nothing before it
    // changes what comes after.
    val firstBlock = if (end is RepeatEnd.After) 0 else blockOf(from)
    val blocks = generateSequence(firstBlock) { it + 1 }
    // "After N times" is bounded by N itself: every block past the first yields a date on or
    // after the start, so take(times) ends the walk within N blocks. The cap is for the two
    // endings that count nothing — and it must not reach a counted series, or a daily one
    // started two years ago ran dry at its six-hundredth day with rings still owed.
    val capped = if (end is RepeatEnd.After) blocks else blocks.take(MAX_BLOCKS)
    val dates = capped
        .flatMap { datesIn(it).asSequence() }
        .filter { !it.isBefore(startsOn) }
    val bounded = when (end) {
        is RepeatEnd.After -> dates.take(end.times.coerceAtLeast(0))
        is RepeatEnd.On -> dates.takeWhile { !it.isAfter(end.date) }
        RepeatEnd.Never -> dates
    }
    return bounded.dropWhile { it.isBefore(from) }
}

/** The block [date] falls in, never past it and never before the first. */
private fun Trigger.Repeat.blockOf(date: LocalDate): Int {
    val span = when (unit) {
        RepeatUnit.DAY -> date.toEpochDay() - startsOn.toEpochDay()
        RepeatUnit.WEEK -> weekStart(date).toEpochDay() - weekStart(startsOn).toEpochDay()
        RepeatUnit.MONTH -> months(startsOn, date)
        RepeatUnit.YEAR -> (date.year - startsOn.year).toLong()
    }
    val perBlock = if (unit == RepeatUnit.WEEK) 7L * every else every.toLong()
    return Math.floorDiv(span, perBlock).coerceAtLeast(0L).toInt()
}

private fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

private fun months(from: LocalDate, to: LocalDate): Long =
    (to.year - from.year) * 12L + (to.monthValue - from.monthValue)

/** The dates one block yields, in order. Never empty. */
private fun Trigger.Repeat.datesIn(block: Int): List<LocalDate> {
    val step = block.toLong() * every
    return when (unit) {
        RepeatUnit.DAY -> listOf(startsOn.plusDays(step))
        RepeatUnit.WEEK -> {
            val monday = weekStart(startsOn).plusWeeks(step)
            weekDays().sortedBy { it.value }.map { monday.plusDays((it.value - 1).toLong()) }
        }
        // plusMonths keeps the day where it can and pulls it back to the end of the month where
        // it cannot, which is also what a "day 31" means in February.
        RepeatUnit.MONTH -> listOf(dayIn(YearMonth.from(startsOn).plusMonths(step), monthlyRule()))
        // A year names a month as well as a day, so it takes the same rule a month does: "el
        // primer miércoles de mayo" is a yearly, and saying it any other way is arithmetic on
        // a date that moves. With nothing set the rule is the day startsOn falls on, which is
        // what plusYears did — including the twenty-ninth of February landing on the
        // twenty-eighth rather than ringing once in four years.
        RepeatUnit.YEAR -> listOf(dayIn(YearMonth.from(startsOn).plusYears(step), monthlyRule()))
    }
}

/** Where a monthly rule lands in one month. Always a real date in it. */
private fun dayIn(month: YearMonth, rule: MonthlyOn): LocalDate = when (rule) {
    is MonthlyOn.Day -> month.atDay(rule.day.coerceIn(1, month.lengthOfMonth()))
    is MonthlyOn.Nth -> if (rule.ordinal < 0) {
        month.atEndOfMonth().with(TemporalAdjusters.previousOrSame(rule.day))
    } else {
        val first = month.atDay(1).with(TemporalAdjusters.nextOrSame(rule.day))
        val nth = first.plusWeeks((rule.ordinal - 1).coerceAtLeast(0).toLong())
        // Only reachable from a hand-edited store: the editor stops at "fourth" and "last".
        if (nth.month == month.month) nth else month.atEndOfMonth().with(TemporalAdjusters.previousOrSame(rule.day))
    }
}

/**
 * The moment this repeat rings on [date]: its own hour, or one drawn from the stretch of the day
 * it was given, or from that day's waking hours. The draw is by (reminder, day), so it holds
 * still while the day does.
 *
 * The three answers narrow, in that order: an hour is exact, a window is "somewhere in here",
 * and the waking hours are "somewhere today". [Trigger.Repeat.window] is only ever read when
 * there is no hour, because an hour somebody typed is not a thing anything else may argue with.
 *
 * [fences] are the hour fences the moment has to clear ("y sólo si es entre las 16 y las 17"),
 * and a draw is made inside them rather than judged against them afterwards — see
 * [RandomDraw.inDay]. An hour somebody typed is left to the walk that asks the fences.
 */
fun Trigger.Repeat.momentOn(
    date: LocalDate,
    reminderId: String,
    zone: ZoneId,
    shape: DayShape,
    fences: List<Condition.TimeWindow> = emptyList(),
): Instant {
    val hour = time
    val span = window
    return when {
        hour != null -> date.atTime(hour).atZone(zone).toInstant()
        span != null -> RandomDraw.inDay(reminderId, date, span.on(date), zone, fences)
        else -> RandomDraw.inDay(reminderId, date, shape.awakeOn(date), zone, fences)
    }
}

/**
 * How a repeat reads when it is not a plain "every week": whether it says anything beyond its
 * unit. Used by the card and the editor to decide how much to spell out.
 */
val Trigger.Repeat.isPlain: Boolean
    get() = every == 1 && ends == RepeatEnd.Never
