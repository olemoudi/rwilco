@file:UseSerializers(LocalTimeSerializer::class, DayOfWeekSerializer::class)

package dev.rwilco.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The hours somebody is up, which is the only honest answer to "at random during the day".
 *
 * A reminder drawn from the whole twenty-four hours rings at four in the morning, so a random
 * day needs to know when the day starts and stops. Two pairs, not one: a Tuesday and a Saturday
 * are not the same day, and the whole point of the weekend hours is that they are later at both
 * ends. [AppSettings.weekendDay]/[AppSettings.weekendTime] say when the weekend starts and
 * [AppSettings.weekendEndDay]/[AppSettings.weekendEndTime] when it stops, so a Friday evening is
 * already the weekend and a Sunday night is not.
 */
@Serializable
data class AwakeHours(
    val wake: LocalTime = LocalTime.of(8, 0),
    val sleep: LocalTime = LocalTime.of(23, 30),
    val weekendWake: LocalTime = LocalTime.of(10, 0),
    val weekendSleep: LocalTime = LocalTime.of(1, 30),
)

/**
 * Everything the shape of a day needs, pulled out of the settings so the pure functions take a
 * small value and not the whole settings object. [DEFAULT] is what a caller with nothing to say
 * gets: it is only ever the wrong answer for somebody who has changed their hours, and the
 * places that matter (the scheduler, Home, the editor) all pass the real one.
 */
data class DayShape(
    val hours: AwakeHours = AwakeHours(),
    val weekendFrom: DayOfWeek = DayOfWeek.FRIDAY,
    val weekendFromTime: LocalTime = LocalTime.of(20, 30),
    val weekendTo: DayOfWeek = DayOfWeek.SUNDAY,
    val weekendToTime: LocalTime = LocalTime.of(22, 0),
) {
    companion object {
        val DEFAULT = DayShape()
    }
}

val AppSettings.dayShape: DayShape
    get() = DayShape(
        hours = awake,
        weekendFrom = weekendDay,
        weekendFromTime = weekendTime,
        weekendTo = weekendEndDay,
        weekendToTime = weekendEndTime,
    )

/** The stretch of one day somebody is awake for. [to] is past midnight whenever bedtime is. */
data class AwakeWindow(val from: LocalDateTime, val to: LocalDateTime)

/**
 * Whether a moment falls in the weekend, as this person has drawn it.
 *
 * Minutes since Monday midnight, so the span is one comparison and a span that wraps the week
 * (a "weekend" from Sunday to Friday, which is somebody's shift pattern) works the same way.
 * A start equal to the end is a weekend with no width: nothing is in it.
 */
fun DayShape.inWeekend(at: LocalDateTime): Boolean {
    val start = minuteOfWeek(weekendFrom, weekendFromTime)
    val end = minuteOfWeek(weekendTo, weekendToTime)
    val moment = minuteOfWeek(at.dayOfWeek, at.toLocalTime())
    return if (start <= end) moment >= start && moment < end else moment >= start || moment < end
}

private fun minuteOfWeek(day: DayOfWeek, time: LocalTime): Int =
    (day.value - 1) * 24 * 60 + time.hour * 60 + time.minute

/**
 * When this person is up on [date]: from when they get up to when they go to bed.
 *
 * The two ends are asked separately, and that is the point. The weekend starts on Friday
 * evening, so a Friday is a working day that ends at the weekend's bedtime — up at eight,
 * to bed at half one. A Sunday is the mirror of it: the lie-in is the weekend's, the early
 * night is the week's, because the weekend ended at ten. Midday stands for the morning and
 * the last minute of the day for the night; no wake or bedtime anybody sets can fall on the
 * wrong side of either.
 */
fun DayShape.awakeOn(date: LocalDate): AwakeWindow {
    val wake = if (inWeekend(date.atTime(12, 0))) hours.weekendWake else hours.wake
    val sleep = if (inWeekend(date.atTime(23, 59))) hours.weekendSleep else hours.sleep
    val from = date.atTime(wake)
    // Bedtime at or before getting up is the next morning's: that is what "up until half one"
    // means, and a window of no width at all would be a day with no moment in it.
    val to = if (sleep > wake) date.atTime(sleep) else date.plusDays(1).atTime(sleep)
    return AwakeWindow(from, to)
}
