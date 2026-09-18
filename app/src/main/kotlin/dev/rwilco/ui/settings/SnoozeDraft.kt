package dev.rwilco.ui.settings

import dev.rwilco.model.SnoozeDay
import dev.rwilco.model.SnoozeHour
import dev.rwilco.model.SnoozePart
import dev.rwilco.model.SnoozeSpec
import dev.rwilco.model.key
import dev.rwilco.model.snoozeSpecOf
import java.time.LocalTime

/**
 * What a length is counted in while it is being built; what is kept is minutes. Each has the
 * range a stepper can reasonably cover and the number it opens on — and every end of every range
 * is a length the model takes ([dev.rwilco.model.SnoozeLimits.AFTER_MINUTES]).
 */
enum class SnoozeUnit(val minutes: Int, val amounts: IntRange, val step: Int, val start: Int) {
    MINUTES(1, 5..180, 5, 45),
    HOURS(60, 1..72, 1, 3),
    DAYS(24 * 60, 1..30, 1, 3),
}

/**
 * A snooze of the person's own while it is being built: the sheet behind "Añadir un posponer".
 * Pure, so that what the sheet would add has tests and the sheet itself only draws.
 *
 * Both halves are kept whichever is showing ([length]), so looking at the other tab and coming
 * back loses nothing. It opens on "esta noche": of everything the app's own seven do not say, it
 * is the answer people give most.
 */
data class SnoozeDraft(
    /** "Dentro de…" rather than "Un día…". */
    val length: Boolean = false,
    val amount: Int = SnoozeUnit.MINUTES.start,
    val unit: SnoozeUnit = SnoozeUnit.MINUTES,
    val day: SnoozeDay = SnoozeDay.Today,
    /** One of the three parts of the day, or null for [time]. */
    val part: SnoozePart? = SnoozePart.EVENING,
    val time: LocalTime = LocalTime.of(8, 0),
) {
    val spec: SnoozeSpec
        get() = if (length) SnoozeSpec.After(amount * unit.minutes) else moment

    private val moment: SnoozeSpec.On
        get() = SnoozeSpec.On(day, part?.let(SnoozeHour::Part) ?: SnoozeHour.At(time))

    fun canStep(by: Int): Boolean = amount + by * unit.step in unit.amounts

    fun stepped(by: Int): SnoozeDraft = if (canStep(by)) copy(amount = amount + by * unit.step) else this

    /** Another unit starts from its own number: forty-five minutes is not forty-five days. */
    fun counted(inUnit: SnoozeUnit): SnoozeDraft = if (inUnit == unit) this else copy(unit = inUnit, amount = inUnit.start)

    /** What survives the sheet being put away: plain values, the day and hour as the key that already says them. */
    fun saved(): List<Any> = listOf(length, amount, unit.name, moment.key, time.toSecondOfDay())
}

/** [SnoozeDraft.saved] read back; anything else is a fresh draft. */
fun snoozeDraftOf(saved: List<Any?>): SnoozeDraft {
    if (saved.size != 5) return SnoozeDraft()
    val moment = (saved[3] as? String)?.let(::snoozeSpecOf) as? SnoozeSpec.On ?: return SnoozeDraft()
    return SnoozeDraft(
        length = saved[0] as? Boolean ?: return SnoozeDraft(),
        amount = saved[1] as? Int ?: return SnoozeDraft(),
        unit = SnoozeUnit.entries.firstOrNull { it.name == saved[2] } ?: return SnoozeDraft(),
        day = moment.day,
        part = (moment.hour as? SnoozeHour.Part)?.part,
        time = (saved[4] as? Int)?.let { LocalTime.ofSecondOfDay(it.toLong()) } ?: return SnoozeDraft(),
    )
}
