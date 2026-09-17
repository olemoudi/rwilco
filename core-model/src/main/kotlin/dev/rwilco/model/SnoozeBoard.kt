package dev.rwilco.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Which snooze offers are on the alert, and which wait behind "a otro momento" (0.137.0).
 *
 * The alert offered every answer there is, every time — seven lengths, the places, the calendar:
 * up to ten held buttons on the one screen that gets answered half awake, whether or not anybody
 * had ever pressed "la semana que viene". What is [shown] is the person's to say now
 * ([AppSettings.hiddenSnoozes]); the rest is [more], one button away and **ordered by how often
 * it is actually used** ([AppSettings.snoozeUses]), so the answer somebody gives once a month is
 * at the top of the list it is looked for in. Nothing is lost by hiding: every offer is still an
 * answer, only a door further.
 *
 * With nothing hidden the board is exactly what the alert was, which is what an update has to
 * be. The place answers are hidden or shown as one ([SNOOZE_PLACES]): they are the phone's offers
 * rather than the person's, and come and go with where the phone is.
 */
data class SnoozeBoard(val shown: List<Snooze>, val more: List<Snooze>, val placesShown: Boolean)

/** The key the place answers ("al llegar a casa", "al salir de aquí") are hidden under, as one. */
const val SNOOZE_PLACES = "places"

fun snoozeBoard(settings: AppSettings): SnoozeBoard {
    val (hidden, shown) = Snooze.entries.partition { it.name in settings.hiddenSnoozes }
    return SnoozeBoard(
        shown = shown,
        // Stable, so the ones never used keep the order they always had among themselves.
        more = hidden.sortedByDescending { settings.snoozeUses[it.name] ?: 0 },
        placesShown = SNOOZE_PLACES !in settings.hiddenSnoozes,
    )
}

/** Whether [key] names something the alert can show or hide: a snooze, or the places. */
private fun isSnoozeKey(key: String): Boolean = key == SNOOZE_PLACES || Snooze.entries.any { it.name == key }

/** One use more of the offer called [key]; anything that is not an offer ("a date", "a week") is not counted. */
fun AppSettings.withSnoozeUsed(key: String): AppSettings =
    if (Snooze.entries.none { it.name == key }) this
    else copy(snoozeUses = snoozeUses + (key to (snoozeUses[key] ?: 0) + 1))

/** The switch in Settings: [key] on the alert, or behind the other door. */
fun AppSettings.withSnoozeShown(key: String, shown: Boolean): AppSettings = when {
    !isSnoozeKey(key) -> this
    shown -> copy(hiddenSnoozes = hiddenSnoozes - key)
    else -> copy(hiddenSnoozes = hiddenSnoozes + key)
}

/**
 * What a snooze's moment is worked out from besides the clock: the person's own weekend, the
 * hour their day starts, how long their own length is. One value to hand a screen, instead of
 * four settings threaded through every composable that says when an offer would come back.
 */
data class SnoozeTerms(val weekendDay: DayOfWeek, val weekendTime: LocalTime, val dayStart: LocalTime, val customMinutes: Int)

val AppSettings.snoozeTerms: SnoozeTerms get() = SnoozeTerms(weekendDay, weekendTime, dayStart, snoozeCustomMinutes)

fun Snooze.until(now: Instant, zone: ZoneId, terms: SnoozeTerms): Instant =
    until(now, zone, terms.weekendDay, terms.weekendTime, terms.dayStart, terms.customMinutes)
