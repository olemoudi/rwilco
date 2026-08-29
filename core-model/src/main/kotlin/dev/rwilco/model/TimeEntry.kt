package dev.rwilco.model

import java.time.LocalTime

/** The most digits a typed time can have: two for the hour, two for the minute. */
const val MAX_TIME_DIGITS = 4

/**
 * A time typed as bare digits, the way it is said: "7" is seven o'clock, "930" is half past
 * nine, "1730" is half past five. Null when the digits make no time at all ("2460").
 *
 * On a 12-hour phone [afternoon] says which half of the day a one-to-twelve hour belongs to;
 * an hour past twelve typed there is taken at its word, because somebody who typed 1730 meant
 * 17:30 whichever clock the phone shows.
 */
fun parseTypedTime(digits: String, is24h: Boolean, afternoon: Boolean): LocalTime? {
    if (digits.isEmpty() || digits.length > MAX_TIME_DIGITS || digits.any { !it.isDigit() }) return null
    val (hourText, minuteText) = when (digits.length) {
        1, 2 -> digits to "0"
        3 -> digits.substring(0, 1) to digits.substring(1)
        else -> digits.substring(0, 2) to digits.substring(2)
    }
    val minute = minuteText.toInt()
    if (minute > 59) return null
    val typed = hourText.toInt()
    val hour = when {
        is24h || typed > 12 -> typed
        typed == 0 -> return null
        else -> typed % 12 + if (afternoon) 12 else 0
    }
    if (hour > 23) return null
    return LocalTime.of(hour, minute)
}
