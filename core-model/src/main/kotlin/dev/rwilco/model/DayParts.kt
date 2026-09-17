package dev.rwilco.model

import java.time.LocalTime

/**
 * The hours the parts of a day stand for: "por la mañana", "por la tarde", "por la noche".
 *
 * They are the person's own (0.136.0). The morning always was — it is where the day starts
 * ([AppSettings.dayStart]) — but only the quick chip under "Cuándo" knew it: the words went on
 * meaning nine o'clock whatever Settings said, so "mañana por la mañana" was two different hours
 * depending on whether it was tapped or typed. The evening was eight o'clock in two unconnected
 * places. And the afternoon had no hour at all, so a sentence that named it was refused whole —
 * "mañana por la tarde" gave no chip, not even for "mañana" — which is the commonest way there
 * is of saying when.
 *
 * The defaults are what the words already meant (and five o'clock for the part that meant
 * nothing), so nothing anybody has typed lands anywhere new until they say so. Not ordered
 * against each other on purpose: every reader takes one hour on its own, and somebody who works
 * nights is allowed an evening before their morning.
 */
data class DayParts(
    val morning: LocalTime = DEFAULT_DAY_START,
    val afternoon: LocalTime = DEFAULT_AFTERNOON,
    val evening: LocalTime = DEFAULT_EVENING,
)

/** Five in the afternoon: late enough to be after work for most, early enough to still be "la tarde". */
val DEFAULT_AFTERNOON: LocalTime = LocalTime.of(17, 0)

/** Eight in the evening, which is what "esta noche" has meant since the quick chips arrived. */
val DEFAULT_EVENING: LocalTime = LocalTime.of(20, 0)

/** The three hours as this person keeps them. The morning is where their day starts. */
val AppSettings.dayParts: DayParts get() = DayParts(morning = dayStart, afternoon = afternoon, evening = evening)
