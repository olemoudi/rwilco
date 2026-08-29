package dev.rwilco.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A shape written when a repeating time was a *trigger*, read as one written now.
 *
 * Two ways of saying "cada semana" were one too many: a [Trigger.Repeat] rule said it in the
 * "cuándo" card and a [Recurrence] said it in "Vuelve", nothing on either screen said which one
 * a reminder had, and the anchor row in "Vuelve" had a button that reached across and opened the
 * *trigger* sheet. So the repeat moved to where the rest of that answer lives, and everything
 * already on disk is folded on the way in — the repo's own way with a shape that changed
 * (`ReminderCodec` still reads the bare trigger list v0.1.0 wrote).
 *
 * What survives the move: the shape itself, unchanged (it is still a [Trigger.Repeat], now
 * inside [Recurrence.Calendar]), and the fences the rule carried, which become the calendar's
 * own. What does not: a *second* repeating rule on the same shape — two calendars is a thing the
 * app can no longer say, and a rule nothing can edit is worse than one that is gone — and the
 * simultaneity of a repeat ANDed with something else, which becomes the ordinary rest ("suena al
 * llegar, y no vuelve a mirar hasta el lunes"). A [Recurrence.After] set alongside a repeating
 * trigger loses its span to the calendar: the calendar is what was actually producing the rings,
 * and a reminder left with neither is worse than one that rings on its own dates.
 *
 * Idempotent, so it can sit in a read path and run on every load.
 */
data class Folded(
    val rules: List<TriggerRule>,
    val recurrence: Recurrence,
    /**
     * Which rules were folded away — the first is the calendar, the rest are dropped — so
     * whatever indexed the list can be moved with it. Empty when nothing was.
     */
    val removed: List<Int>,
)

fun foldRepeats(rules: List<TriggerRule>, recurrence: Recurrence, writtenOn: LocalDate): Folded {
    // Already answered by a calendar: a store where both exist is hand-edited, and guessing
    // which of the two somebody meant is exactly the thing this is trying to stop.
    if (recurrence is Recurrence.Calendar) return Folded(rules, recurrence, emptyList())
    val removed = rules.indices.filter { rules[it].trigger.isLegacyRepeat }
    val index = removed.firstOrNull() ?: return Folded(rules, recurrence, emptyList())
    val rule = rules[index]
    val repeat = rule.trigger.asCalendarShape(writtenOn) ?: return Folded(rules, recurrence, emptyList())
    return Folded(
        rules = rules.filterIndexed { at, _ -> at !in removed },
        recurrence = Recurrence.Calendar(repeat, rule.conditions),
        removed = removed,
    )
}

/** See [foldRepeats]. The zone only ever gives a legacy weekly the day it was written on. */
fun Reminder.foldRepeats(zone: ZoneId): Reminder {
    val folded = foldRepeats(rules, recurrence, createdAt.dateIn(zone))
    if (folded.removed.isEmpty()) return this
    return copy(
        rules = folded.rules,
        recurrence = folded.recurrence,
        // The indices moved with the rules. An alarm armed for a repeat belongs to no rule any
        // more (the recurrence's moment is the ring, and has no index), and one armed for a
        // later rule is as many places further left as there were repeats before it — one
        // place for every one removed, not one place for all of them, or a reminder with two
        // legacy repeats came out pointing at rules it no longer had.
        armedRule = armedRule?.shiftedBy(folded.removed),
        armedFor = if (armedRule in folded.removed) null else armedFor,
        firedRules = firedRules.mapNotNull { it.shiftedBy(folded.removed) }.toSet(),
    )
}

/** The same, for a shape kept under a name. A preset has no armed moment to move. */
fun Preset.foldRepeats(zone: ZoneId): Preset {
    val folded = foldRepeats(rules, recurrence, createdAt.dateIn(zone))
    if (folded.removed.isEmpty()) return this
    return copy(rules = folded.rules, recurrence = folded.recurrence)
}

/** Null for a rule that went, and one place left for every removed rule that came before it. */
private fun Int.shiftedBy(removed: List<Int>): Int? =
    if (this in removed) null else this - removed.count { it < this }

private fun Instant.dateIn(zone: ZoneId): LocalDate = atZone(zone).toLocalDate()

private val Trigger.isLegacyRepeat: Boolean get() = this is Trigger.Repeat || this is Trigger.AtTime

/**
 * The calendar shape a legacy trigger always was.
 *
 * A [Trigger.AtTime] never carried a start day — it was "every week on these days, for ever" —
 * so the day the shape was written stands in for it. Any past day in the right week would do
 * (with `every = 1` every week is a block, and the days are named outright), but the one it was
 * written on is the one that reads as an answer in the sheet that now shows it.
 */
private fun Trigger.asCalendarShape(writtenOn: LocalDate): Trigger.Repeat? = when (this) {
    is Trigger.Repeat -> this
    is Trigger.AtTime -> Trigger.Repeat(startsOn = writtenOn, unit = RepeatUnit.WEEK, time = time, days = days)
    else -> null
}
