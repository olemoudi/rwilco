package dev.rwilco.model

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * The achievements (0.150.0): a few milestones, each earned once and kept.
 *
 * Kept, not worked out afresh every time, because what proves one does not last: the history is
 * capped, a finished one-off and its hechos are swept after three months, a reminder can be
 * deleted. "Five hundred hechos" earned in March would be un-earned in June by the sweep, which
 * is the one thing an achievement must never do. So they live in the settings
 * ([AppSettings.achievements]) — which also means they travel in the vault, where the history
 * does not — and every reading only ever adds to them ([mergeUnlocked]).
 *
 * The date is the day the milestone was actually crossed, read off the round that crossed it,
 * not the day the app noticed: the first time this runs on a phone with months of history, what
 * it finds is dated in the past, and nothing celebrates it as news.
 */
@Serializable
enum class AchievementFamily {
    /** So many in a row, of one reminder. */
    STREAK,

    /** So many hechos, everything together. */
    HECHOS,

    /** So many finished weeks with at least [PERFECT_WEEK_MIN] rounds and not one left undone. */
    PERFECT_WEEKS,

    /** So many done before they had to ring. */
    AHEAD,
}

/** The milestones of each family, smallest first. Names and numbers are stored: never renumber one. */
val AchievementFamily.tiers: List<Int>
    get() = when (this) {
        AchievementFamily.STREAK -> listOf(7, 30, 100)
        AchievementFamily.HECHOS -> listOf(50, 100, 250, 500, 1000)
        AchievementFamily.PERFECT_WEEKS -> listOf(1, 4, 12)
        AchievementFamily.AHEAD -> listOf(10, 50)
    }

/** A week is "redonda" from five rounds: fewer is a week somebody barely asked anything of. */
const val PERFECT_WEEK_MIN = 5

/**
 * One milestone earned. [subjectId] and [about] name the reminder a streak was about — its words
 * as they were then, so the achievement still says something after the reminder is gone.
 */
@Serializable
data class Unlocked(
    val family: AchievementFamily,
    val tier: Int,
    @Serializable(with = LocalDateSerializer::class) val on: LocalDate,
    val subjectId: String? = null,
    val about: String? = null,
) {
    /** What makes two unlocks the same one: a streak of thirty is earned once per reminder. */
    val key: String get() = "${family.name}:$tier:${subjectId.orEmpty()}"
}

/**
 * Every milestone the kept history proves, dated by the round that crossed it. Only finished
 * weeks count towards a perfect one: the week under way may still go wrong.
 */
fun achievements(tallies: List<Tally>, now: Instant, zone: ZoneId): List<Unlocked> {
    val out = ArrayList<Unlocked>()
    fun day(round: Round) = round.at.atZone(zone).toLocalDate()

    for (tally in tallies.filter { it.subject.shape.keepsCount }) {
        var run = 0
        for (round in tally.rounds) {
            when {
                round.keepsStreak -> {
                    run++
                    if (run in AchievementFamily.STREAK.tiers) {
                        out += Unlocked(AchievementFamily.STREAK, run, day(round), tally.subject.id, tally.subject.text)
                    }
                }
                round.breaksStreak -> run = 0
            }
        }
    }

    val hechos = tallies.flatMap { tally -> tally.rounds.filter { it.end == RoundEnd.DONE } }.sortedBy { it.at }
    out += crossings(AchievementFamily.HECHOS, hechos.map(::day))

    val ahead = tallies
        .filter { it.subject.shape.canBeAhead }
        .flatMap { tally -> tally.rounds.filter { it.ahead } }
        .sortedBy { it.at }
    out += crossings(AchievementFamily.AHEAD, ahead.map(::day))

    val thisWeek = now.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    // A round still open counts against its week until it closes: a Sunday-evening ring nobody
    // has answered yet may still turn into a miss, and a milestone is never taken back.
    val perfectWeeks = tallies
        .filter { it.subject.shape.keepsCount }
        .flatMap { tally -> tally.rounds.filter { it.end != RoundEnd.SKIPPED } }
        .groupBy { day(it).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
        .filter { (monday, rounds) ->
            monday.isBefore(thisWeek) && rounds.none { it.end == RoundEnd.OPEN } &&
                rounds.size >= PERFECT_WEEK_MIN && rounds.none { it.breaksStreak }
        }
        .keys.sorted()
        .map { monday -> monday.plusDays(6) }
    out += crossings(AchievementFamily.PERFECT_WEEKS, perfectWeeks)
    return out
}

/** The day each tier of [family] was reached, given the days its things happened on, in order. */
private fun crossings(family: AchievementFamily, days: List<LocalDate>): List<Unlocked> =
    family.tiers.filter { it <= days.size }.map { tier -> Unlocked(family, tier, days[tier - 1]) }

/**
 * What was kept, plus whatever [derived] adds; for one already kept, the earlier date. Returns
 * [kept] itself when nothing changes, so a caller can tell a write is not needed.
 */
fun mergeUnlocked(kept: List<Unlocked>, derived: List<Unlocked>): List<Unlocked> {
    val byKey = LinkedHashMap<String, Unlocked>()
    kept.forEach { byKey[it.key] = it }
    var changed = false
    for (one in derived) {
        val existing = byKey[one.key]
        if (existing == null) {
            byKey[one.key] = one
            changed = true
        } else if (one.on.isBefore(existing.on)) {
            byKey[one.key] = existing.copy(on = one.on)
            changed = true
        }
    }
    return if (changed) byKey.values.sortedWith(compareBy<Unlocked> { it.on }.thenBy { it.family.ordinal }.thenBy { it.tier }) else kept
}

/** The nearest milestone still ahead, and how far: what "próximo" says under the achievements. */
data class Goal(val family: AchievementFamily, val tier: Int, val remaining: Int, val subject: StatsSubject? = null)

/**
 * The closest of: the next number of hechos, and the next streak milestone of a running streak.
 * A tie goes to the hechos, which everything counts towards.
 */
fun nextGoal(stats: GlobalStats, unlocked: List<Unlocked>): Goal? {
    val have = unlocked.map { it.key }.toSet()
    val candidates = ArrayList<Goal>()
    AchievementFamily.HECHOS.tiers
        .firstOrNull { tier -> Unlocked(AchievementFamily.HECHOS, tier, LocalDate.MIN).key !in have && tier > stats.hechos }
        ?.let { candidates += Goal(AchievementFamily.HECHOS, it, it - stats.hechos) }
    for (tally in stats.tallies) {
        val streak = tally.stats.currentStreak
        if (!tally.subject.shape.keepsCount || tally.subject.status != Status.ACTIVE || streak <= 0) continue
        AchievementFamily.STREAK.tiers
            .firstOrNull { tier -> tier > streak && Unlocked(AchievementFamily.STREAK, tier, LocalDate.MIN, tally.subject.id).key !in have }
            ?.let { candidates += Goal(AchievementFamily.STREAK, it, it - streak, tally.subject) }
    }
    return candidates.minWithOrNull(compareBy<Goal> { it.remaining }.thenBy { it.family != AchievementFamily.HECHOS }.thenBy { it.subject?.text })
}
