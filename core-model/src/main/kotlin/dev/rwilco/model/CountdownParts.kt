package dev.rwilco.model

import java.time.Duration
import java.time.Instant

/** The pieces of "in 2 h 14 min" / "5 min ago"; formatting is the app's job (it has the locale). */
data class CountdownParts(
    val days: Long,
    val hours: Long,
    val minutes: Long,
    val seconds: Long,
    val overdue: Boolean,
) {
    val totalMinutes: Long get() = days * 24 * 60 + hours * 60 + minutes
    val underAnHour: Boolean get() = days == 0L && hours == 0L
    /** Behind us by less than a minute: "just now", never "0 min ago". */
    val justNow: Boolean get() = overdue && totalMinutes == 0L
}

fun partsBetween(now: Instant, at: Instant): CountdownParts {
    val duration = Duration.between(now, at)
    val overdue = duration.isNegative
    val seconds = duration.abs().seconds
    return CountdownParts(
        days = seconds / 86_400,
        hours = seconds % 86_400 / 3_600,
        minutes = seconds % 3_600 / 60,
        seconds = seconds % 60,
        overdue = overdue,
    )
}
