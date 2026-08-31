package dev.rwilco.model

import java.time.LocalTime

/**
 * When in the day something happens: an hour somebody picked, a stretch they named, or whatever
 * hours they happen to be up for.
 *
 * The same three answers the date tile and the calendar both ask for, pulled out of the screens
 * so one can be read off the other. It is not stored anywhere — every trigger already carries
 * its own answer in its own fields, and a fourth copy of "14:00 to 16:00" would be a fourth
 * place for them to disagree.
 */
sealed interface DayTiming {
    /** An hour somebody typed. Nothing else may argue with it. */
    data class At(val time: LocalTime) : DayTiming

    /** A stretch they named: "a la hora de comer". It opens at [DayWindow.from]. */
    data class In(val window: DayWindow) : DayTiming

    /** None of your business: the hours this person is up on the day in question. */
    data object Whenever : DayTiming
}

/**
 * The answer this trigger gives to "when in the day", or null when it has none to give.
 *
 * A countdown names a length rather than a time of day, a place rings at whatever hour somebody
 * walks through the door, a random window is about not knowing, and a date range says outright
 * that it is not being asked. [Trigger.OnDate] is the same kind of silence: its hour is the
 * setting's, not one anybody chose, and carrying it forward would put a number somebody never
 * typed into a second reminder.
 */
fun Trigger.dayTiming(): DayTiming? = when (this) {
    is Trigger.AtDateTime -> DayTiming.At(at.toLocalTime())
    is Trigger.AtTime -> DayTiming.At(time)
    is Trigger.TimeOfDay -> DayTiming.At(time)
    is Trigger.Interval -> DayTiming.In(DayWindow(from, to))
    is Trigger.DayRandom -> window?.let { DayTiming.In(it) } ?: DayTiming.Whenever
    is Trigger.Repeat -> time?.let { DayTiming.At(it) } ?: window?.let { DayTiming.In(it) } ?: DayTiming.Whenever
    // The same three answers a date gives, which is what it is: a day, and when in it.
    is Trigger.RelativeDate -> time?.let { DayTiming.At(it) } ?: window?.let { DayTiming.In(it) } ?: DayTiming.Whenever
    // The days and no hour: the same silence a day with no hour keeps, and the same answer.
    is Trigger.Weekday -> DayTiming.Whenever
    is Trigger.OnDate, is Trigger.Countdown, is Trigger.Location, is Trigger.Random, is Trigger.DateRange -> null
}

/**
 * What the rules already say about the time of day, for a calendar about to be written after
 * them — the first rule that says anything at all.
 *
 * Somebody who has just answered "when in the day" once should not have to answer it again three
 * rows down: "el 26 a las 20:00, y vuelve cada mes" is one sentence, and a calendar that opened
 * on 09:00 made them re-type the only part of it they had already said. The first rule and not
 * some vote between them, because a reminder with two clocks in it has no single answer and the
 * one written first is the one the person was thinking of.
 *
 * Only ever a *starting point*: it seeds a calendar being created, never one that already exists
 * — an answer somebody has given is not something a trigger may reach back and change.
 */
fun dayTimingOf(rules: List<TriggerRule>): DayTiming? = rules.firstNotNullOfOrNull { it.trigger.dayTiming() }
