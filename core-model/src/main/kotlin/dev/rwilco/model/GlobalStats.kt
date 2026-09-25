package dev.rwilco.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * What every reminder's history comes to together: the Hechos screen's face (0.150.0), and what
 * the achievements and the encouragement lines are read from.
 *
 * Built from the rounds ([rounds]) rather than from the rows' done stamps, so a routine's and a
 * recurring reminder's hechos count as much as a finished one-off — the old headline counted only
 * rows that were DONE, and a pill taken every morning never once reached it.
 */

/** What a reminder is, as far as the statistics need to know: its words for the screen, its shape, whether it is still going. */
data class StatsSubject(val id: String, val text: String, val shape: RoundShape, val status: Status)

fun Reminder.asSubject(): StatsSubject = StatsSubject(id, text, roundShape, status)

/** One reminder read whole: its rounds and what they come to. */
data class Tally(val subject: StatsSubject, val rounds: List<Round>, val stats: ReminderStats)

/** A reminder and a number about it: a streak, a count. */
data class Standing(val subject: StatsSubject, val count: Int, val best: Int = count)

data class GlobalStats(
    /** Hechos per day over the last [DONE_CHART_DAYS], oldest first, today last. */
    val byDay: List<Int>,
    val today: Int,
    /** The last seven days, today included, and the seven before them. */
    val thisWeek: Int,
    val lastWeek: Int,
    /** Everything the kept history holds. */
    val hechos: Int,
    /** The running streaks worth naming, longest first. */
    val streaks: List<Standing>,
    /** Things that have never once been left undone, most rounds first; [Standing.count] is how many were done. */
    val neverFail: List<Standing>,
    /** Of the hechos in the last [FIRST_TIME_DAYS] days, how many were at the first ask; null under [FIRST_TIME_MIN]. */
    val firstTime: Pair<Int, Int>?,
    /** Every reminder read whole, for the achievements and the encouragement lines. */
    val tallies: List<Tally>,
)

/** A streak is worth naming from three: two in a row is a Tuesday after a Monday. */
const val STREAK_SHOWN = 3

/** How many running streaks the screen names. */
const val STREAKS_SHOWN = 3

/** Five closed rounds before "never failed" means something. */
const val NEVER_FAIL_MIN = 5

const val FIRST_TIME_DAYS = 30L

/** A rate over fewer than ten is a coin tossed a few times. */
const val FIRST_TIME_MIN = 10

/** When a round happened, for putting it on a day: its end, or — not done, or still open — its start. */
val Round.at: Instant get() = endedAt ?: startedAt

/**
 * The numbers over every reminder in [history] (each list in the order written, keyed by id).
 * Histories with no subject — a row gone between the two reads — are left out.
 */
fun globalStats(
    history: Map<String, List<FiringEvent>>,
    subjects: Map<String, StatsSubject>,
    now: Instant,
    zone: ZoneId,
): GlobalStats {
    val tallies = history.mapNotNull { (id, events) ->
        val subject = subjects[id] ?: return@mapNotNull null
        val rounds = rounds(events, subject.shape)
        Tally(subject, rounds, statsOf(rounds, subject.shape, since = events.minOfOrNull { it.at }))
    }
    val today = now.atZone(zone).toLocalDate()
    val hechoDays = tallies.flatMap { tally -> tally.rounds.filter { it.end == RoundEnd.DONE }.map { it.at.atZone(zone).toLocalDate() } }
    val byDay = countByDay(hechoDays, today)
    val counted = tallies.filter { it.subject.shape.keepsCount }
    val recent = now.minus(FIRST_TIME_DAYS, ChronoUnit.DAYS)
    val lately = counted.flatMap { tally -> tally.rounds.filter { it.end == RoundEnd.DONE && it.at >= recent } }
    return GlobalStats(
        byDay = byDay,
        today = hechoDays.count { it == today },
        thisWeek = hechoDays.count { !it.isBefore(today.minusDays(6)) && !it.isAfter(today) },
        lastWeek = hechoDays.count { !it.isBefore(today.minusDays(13)) && it.isBefore(today.minusDays(6)) },
        hechos = hechoDays.size,
        streaks = counted
            .filter { it.subject.status == Status.ACTIVE && it.stats.currentStreak >= STREAK_SHOWN }
            .sortedWith(compareByDescending<Tally> { it.stats.currentStreak }.thenBy { it.subject.text })
            .take(STREAKS_SHOWN)
            .map { Standing(it.subject, it.stats.currentStreak, it.stats.bestStreak) },
        neverFail = counted
            .filter { it.subject.status != Status.DONE && it.neverFailed }
            .sortedWith(compareByDescending<Tally> { it.stats.done }.thenBy { it.subject.text })
            .map { Standing(it.subject, it.stats.done) },
        firstTime = lately.takeIf { it.size >= FIRST_TIME_MIN }?.let { rounds -> rounds.count { it.firstTime } to rounds.size },
        tallies = tallies,
    )
}

/** Whether streaks and misses mean anything for this shape: whatever comes back and asks for an answer. */
val RoundShape.keepsCount: Boolean
    get() = this == RoundShape.REPEATING || this == RoundShape.UNTIL_DONE || this == RoundShape.ROUTINE

/** Whether "done before it rang" means anything: a reminder that rings and is not a routine (always ahead when on time). */
val RoundShape.canBeAhead: Boolean
    get() = this == RoundShape.REPEATING || this == RoundShape.UNTIL_DONE || this == RoundShape.ONE_OFF

/**
 * Whether a first-ask rate is news worth saying: eight in ten or better. Below it the number is a
 * fact the screen keeps to itself — it does not scold.
 */
fun firstTimeIsGood(first: Int, of: Int): Boolean = of > 0 && first * 10 >= of * 8

/** At least [NEVER_FAIL_MIN] rounds closed, and not one of them broke the streak. */
val Tally.neverFailed: Boolean
    get() = rounds.count { it.end == RoundEnd.DONE || it.end == RoundEnd.NOT_DONE } >= NEVER_FAIL_MIN && rounds.none { it.breaksStreak }

/**
 * How many [days] fall on each of the last [span] days, oldest first, today last. Days outside
 * the window are not counted.
 */
fun countByDay(days: List<LocalDate>, today: LocalDate, span: Int = DONE_CHART_DAYS): List<Int> {
    if (span <= 0) return emptyList()
    val first = today.minusDays(span - 1L)
    val counts = IntArray(span)
    for (day in days) {
        if (day.isBefore(first) || day.isAfter(today)) continue
        counts[ChronoUnit.DAYS.between(first, day).toInt()]++
    }
    return counts.toList()
}
