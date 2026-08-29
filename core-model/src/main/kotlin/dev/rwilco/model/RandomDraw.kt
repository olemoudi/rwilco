package dev.rwilco.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.min

/**
 * The moments a random trigger fires at, drawn deterministically from (reminder id, period)
 * so the UI and the scheduler agree without storing anything. Own generator and hash rather
 * than `kotlin.random`/`hashCode`, whose algorithms are not part of any contract: a JDK update
 * must not reshuffle a person's reminders. The golden values in RandomDrawTest pin it.
 */
object RandomDraw {

    private const val GOLDEN_GAMMA = 0x9E3779B97F4A7C15uL
    private const val SECONDS_PER_DAY = 24 * 60 * 60

    /** DAY: the epoch day. WEEK: Monday-start weeks, independent of locale on purpose. */
    fun periodIndex(date: LocalDate, period: Period): Long = when (period) {
        Period.DAY -> date.toEpochDay()
        // Epoch day 0 was a Thursday; +3 aligns the division to Mondays.
        Period.WEEK -> Math.floorDiv(date.toEpochDay() + 3, 7L)
    }

    /** The Monday of a WEEK period. */
    fun weekStart(periodIndex: Long): LocalDate = LocalDate.ofEpochDay(periodIndex * 7 - 3)

    /**
     * The instants for one period, sorted, all inside the trigger's window on eligible days —
     * and, given [fences], all on minutes the fences allow.
     *
     * **Drawn inside the fences, never judged against them afterwards** — the same rule
     * [inDay] follows, and for the same reason. A weekly draw made over the whole week and then
     * asked "is it a Saturday?" was a reminder that waited seven weeks on average and could be
     * called *never* by the walk; under "a la vez" a window folded in as a fence did the same
     * to a daily one. So the minutes each day allows are listed first and the draw is one of
     * those. With no fences this is, to the bit, the draw it has always been: the same calls on
     * the generator with the same bounds, so nothing without a fence moves.
     */
    fun draws(
        trigger: Trigger.Random,
        reminderId: String,
        periodIndex: Long,
        zone: ZoneId,
        fences: List<Condition.TimeWindow> = emptyList(),
    ): List<Instant> {
        val windowMinutes = windowMinutes(trigger)
        if (windowMinutes <= 0 || trigger.timesPer <= 0) return emptyList()
        val rng = SplitMix64(seed(reminderId, periodIndex, trigger.period))
        // The minutes of the window on [day] that every fence allows: all of them with none.
        fun allowed(day: LocalDate): List<Int> =
            if (fences.isEmpty()) {
                (0 until windowMinutes).toList()
            } else {
                (0 until windowMinutes).filter { minute ->
                    val at = day.atTime(trigger.from).plusMinutes(minute.toLong())
                    fences.all { it.holdsAt(at) }
                }
            }
        fun moment(day: LocalDate, minute: Int): Instant =
            day.atTime(trigger.from).plusMinutes(minute.toLong()).atZone(zone).toInstant()
        return when (trigger.period) {
            Period.DAY -> {
                val date = LocalDate.ofEpochDay(periodIndex)
                if (!eligible(date, trigger.days)) return emptyList()
                val minutes = allowed(date)
                if (minutes.isEmpty()) return emptyList()
                val offsets = List(drawCount(trigger, minutes.size)) { rng.nextInt(minutes.size) }.sorted()
                spreadApart(offsets, minutes.size).map { moment(date, minutes[it]) }
            }
            Period.WEEK -> {
                val monday = weekStart(periodIndex)
                val eligibleDays = (0L..6L).map { monday.plusDays(it) }
                    .filter { eligible(it, trigger.days) }
                    .associateWith { allowed(it) }
                    .filterValues { it.isNotEmpty() }
                if (eligibleDays.isEmpty()) return emptyList()
                val days = eligibleDays.keys.toList()
                val pairs = List(drawCount(trigger, windowMinutes)) {
                    val day = days[rng.nextInt(days.size)]
                    val minute = rng.nextInt(eligibleDays.getValue(day).size)
                    day to minute
                }
                spreadApartInDays(pairs.sortedWith(compareBy({ it.first }, { it.second }))) { eligibleDays.getValue(it).size }
                    .map { (day, index) -> moment(day, eligibleDays.getValue(day)[index]) }
            }
        }
    }

    /**
     * One moment inside a day's waking hours, drawn from (reminder, day).
     *
     * What "at random during the day" resolves to, for a date and for a repeat that was given
     * no hour. Same generator as the rest of this object and the same reason for it: the
     * scheduler and the screen have to agree on the moment without either of them writing it
     * down, and it has to hold still while the day does.
     *
     * **A draw is made inside its [fences], never judged against them afterwards.** "El jueves
     * a cualquier hora, y sólo si es entre las 16 y las 17" has one moment in it, and a moment
     * drawn from the whole day and then asked "is it between four and five?" is a reminder that
     * fifteen times in sixteen silently does not ring — and for a monthly calendar with no hour,
     * one that rings once a year. So the minutes the fences allow are listed and the draw is one
     * of those. With no fences this is, to the bit, the draw it has always been: one call on the
     * generator, the same seed, the same minute. When no minute of the window clears the fences
     * the plain draw is handed back and the caller's walk rejects it, which is what a fence that
     * names *other days* ("sólo los lunes") has to do to a daily calendar.
     */
    fun inDay(
        reminderId: String,
        date: LocalDate,
        window: AwakeWindow,
        zone: ZoneId,
        fences: List<Condition.TimeWindow> = emptyList(),
    ): Instant {
        val from = window.from.atZone(zone).toInstant()
        val to = window.to.atZone(zone).toInstant()
        val minutes = java.time.Duration.between(from, to).toMinutes().toInt()
        if (minutes < 2) return from
        val rng = SplitMix64(seed(reminderId, date.toEpochDay(), Period.DAY))
        if (fences.isEmpty()) return from.plusSeconds(rng.nextInt(minutes) * 60L)
        val eligible = (0 until minutes).filter { offset ->
            val at = from.plusSeconds(offset * 60L).atZone(zone).toLocalDateTime()
            fences.all { it.holdsAt(at) }
        }
        val offset = if (eligible.isEmpty()) rng.nextInt(minutes) else eligible[rng.nextInt(eligible.size)]
        return from.plusSeconds(offset * 60L)
    }

    /**
     * A window that ends before it starts crosses midnight, as every other window in the model
     * does ([Condition.TimeWindow], [Trigger.Interval]); one that ends where it starts is empty.
     * Read as negative it drew nothing at all, and the editor called a four-hour evening window
     * "empty". The draws themselves need no more: a minute past the day's end lands on the next
     * one (`atTime(from).plusMinutes`), and a draw belongs to the day its window opened.
     */
    fun windowMinutes(trigger: Trigger.Random): Int =
        Math.floorMod(trigger.to.toSecondOfDay() - trigger.from.toSecondOfDay(), SECONDS_PER_DAY) / 60

    private fun eligible(date: LocalDate, days: Set<java.time.DayOfWeek>): Boolean =
        days.isEmpty() || date.dayOfWeek in days

    /**
     * A window cannot hold more draws than it has minutes. Validation already refuses one that
     * cannot ([TriggerProblem.WINDOW_EMPTY]), so this only catches a trigger from an older build
     * or a hand-edited store — where drawing more would be drawing the same minute twice.
     */
    private fun drawCount(trigger: Trigger.Random, windowMinutes: Int): Int =
        min(trigger.timesPer, windowMinutes)

    /**
     * Two draws on the same minute would ring twice at once; the later one moves a minute on.
     *
     * Each is also held back far enough from the end of the window to leave a minute for every
     * draw still to come. Without that, a pile-up against the last minute had nowhere to be
     * pushed and landed on top of the one before it — which is the one case where "moves a
     * minute on" could not.
     */
    private fun spreadApart(sortedOffsets: List<Int>, windowMinutes: Int): List<Int> {
        val result = ArrayList<Int>(sortedOffsets.size)
        for ((index, offset) in sortedOffsets.withIndex()) {
            val ceiling = windowMinutes - (sortedOffsets.size - index)
            val previous = result.lastOrNull()
            val at = if (previous != null && offset <= previous) previous + 1 else offset
            result += min(at, ceiling)
        }
        return result
    }

    /**
     * The same, a week at a time: only draws that landed on the same day can collide, so each
     * day is spread on its own, against its own minutes.
     *
     * A day the fences leave fewer minutes on than draws landed there keeps the ones that fit —
     * the week's count is sized from the whole window, and a fence naming a two-minute stretch
     * of one day (a day with no hour, settled at bedtime, folded in under "a la vez") can land
     * every draw on it. Spread against a ceiling below zero, that was an index below zero.
     */
    private fun spreadApartInDays(sortedPairs: List<Pair<LocalDate, Int>>, minutesOn: (LocalDate) -> Int): List<Pair<LocalDate, Int>> =
        sortedPairs.groupBy({ it.first }, { it.second }).flatMap { (day, minutes) ->
            val room = minutesOn(day)
            spreadApart(minutes.take(room), room).map { day to it }
        }

    internal fun seed(reminderId: String, periodIndex: Long, period: Period): Long =
        fnv1a64(reminderId) xor (periodIndex * GOLDEN_GAMMA.toLong()) xor period.ordinal.toLong()

    internal fun fnv1a64(text: String): Long {
        var hash = 0xcbf29ce484222325uL.toLong()
        for (byte in text.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= 0x100000001b3L
        }
        return hash
    }

    /** Vigna's SplitMix64: tiny, fast, and specified to the bit. */
    internal class SplitMix64(private var state: Long) {
        fun nextLong(): Long {
            state += GOLDEN_GAMMA.toLong()
            var z = state
            z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
            z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
            return z xor (z ushr 31)
        }

        /** Uniform enough for minutes in a window; the modulo bias is 2^-50-ish. */
        fun nextInt(bound: Int): Int = Math.floorMod(nextLong(), bound.toLong()).toInt()
    }
}
