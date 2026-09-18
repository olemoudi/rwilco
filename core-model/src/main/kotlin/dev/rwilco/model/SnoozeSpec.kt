package dev.rwilco.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * A snooze of the person's own (0.138.0): a length, or a day and a moment of it.
 *
 * The app's seven ([Snooze]) are the answers most people give an alarm, and one length among them
 * was already somebody's own. But "esta noche", "mañana por la tarde" and "el finde por la noche"
 * are answers too, and which of them a person actually gives is theirs to say — so these are
 * built in Settings, sit on the alert beside the app's own ([snoozeBoard]) and are hidden, counted
 * and chosen for the notification exactly as those are.
 *
 * **It travels as a key that says what it is** ([key], [snoozeSpecOf]): `after:45`,
 * `on:tomorrow:evening`, `on:mon:08:00`. Not an index into the settings and not an id, because
 * the key outlives what it was made from — it sits in a notification's button for as long as the
 * card is in the shade, and the snooze it names may have been deleted by then. A button that says
 * "45 min" has to put off 45 minutes whatever Settings says now, and a key that describes itself
 * is the only kind that can. A key nothing can read is not an offer (null), never an exception:
 * the settings are decoded all at once, and these live in them.
 */
sealed interface SnoozeSpec {
    /**
     * "Dentro de 45 minutos", "dentro de 3 días". Whole days are days on the wall — the hour is
     * kept across a clock change, as [Snooze.TOMORROW] keeps it — and what is left over is time
     * on the clock.
     */
    data class After(val minutes: Int) : SnoozeSpec

    /** "Esta noche", "mañana por la tarde", "el finde por la noche", "el lunes a las 8". */
    data class On(val day: SnoozeDay, val hour: SnoozeHour) : SnoozeSpec
}

/** The day a snooze names. Never a date: an offer is for every alert there will be, not for one. */
sealed interface SnoozeDay {
    /** The only one that can run out: "esta tarde" asked at nine at night is not an answer. */
    data object Today : SnoozeDay
    data object Tomorrow : SnoozeDay

    /** The weekend as this person has drawn it ([DayShape.inWeekend]), not Saturday. */
    data object Weekend : SnoozeDay

    /** The next one **after today**: said on a Thursday, "el jueves" is next week's — today's is [Today]. */
    data class Weekday(val day: DayOfWeek) : SnoozeDay
}

enum class SnoozePart { MORNING, AFTERNOON, EVENING }

/** The moment of the day: one of the three that follow "Tu día" ([DayParts]), or an hour of its own. */
sealed interface SnoozeHour {
    data class Part(val part: SnoozePart) : SnoozeHour
    data class At(val time: LocalTime) : SnoozeHour
}

fun SnoozeHour.timeIn(parts: DayParts): LocalTime = when (this) {
    is SnoozeHour.At -> time
    is SnoozeHour.Part -> when (part) {
        SnoozePart.MORNING -> parts.morning
        SnoozePart.AFTERNOON -> parts.afternoon
        SnoozePart.EVENING -> parts.evening
    }
}

val SnoozeSpec.key: String
    get() = when (this) {
        is SnoozeSpec.After -> "$AFTER:$minutes"
        is SnoozeSpec.On -> "$ON:${day.word}:${hour.word}"
    }

/** [key] read back, or null for anything that is not one — including a length outside [SnoozeLimits.AFTER_MINUTES]. */
fun snoozeSpecOf(key: String): SnoozeSpec? {
    // Three at most, so an hour keeps its own colon: "on:mon:08:00" is "on", "mon", "08:00".
    val words = key.split(":", limit = 3)
    return when {
        words[0] == AFTER && words.size == 2 ->
            words[1].toIntOrNull()?.takeIf { it in SnoozeLimits.AFTER_MINUTES }?.let(SnoozeSpec::After)
        words[0] == ON && words.size == 3 -> {
            val day = dayCalled(words[1]) ?: return null
            val hour = hourCalled(words[2]) ?: return null
            SnoozeSpec.On(day, hour)
        }
        else -> null
    }
}

private const val AFTER = "after"
private const val ON = "on"
private const val TODAY = "today"
private const val TOMORROW = "tomorrow"
private const val WEEKEND = "weekend"

/** Frozen, like every other word on the wire: three letters of the English name. */
private val DAY_WORDS: Map<DayOfWeek, String> = DayOfWeek.entries.associateWith { it.name.take(3).lowercase(Locale.ROOT) }

private val SnoozeDay.word: String
    get() = when (this) {
        SnoozeDay.Today -> TODAY
        SnoozeDay.Tomorrow -> TOMORROW
        SnoozeDay.Weekend -> WEEKEND
        is SnoozeDay.Weekday -> DAY_WORDS.getValue(day)
    }

private fun dayCalled(word: String): SnoozeDay? = when (word) {
    TODAY -> SnoozeDay.Today
    TOMORROW -> SnoozeDay.Tomorrow
    WEEKEND -> SnoozeDay.Weekend
    else -> DAY_WORDS.entries.firstOrNull { it.value == word }?.let { SnoozeDay.Weekday(it.key) }
}

private val SnoozeHour.word: String
    get() = when (this) {
        is SnoozeHour.Part -> part.name.lowercase(Locale.ROOT)
        is SnoozeHour.At -> String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)
    }

private val HOUR = Regex("""(\d{2}):(\d{2})""")

private fun hourCalled(word: String): SnoozeHour? {
    SnoozePart.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == word }?.let { return SnoozeHour.Part(it) }
    val (hour, minute) = HOUR.matchEntire(word)?.destructured ?: return null
    if (hour.toInt() > 23 || minute.toInt() > 59) return null
    return SnoozeHour.At(LocalTime.of(hour.toInt(), minute.toInt()))
}

/**
 * When it comes back, or **null when it cannot be an answer now**: a part of today that has gone.
 * Everything else is always ahead — tomorrow, a day of the week (strictly after today), a length.
 *
 * The weekend's is the first such hour **inside** the weekend as this person has drawn it, and
 * still ahead: with a weekend that starts on Friday at 20:30, "el finde por la noche" is
 * Saturday's eight o'clock — Friday's is half an hour short of being the weekend — and asked on a
 * Saturday morning, "el finde por la mañana" is Sunday's. A weekend with no such hour in it at all
 * (one that is only a Saturday morning has no evening) gets the first one after it starts, which
 * is the nearest thing to what was said and better than an offer that never answers.
 */
fun SnoozeSpec.until(now: Instant, zone: ZoneId, terms: SnoozeTerms): Instant? {
    val here = now.atZone(zone)
    return when (this) {
        is SnoozeSpec.After ->
            here.plusDays((minutes / MINUTES_A_DAY).toLong()).plusMinutes((minutes % MINUTES_A_DAY).toLong()).toInstant()
        is SnoozeSpec.On -> {
            val time = hour.timeIn(terms.parts)
            val today = here.toLocalDate()
            fun on(date: LocalDate): Instant = date.atTime(time).atZone(zone).toInstant()
            when (val day = day) {
                SnoozeDay.Today -> on(today).takeIf { it > now }
                SnoozeDay.Tomorrow -> on(today.plusDays(1))
                is SnoozeDay.Weekday -> on(today.with(TemporalAdjusters.next(day.day)))
                SnoozeDay.Weekend -> {
                    // Today and the seven days after it: every hour of a week, once.
                    val inside = (0L..7L).map(today::plusDays)
                        .firstOrNull { date -> terms.shape.inWeekend(date.atTime(time)) && on(date) > now }
                    inside?.let(::on) ?: run {
                        val starts = Snooze.WEEKEND.until(now, zone, terms)
                        val firstDay = starts.atZone(zone).toLocalDate()
                        on(firstDay).takeIf { it >= starts } ?: on(firstDay.plusDays(1))
                    }
                }
            }
        }
    }
}

internal const val MINUTES_A_DAY = 24 * 60
