package dev.rwilco.model

import java.time.Duration
import java.time.Instant

/**
 * What a reminder's history comes to: its **rounds** — each time it came up and what became of
 * it — and, out of those, the numbers somebody asks of a thing they do again and again: how many
 * times, how often, how many in a row.
 *
 * The rule for a streak is the owner's, in his words (2026-09-25): **it breaks only by not doing
 * it.** A ring left without a "hecho" — overtaken by the next one, or chased by the net and still
 * unanswered — breaks it; for a routine, going overdue does. Putting it off and then doing it does
 * not: that is counted apart, as "a la primera". A round let pass on purpose neither adds nor
 * breaks, and neither does a deadline that lapsed, because a deadline only lapses when nothing
 * rang ([deadlineOutranked] drops it the moment anything did).
 */

/** How a reminder's rounds are read. */
enum class RoundShape {
    /**
     * It comes back on its own clock — counted from the ring, drawn by its trigger, or a calendar
     * ringing its own dates: the next ring is the next round whether or not the last was
     * answered, so an unanswered ring overtaken by the next one is a miss.
     */
    REPEATING,

    /**
     * It comes back, but from the "hecho": the span counts from the answer, and until there is one
     * the rules go on asking (a place crossed again, the same hour the next day). A second ring is
     * the same round asking again, not the next one — so a miss is only the net's word left
     * unanswered, as for a one-off (review of 0.152.0: "al llegar a casa, vuelve cada día",
     * ignored at six and done at the second arrival, was a miss and a broken streak).
     */
    UNTIL_DONE,

    /** Once: it may ring more than once (a place, crossed twice) and it is still one round. */
    ONE_OFF,

    /** A span counted from the last time: its deadline ringing means it went overdue. */
    ROUTINE,

    /**
     * A contact, something that asks for no answer (no actions), or a to-do with nothing that can
     * ring: its "hechos" are counted and nothing else. A contact is never a debt (Waiting.kt), a
     * reminder that rings silently and asks for nothing cannot have been left unanswered, and a
     * to-do ticked off was never "ahead" of a ring it could not have.
     */
    QUIET,
}

val Reminder.roundShape: RoundShape
    get() = when {
        isContact -> RoundShape.QUIET
        // Before the actions: a routine goes overdue whether or not its deadline makes a sound.
        isRoutine -> RoundShape.ROUTINE
        actions.isEmpty() -> RoundShape.QUIET
        !recurrence.repeats && rules.isEmpty() -> RoundShape.QUIET
        !recurrence.repeats -> RoundShape.ONE_OFF
        roundsByClock -> RoundShape.REPEATING
        else -> RoundShape.UNTIL_DONE
    }

/**
 * Whether the next round comes whatever became of the last: a span from the ring, the trigger's
 * own draws, or a calendar with no rules of its own (its dates are the rings). A calendar with
 * rules is a rest counted from the "hecho" (`restUntil`), like any span from the answer.
 */
private val Reminder.roundsByClock: Boolean
    get() = when (val recurrence = recurrence) {
        Recurrence.ByTrigger -> true
        is Recurrence.After -> recurrence.from == RecurrenceFrom.RANG
        is Recurrence.Calendar, is Recurrence.MonthlyWeekday -> rules.isEmpty()
        else -> false
    }

/** How a round ended. */
enum class RoundEnd {
    DONE,

    /** It rang and got no "hecho": overtaken, or chased by the net and still unanswered, or (a routine) gone overdue and still owed. */
    NOT_DONE,

    /** Let pass on purpose, or its deadline lapsed with nothing having rung. */
    SKIPPED,

    /** Still under way: rang and waiting, or snoozed. Not counted either way yet. */
    OPEN,
}

/**
 * One round. [late] is a routine done after its deadline rang — done, but overdue first, which is
 * what breaks a routine's streak. [chased] is the net having had to say "ICYMI" about it.
 */
data class Round(
    val end: RoundEnd,
    val startedAt: Instant,
    val endedAt: Instant?,
    val rang: Boolean,
    val snoozes: Int,
    val chased: Boolean,
    val late: Boolean,
    /** How many times it rang before the "hecho": more than one is being asked again. */
    val rings: Int = if (rang) 1 else 0,
) {
    val keepsStreak: Boolean get() = end == RoundEnd.DONE && !late
    val breaksStreak: Boolean get() = end == RoundEnd.NOT_DONE || (end == RoundEnd.DONE && late)

    /** Done the first time it was asked: rung at most once, no snooze, not overdue, not chased. */
    val firstTime: Boolean get() = end == RoundEnd.DONE && rings <= 1 && snoozes == 0 && !late && !chased

    /** Done before it ever rang. */
    val ahead: Boolean get() = end == RoundEnd.DONE && !rang
}

/**
 * The rounds of one reminder, oldest first.
 *
 * [events] must be **in the order they were written** (the table's id), not sorted by [FiringEvent.at]:
 * "lo hice el sábado", said on Tuesday after Monday's deadline rang, writes a "hecho" dated
 * Saturday *after* the ring. Sorted by date the ring would fall into the next round and make it
 * overdue; in written order the hecho closes the round it was said in, and a ring dated after the
 * hecho's own moment is taken back — the same thing [Reminder.doneEarlier] does to `lastFiredAt`.
 */
fun rounds(events: List<FiringEvent>, shape: RoundShape): List<Round> {
    val out = ArrayList<Round>()
    var open: OpenRound? = null
    for (event in withoutUndoneResets(events)) {
        when (event.kind) {
            FiringKind.RANG, FiringKind.MISSED -> {
                if (shape == RoundShape.QUIET) continue
                val current = open
                open = when {
                    current == null -> OpenRound(event.at)
                    // A ring nobody answered, overtaken by the next: that round is over, and not
                    // done. After a snooze the ring is the snooze's own, the same round again; a
                    // one-off ringing twice (a place crossed twice) is still one thing to do.
                    shape == RoundShape.REPEATING && current.rings.isNotEmpty() && !current.snoozedSinceRing -> {
                        out += current.close(RoundEnd.NOT_DONE, null)
                        OpenRound(event.at)
                    }
                    else -> current
                }
                open.ring(event.at)
            }
            FiringKind.SNOOZED -> {
                // "Todavía no" answers a routine's question; it is not a snooze.
                if (shape == RoundShape.QUIET || event.detail == LATER_DETAIL) continue
                open = (open ?: OpenRound(event.at)).also { it.snooze(event.at) }
            }
            // Only "it rang and nobody answered" is about the person; a moment that fell in a
            // shut window, a shape that cannot ring, a place slow to be reached are not.
            FiringKind.NET -> if (shape != RoundShape.QUIET && event.detail == NetWord.LET_GO.name) open?.chase(event.at)
            FiringKind.DEALT, FiringKind.RESET -> {
                out += (open ?: OpenRound(event.at)).done(event.at, shape)
                open = null
            }
            FiringKind.SKIPPED, FiringKind.LAPSED -> {
                out += (open ?: OpenRound(event.at)).close(RoundEnd.SKIPPED, event.at)
                open = null
            }
            FiringKind.UNTICKED, FiringKind.ASKED, FiringKind.UNRESET -> Unit
        }
    }
    open?.let { out += it.trailing(shape) }
    return out
}

/**
 * The events without the place resets that were taken back: each UNRESET cancels the latest
 * RESET before it that nothing has cancelled yet, and goes itself.
 */
private fun withoutUndoneResets(events: List<FiringEvent>): List<FiringEvent> {
    if (events.none { it.kind == FiringKind.UNRESET }) return events
    val dropped = HashSet<Int>()
    events.forEachIndexed { index, event ->
        if (event.kind != FiringKind.UNRESET) return@forEachIndexed
        dropped += index
        val reset = (index - 1 downTo 0).firstOrNull { events[it].kind == FiringKind.RESET && it !in dropped }
        if (reset != null) dropped += reset
    }
    return events.filterIndexed { index, _ -> index !in dropped }
}

private class OpenRound(val startedAt: Instant) {
    val rings = ArrayList<Instant>()
    val snoozes = ArrayList<Instant>()
    var chasedAt: Instant? = null
    var snoozedSinceRing = false

    fun ring(at: Instant) {
        rings += at
        snoozedSinceRing = false
    }

    fun snooze(at: Instant) {
        snoozes += at
        snoozedSinceRing = true
    }

    fun chase(at: Instant) {
        if (chasedAt == null) chasedAt = at
        // The net only speaks about a ring nobody answered: whatever snooze came before it was
        // taken back ("quitar el posponer" writes no line), and the next ring overtakes this one.
        snoozedSinceRing = false
    }

    /** Only what happened by [at] counts: a hecho dated earlier takes back what came after it. */
    fun done(at: Instant, shape: RoundShape): Round {
        val rang = rings.count { it <= at }
        return Round(
            end = RoundEnd.DONE,
            startedAt = minOf(startedAt, at),
            endedAt = at,
            rang = rang > 0,
            snoozes = snoozes.count { it <= at },
            chased = chasedAt?.let { it <= at } == true,
            late = shape == RoundShape.ROUTINE && rang > 0,
            rings = rang,
        )
    }

    fun close(end: RoundEnd, at: Instant?) =
        Round(end, startedAt, at, rings.isNotEmpty(), snoozes.size, chasedAt != null, late = false, rings = rings.size)

    /**
     * The round still under way. Chased and unanswered is not done — until a hecho arrives and
     * makes it done, which is why nothing here is stored. A routine whose deadline has rung is
     * overdue now, and that is the streak broken already.
     */
    fun trailing(shape: RoundShape): Round {
        val missed = chasedAt != null || (shape == RoundShape.ROUTINE && rings.isNotEmpty())
        return close(if (missed) RoundEnd.NOT_DONE else RoundEnd.OPEN, null)
    }
}

/** How a closed round is drawn on the reminder's strip. */
enum class RoundMark {
    /** Done the first time it was asked. */
    FIRST_TIME,

    /** Done, after a snooze or the net's word. */
    DONE,

    /** A routine done after going overdue: done, and the streak broken. */
    LATE,

    SKIPPED,
    NOT_DONE,
}

val Round.mark: RoundMark?
    get() = when (end) {
        RoundEnd.DONE -> when {
            late -> RoundMark.LATE
            firstTime -> RoundMark.FIRST_TIME
            else -> RoundMark.DONE
        }
        RoundEnd.NOT_DONE -> RoundMark.NOT_DONE
        RoundEnd.SKIPPED -> RoundMark.SKIPPED
        RoundEnd.OPEN -> null
    }

/**
 * What one reminder's history comes to.
 *
 * [snoozes] are real ones, across every round; [firstTime] is how many of the [done] rounds were
 * done at the first time of asking. [meanGap] is between consecutive hechos, taken in the order
 * they happened (a dated one included where it belongs). [since] is the oldest line kept: the
 * history is capped, so every number here is "since then", never "ever".
 */
data class ReminderStats(
    val shape: RoundShape,
    val done: Int,
    val notDone: Int,
    val skipped: Int,
    val snoozes: Int,
    val firstTime: Int,
    val currentStreak: Int,
    val bestStreak: Int,
    val meanGap: Duration?,
    val lastDone: Instant?,
    val since: Instant?,
    /** The last [RECENT_ROUNDS] closed rounds, oldest first. */
    val recent: List<RoundMark>,
)

/** How many rounds the strip on a reminder shows: a month of a daily one, at a glance. */
const val RECENT_ROUNDS = 20

fun reminderStats(events: List<FiringEvent>, shape: RoundShape): ReminderStats =
    statsOf(rounds(events, shape), shape, since = events.minOfOrNull { it.at })

/** The same, from rounds already read — [since] being the oldest line they were read from. */
fun statsOf(all: List<Round>, shape: RoundShape, since: Instant?): ReminderStats {
    val done = all.filter { it.end == RoundEnd.DONE }
    val hechos = done.mapNotNull { it.endedAt }.sorted()
    val gaps = hechos.zipWithNext { earlier, later -> Duration.between(earlier, later) }
    val counted = shape != RoundShape.QUIET
    return ReminderStats(
        shape = shape,
        done = done.size,
        notDone = all.count { it.end == RoundEnd.NOT_DONE },
        skipped = all.count { it.end == RoundEnd.SKIPPED },
        snoozes = all.sumOf { it.snoozes },
        firstTime = done.count { it.firstTime },
        currentStreak = if (counted) currentStreak(all) else 0,
        bestStreak = if (counted) bestStreak(all) else 0,
        meanGap = gaps.takeIf { it.isNotEmpty() }?.let { list -> list.fold(Duration.ZERO, Duration::plus).dividedBy(list.size.toLong()) },
        lastDone = hechos.lastOrNull(),
        since = since,
        recent = all.mapNotNull { it.mark }.takeLast(RECENT_ROUNDS),
    )
}

/** The run of kept rounds back from the newest, stepping over the skipped and the open. */
fun currentStreak(rounds: List<Round>): Int {
    var run = 0
    for (round in rounds.asReversed()) {
        when {
            round.keepsStreak -> run++
            round.breaksStreak -> return run
        }
    }
    return run
}

/** The longest run of kept rounds anywhere in the history. */
fun bestStreak(rounds: List<Round>): Int {
    var run = 0
    var best = 0
    for (round in rounds) {
        when {
            round.keepsStreak -> best = maxOf(best, ++run)
            round.breaksStreak -> run = 0
        }
    }
    return best
}
