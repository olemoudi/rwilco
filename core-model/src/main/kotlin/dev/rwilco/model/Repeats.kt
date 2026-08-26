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

/** A cap, not a limit anybody should reach: nothing here skips, so one block is one step. */
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
    val dates = generateSequence(firstBlock) { it + 1 }
        .take(MAX_BLOCKS)
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
        // The same for the twenty-ninth of February, which otherwise rings once in four years.
        RepeatUnit.YEAR -> listOf(startsOn.plusYears(step))
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
 * The moment this repeat rings on [date]: its own hour, or one drawn from that day's waking
 * hours. The draw is by (reminder, day), so it holds still while the day does.
 */
fun Trigger.Repeat.momentOn(date: LocalDate, reminderId: String, zone: ZoneId, shape: DayShape): Instant {
    val hour = time
    return if (hour != null) {
        date.atTime(hour).atZone(zone).toInstant()
    } else {
        RandomDraw.inDay(reminderId, date, shape.awakeOn(date), zone)
    }
}

/**
 * How a repeat reads when it is not a plain "every week": whether it says anything beyond its
 * unit. Used by the card and the editor to decide how much to spell out.
 */
val Trigger.Repeat.isPlain: Boolean
    get() = every == 1 && ends == RepeatEnd.Never
