package dev.rwilco.model

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * The encouragement (0.151.0): a quiet line on Home under what fires next, and now and then a
 * silent notification — "lo estás haciendo genial, sigue así", in the owner's words, but never
 * canned.
 *
 * What keeps it from being canned is not the phrasing, it is that **every line carries a real
 * number or a reminder's own words** ([Cheer.numbers], [Cheer.subject]): there is no generic
 * "vas bien". A line is offered only when what it says is true and good; nothing true and good
 * to say is silence. Then freshness: the same fact is not said again for a week, the same
 * reminder not for a day and a half, the same kind not twice running when there is anything
 * else, and the same phrasing never twice in a row ([pickCheer]). The phrasings themselves are
 * the app's (a string-array per kind, with humour that never needs to understand the reminder's
 * words), and every number offered is two or more, so no phrasing ever needs a singular.
 */
@Serializable
enum class CheerKind {
    /** So many in a row, of one reminder. */
    STREAK,

    /** A run longer than any it had before. */
    BEST_STREAK,

    /** A reminder never once left undone. */
    NEVER_FAILS,

    /** Most of the month's hechos at the first ask. */
    FIRST_TIME,

    /** More this week than the last. */
    WEEK_UP,

    /** A good week, whatever the last one was. */
    WEEK_COUNT,

    /** A busy day. */
    TODAY_COUNT,

    /** Done this week before they had to ring. */
    AHEAD,

    /** A milestone earned in the last few days. */
    ACHIEVEMENT,
}

/**
 * One true thing worth saying. [key] is the fact itself — the kind, what it is about and its
 * numbers — so a streak that grew is a new fact and the same streak is not. [subject] is the
 * reminder's words where the line is about one; [achievement] the milestone where it is one.
 */
data class Cheer(
    val kind: CheerKind,
    val key: String,
    val weight: Int,
    val numbers: List<Int> = emptyList(),
    val subjectId: String? = null,
    val subject: String? = null,
    val achievement: Unlocked? = null,
)

/**
 * A line that was said: on Home in [slot], or ([notified]) as a notification. What [pickCheer]
 * remembers to keep the next one fresh. Kept on the phone in a store of its own, never in the
 * settings — it changes three times a day, and the settings are what the backup watches.
 */
@Serializable
data class CheerShown(
    val key: String,
    val kind: CheerKind,
    val variant: Int,
    @Serializable(with = InstantSerializer::class) val at: Instant,
    val subjectId: String? = null,
    val slot: String? = null,
    val notified: Boolean = false,
)

data class CheerPick(val cheer: Cheer, val variant: Int)

/** A fact said is not said again for a week. */
val FACT_REST: Duration = Duration.ofDays(7)

/** A reminder talked about is left alone for a day and a half, so the line moves around. */
val SUBJECT_REST: Duration = Duration.ofHours(36)

/** A kind of line said is let rest for a day when there is any other kind to say. */
val KIND_REST: Duration = Duration.ofHours(24)

/** A milestone is news for three days (the day it was earned and the two after); then it lives on the Hechos screen. */
const val ACHIEVEMENT_NEWS_DAYS = 3L

/** How many said lines are remembered: two weeks of three a day and a notification every few. */
const val CHEERS_REMEMBERED = 40

/**
 * Every true and good thing there is to say right now. Only about reminders still going; only
 * numbers of two or more.
 */
fun cheers(stats: GlobalStats, unlocked: List<Unlocked>, now: Instant, zone: ZoneId): List<Cheer> {
    val out = ArrayList<Cheer>()
    val today = now.atZone(zone).toLocalDate()
    for (tally in stats.tallies) {
        val subject = tally.subject
        if (subject.status != Status.ACTIVE || !subject.shape.keepsCount) continue
        val streak = tally.stats.currentStreak
        val before = previousBest(tally.rounds)
        if (streak >= BEST_STREAK_MIN && before >= STREAK_SHOWN && streak > before) {
            out += Cheer(CheerKind.BEST_STREAK, "best:${subject.id}:$streak", 70, listOf(streak), subject.id, subject.text)
        } else if (streak >= STREAK_SHOWN) {
            out += Cheer(CheerKind.STREAK, "streak:${subject.id}:$streak", 40, listOf(streak), subject.id, subject.text)
        }
        if (tally.neverFailed) {
            out += Cheer(CheerKind.NEVER_FAILS, "never:${subject.id}:${tally.stats.done}", 30, listOf(tally.stats.done), subject.id, subject.text)
        }
    }
    stats.firstTime?.let { (first, of) ->
        if (firstTimeIsGood(first, of)) out += Cheer(CheerKind.FIRST_TIME, "first:$first/$of", 30, listOf(first, of))
    }
    val gain = stats.thisWeek - stats.lastWeek
    when {
        stats.lastWeek > 0 && gain >= 2 && stats.thisWeek >= WEEK_SHOWN ->
            out += Cheer(CheerKind.WEEK_UP, "weekup:${stats.thisWeek}:$gain", 45, listOf(stats.thisWeek, gain))
        // A good week is only news when it is not a step down: "tu lista empieza a tenerte
        // respeto" over half of last week's number reads as sarcasm.
        stats.thisWeek >= WEEK_SHOWN && stats.thisWeek >= stats.lastWeek ->
            out += Cheer(CheerKind.WEEK_COUNT, "week:${stats.thisWeek}", 20, listOf(stats.thisWeek))
    }
    if (stats.today >= TODAY_SHOWN) out += Cheer(CheerKind.TODAY_COUNT, "today:$today:${stats.today}", 35, listOf(stats.today))
    // The same seven days "esta semana" means everywhere else: today and the six before it.
    val weekStart = today.minusDays(6)
    val ahead = stats.tallies
        .filter { it.subject.shape.canBeAhead }
        .sumOf { tally -> tally.rounds.count { it.ahead && !it.at.atZone(zone).toLocalDate().isBefore(weekStart) } }
    if (ahead >= 2) out += Cheer(CheerKind.AHEAD, "ahead:$ahead", 25, listOf(ahead))
    for (one in unlocked) {
        if (ChronoUnit.DAYS.between(one.on, today) >= ACHIEVEMENT_NEWS_DAYS) continue
        out += Cheer(CheerKind.ACHIEVEMENT, "ach:${one.key}", 100, subjectId = one.subjectId, achievement = one)
    }
    return out
}

/**
 * Whether this is a moment for encouragement at all: nothing waiting for an answer — the owner's
 * own condition. Praise over an unanswered alarm is the app not listening. The same question
 * Home's "esperando respuesta" card answers (`answersOwed`), so the line on Home and the word in
 * the shade go quiet together (review of 0.152.0: they asked two different questions). Not
 * "anything in Vencidos": one old overdue card would silence it for weeks.
 */
fun calmForCheer(reminders: List<Reminder>, now: Instant): Boolean = answersOwed(reminders, now).isEmpty()

/** A "best ever" only counts past five, and against a record that was a streak itself. */
const val BEST_STREAK_MIN = 5

/** A week worth mentioning. */
const val WEEK_SHOWN = 5

/** A day worth mentioning. */
const val TODAY_SHOWN = 3

/** The longest run that has already ended — the record the running one is up against. */
fun previousBest(rounds: List<Round>): Int {
    var run = 0
    var best = 0
    for (round in rounds) {
        when {
            round.keepsStreak -> run++
            round.breaksStreak -> {
                best = maxOf(best, run)
                run = 0
            }
        }
    }
    return best
}

/**
 * The line to say, or null for silence.
 *
 * With a [slot] (Home), the line already said in it stays while it is still true — the screen
 * does not change its mind every time it is opened in the same afternoon. Otherwise: a fact not
 * said in [FACT_REST], about a reminder not talked about in [SUBJECT_REST], of a kind other than
 * the last one said when there is any other, weighted towards the better news and drawn with
 * [seed] — which a caller makes from the slot, so it is the same draw however often it is asked.
 * The phrasing is any of the kind's [variants] but the one it used last.
 */
fun pickCheer(
    candidates: List<Cheer>,
    shown: List<CheerShown>,
    now: Instant,
    variants: Map<CheerKind, Int>,
    seed: Long,
    slot: String? = null,
): CheerPick? {
    val sayable = candidates.filter { (variants[it.kind] ?: 0) > 0 }
    if (slot != null) {
        // The slot's line holds while its kind still has something to say about the same thing:
        // "llevas 12 esta semana" becomes "llevas 13" after a "hecho", in the same words, rather
        // than the line changing its mind — and the memory filling — with every hecho.
        val here = shown.lastOrNull { it.slot == slot && !it.notified }
        val still = here?.let { said -> sayable.firstOrNull { it.kind == said.kind && it.subjectId == said.subjectId } }
        if (here != null && still != null) return CheerPick(still, here.variant.coerceIn(0, variants.getValue(still.kind) - 1))
    }
    val recentFacts = shown.filter { Duration.between(it.at, now) < FACT_REST }.map { it.key }.toSet()
    val recentSubjects = shown.filter { it.subjectId != null && Duration.between(it.at, now) < SUBJECT_REST }.mapNotNull { it.subjectId }.toSet()
    val fresh = sayable.filter { it.key !in recentFacts && (it.subjectId == null || it.subjectId !in recentSubjects) }
    // A kind said in the last day gives way to one that was not, and failing that the kind said
    // last gives way to any other: "llevas 12 esta semana" in the morning and "llevas 14 esta
    // semana" at night is two facts and one line said twice.
    val recentKinds = shown.filter { Duration.between(it.at, now) < KIND_REST }.map { it.kind }.toSet()
    val lastKind = shown.maxByOrNull { it.at }?.kind
    val pool = fresh.filter { it.kind !in recentKinds && it.kind != lastKind }
        .ifEmpty { fresh.filter { it.kind != lastKind } }
        .ifEmpty { fresh }
    if (pool.isEmpty()) return null
    val random = Random(seed)
    var ticket = random.nextInt(pool.sumOf { it.weight })
    val chosen = pool.first { cheer -> (ticket - cheer.weight).also { ticket = it } < 0 }
    val count = variants.getValue(chosen.kind)
    val last = shown.filter { it.kind == chosen.kind }.maxByOrNull { it.at }?.variant
    val variant = if (count <= 1 || last == null || last !in 0 until count) random.nextInt(count)
    else (last + 1 + random.nextInt(count - 1)) % count
    return CheerPick(chosen, variant)
}

/** A said line remembered, the oldest forgotten past [CHEERS_REMEMBERED]. */
fun List<CheerShown>.remembering(said: CheerShown): List<CheerShown> = (this + said).takeLast(CHEERS_REMEMBERED)

/**
 * Which of the person's three parts of the day [now] falls in (their own hours, [DayParts] — not
 * [dayPartOf]'s fixed bands), as a key ("2026-09-25:AFTERNOON"): what a Home line lasts for. The latest of the
 * three hours at or before it, and before the first of them it is still last night's. The hours
 * are taken in clock order whatever their names, since somebody who works nights is allowed an
 * evening before their morning ([DayParts]).
 */
fun daySlot(now: Instant, zone: ZoneId, parts: DayParts): String {
    val local = now.atZone(zone).toLocalDateTime()
    val starts = listOf(DayPart.MORNING to parts.morning, DayPart.AFTERNOON to parts.afternoon, DayPart.EVENING to parts.evening)
        .sortedBy { it.second }
    val time = local.toLocalTime()
    val current = starts.lastOrNull { it.second <= time }
    return if (current != null) "${local.toLocalDate()}:${current.first}"
    else "${local.toLocalDate().minusDays(1)}:${starts.last().first}"
}

/** A seed for a slot's draw: the same slot, the same line, however often Home asks. */
fun slotSeed(slot: String): Long = slot.hashCode().toLong()

/** An hour clear of each end of the waking day: no encouragement over breakfast in bed or on the way to sleep. */
private val AWAKE_MARGIN: Duration = Duration.ofHours(1)

/** How long a notification that found something waiting for an answer waits before trying again. */
val CHEER_RETRY: Duration = Duration.ofMinutes(90)

/**
 * When the next silent word goes: two or three days after [after], at a minute drawn from that
 * day's waking hours with an hour clear at each end.
 */
fun nextCheerAt(after: Instant, zone: ZoneId, shape: DayShape, random: Random): Instant {
    val day = after.atZone(zone).toLocalDate().plusDays(2L + random.nextInt(2))
    return momentIn(day, zone, shape, random)
}

/**
 * Try again later today: [CHEER_RETRY] on, if that is still inside today's waking hours (less the
 * margin); otherwise at a drawn moment of tomorrow's.
 */
fun cheerRetryAt(now: Instant, zone: ZoneId, shape: DayShape, random: Random): Instant {
    val retry = now.plus(CHEER_RETRY).atZone(zone).toLocalDateTime()
    val date = now.atZone(zone).toLocalDate()
    // The first waking stretch that has not closed by then — last night's that runs past
    // midnight, today's, or tomorrow's — and inside it the retry, or its opening if later. A run
    // at three in the morning waits for the morning, not for the day after it.
    for (day in listOf(date.minusDays(1), date, date.plusDays(1))) {
        val window = shape.awakeOn(day)
        val opens = window.from.plus(AWAKE_MARGIN)
        val closes = window.to.minus(AWAKE_MARGIN)
        if (retry < closes) return maxOf(retry, opens).atZone(zone).toInstant()
    }
    return momentIn(date.plusDays(2), zone, shape, random)
}

private fun momentIn(day: LocalDate, zone: ZoneId, shape: DayShape, random: Random): Instant {
    val window = shape.awakeOn(day)
    val from: LocalDateTime = window.from.plus(AWAKE_MARGIN)
    val to: LocalDateTime = window.to.minus(AWAKE_MARGIN)
    val minutes = Duration.between(from, to).toMinutes().coerceAtLeast(1)
    return from.plusMinutes(random.nextLong(minutes)).atZone(zone).toInstant()
}

/**
 * A reminder's words short enough to sit inside a sentence: cut at a word, with an ellipsis, so
 * the closing quote around it survives on a phone's line.
 */
fun shortSubject(text: String, max: Int = SUBJECT_MAX): String {
    val clean = text.trim().replace(Regex("\\s+"), " ")
    if (clean.length <= max) return clean
    var cut = clean.lastIndexOf(' ', max - 1).takeIf { it >= max / 2 } ?: (max - 1)
    // Never through the middle of a character that takes two chars (an emoji, say).
    if (Character.isHighSurrogate(clean[cut - 1])) cut--
    return clean.substring(0, cut).trimEnd(',', '.', ';', ':', ' ') + "…"
}

const val SUBJECT_MAX = 40
