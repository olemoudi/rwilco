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
 * A stretch of any day, by the clock: "de dos a cuatro", with no date attached to it yet.
 *
 * What a moment "at some point in the afternoon" is drawn from, and the shape a [SavedWindow]
 * keeps. Two wall times and nothing else — the days it applies to belong to whatever is using
 * it (a calendar names its own days, a window trigger has its own), because "a la hora de comer"
 * is a time of day and not a filing rule.
 */
@Serializable
data class DayWindow(val from: LocalTime, val to: LocalTime)

/**
 * The window laid on a date. An end at or before the start is the next morning's, which is the
 * same rule [awakeOn] uses for a bedtime past midnight and is what "de 22:00 a 01:00" means.
 */
fun DayWindow.on(date: LocalDate): AwakeWindow =
    AwakeWindow(date.atTime(from), if (to > from) date.atTime(to) else date.plusDays(1).atTime(to))

/**
 * A window kept under somebody's own name: "a la hora de comer", "por la tarde", "de noche".
 *
 * Named the way places are ([SavedPlace]) and for the same reason: a stretch of the day you use
 * over and over is worth answering once. Nothing that uses one keeps a reference to it — the
 * two times are copied into the trigger, exactly as a place's pin and radius are — so renaming
 * one, or deleting it, never reaches back into a reminder that was written with it.
 */
@Serializable
data class SavedWindow(val label: String, val from: LocalTime, val to: LocalTime)

val SavedWindow.window: DayWindow get() = DayWindow(from, to)

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

/**
 * The first minute of [window] that every one of [fences] allows, or its start when none does.
 *
 * **A day with no hour is not a lottery.** It used to be: a moment drawn from the waking hours
 * by (reminder, day), so "el jueves, a cualquier hora" was one minute of Thursday and nobody —
 * not the person, not the screen — could say which. That reading only ever survived while the
 * shape depended on nothing else; the moment it sat in a set beside a place, the draw had to
 * become the window's opening or the ring would land while the other half was false. Two
 * readings of one control, and the one you got depended on what else was on the card.
 *
 * So this is the only reading now. A stretch with no hour in it is **true from the moment it
 * opens until the moment it closes**, and the ring is the opening: "el jueves, me da igual la
 * hora" rings when Thursday's waking hours start and goes on being true all day, which is what
 * makes it safe to AND with anything. Chance is still a thing you can ask for — it is
 * [Trigger.Random], the tile that is about nothing else.
 *
 * The fences are the rule's own "y sólo si" hours ([TriggerRule.windows]). A door that opened
 * at eight for a rule saying "sólo de 16 a 17" was a moment the fence rejected and a set that
 * never completed, so the opening is the first minute the fences actually allow. When no minute
 * of the window clears them the plain opening comes back for the caller's walk to reject, which
 * is what a fence naming *other days* ("sólo los lunes") has to do to a daily calendar.
 */
fun openingOf(window: AwakeWindow, fences: List<Condition.TimeWindow> = emptyList()): LocalDateTime {
    if (fences.isEmpty()) return window.from
    var at = window.from
    while (at < window.to) {
        if (fences.all { it.holdsAt(at) }) return at
        at = at.plusMinutes(1)
    }
    return window.from
}
