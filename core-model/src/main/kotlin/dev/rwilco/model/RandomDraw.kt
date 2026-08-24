package dev.rwilco.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The moments a random trigger fires at, drawn deterministically from (reminder id, period)
 * so the UI and the scheduler agree without storing anything. Own generator and hash rather
 * than `kotlin.random`/`hashCode`, whose algorithms are not part of any contract: a JDK update
 * must not reshuffle a person's reminders. The golden values in RandomDrawTest pin it.
 */
object RandomDraw {

    private const val GOLDEN_GAMMA = 0x9E3779B97F4A7C15uL

    /** DAY: the epoch day. WEEK: Monday-start weeks, independent of locale on purpose. */
    fun periodIndex(date: LocalDate, period: Period): Long = when (period) {
        Period.DAY -> date.toEpochDay()
        // Epoch day 0 was a Thursday; +3 aligns the division to Mondays.
        Period.WEEK -> Math.floorDiv(date.toEpochDay() + 3, 7L)
    }

    /** The Monday of a WEEK period. */
    fun weekStart(periodIndex: Long): LocalDate = LocalDate.ofEpochDay(periodIndex * 7 - 3)

    /** The instants for one period, sorted, all inside the trigger's window on eligible days. */
    fun draws(trigger: Trigger.Random, reminderId: String, periodIndex: Long, zone: ZoneId): List<Instant> {
        val windowMinutes = windowMinutes(trigger)
        if (windowMinutes <= 0 || trigger.timesPer <= 0) return emptyList()
        val rng = SplitMix64(seed(reminderId, periodIndex, trigger.period))
        return when (trigger.period) {
            Period.DAY -> {
                val date = LocalDate.ofEpochDay(periodIndex)
                if (!eligible(date, trigger.days)) return emptyList()
                val offsets = List(trigger.timesPer) { rng.nextInt(windowMinutes) }.sorted()
                spreadApart(offsets, windowMinutes).map { minutes ->
                    date.atTime(trigger.from).plusMinutes(minutes.toLong()).atZone(zone).toInstant()
                }
            }
            Period.WEEK -> {
                val monday = weekStart(periodIndex)
                val eligibleDays = (0L..6L).map { monday.plusDays(it) }.filter { eligible(it, trigger.days) }
                if (eligibleDays.isEmpty()) return emptyList()
                val pairs = List(trigger.timesPer) {
                    val day = eligibleDays[rng.nextInt(eligibleDays.size)]
                    val minute = rng.nextInt(windowMinutes)
                    day to minute
                }
                spreadApart(pairs.sortedWith(compareBy({ it.first }, { it.second }))).map { (day, minutes) ->
                    day.atTime(trigger.from).plusMinutes(minutes.toLong()).atZone(zone).toInstant()
                }
            }
        }
    }

    fun windowMinutes(trigger: Trigger.Random): Int =
        (trigger.to.toSecondOfDay() - trigger.from.toSecondOfDay()) / 60

    private fun eligible(date: LocalDate, days: Set<java.time.DayOfWeek>): Boolean =
        days.isEmpty() || date.dayOfWeek in days

    /** Two draws on the same minute would ring twice at once; the later one moves a minute on. */
    private fun spreadApart(sortedOffsets: List<Int>, windowMinutes: Int): List<Int> {
        val result = ArrayList<Int>(sortedOffsets.size)
        for (offset in sortedOffsets) {
            val previous = result.lastOrNull()
            result += if (previous != null && offset <= previous) minOf(previous + 1, windowMinutes - 1) else offset
        }
        return result
    }

    private fun spreadApart(sortedPairs: List<Pair<LocalDate, Int>>): List<Pair<LocalDate, Int>> {
        val result = ArrayList<Pair<LocalDate, Int>>(sortedPairs.size)
        for (pair in sortedPairs) {
            val previous = result.lastOrNull()
            result += if (previous != null && pair.first == previous.first && pair.second <= previous.second) {
                previous.first to previous.second + 1
            } else {
                pair
            }
        }
        return result
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
