package dev.rwilco.model

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/** Declaration order is display order. */
enum class Section { OVERDUE, TODAY, TOMORROW, THIS_WEEK, LATER, WHENEVER, NO_TRIGGER, PAUSED }

data class HomeEntry(
    val reminder: Reminder,
    val next: NextFire?,
    /** What the alarm is set for: the moment this reminder cannot ring before. */
    val wake: Wake? = null,
    /**
     * For a reminder with nothing ahead of it, the moment that got away: when it last rang, or
     * the alarm it was armed for, or — for one that never rang at all — the last moment its
     * rules named. What "Vencidos" is sorted by and what its cards say ("hace 3 h"); null
     * everywhere else.
     */
    val missedAt: Instant? = null,
)

/**
 * The card that glows, and whether its moment is the ring or only the earliest it could be.
 * A place with hours on it ("al salir de la oficina, de 18:30 a 20:00") cannot ring before the
 * window opens, which is a real thing to say and is *not* a promise that it will ring then.
 */
data class Hero(val entry: HomeEntry, val atEarliest: Boolean)

data class HomeGroups(
    /** The next thing that can happen across everything: the one card that glows. */
    val hero: Hero?,
    /** Only the sections with something in them, in [Section] order. */
    val sections: Map<Section, List<HomeEntry>>,
)

fun sectionOf(next: NextFire?, status: Status, hasRules: Boolean, now: Instant, zone: ZoneId): Section {
    if (status == Status.PAUSED) return Section.PAUSED
    // Nothing to fire and nothing that ever was: a list item, not a missed alarm.
    if (!hasRules) return Section.NO_TRIGGER
    return when (next) {
        null -> Section.OVERDUE
        is NextFire.WhenAt, is NextFire.Sometime -> Section.WHENEVER
        is NextFire.Scheduled -> {
            val today = now.atZone(zone).toLocalDate()
            val day = next.at.atZone(zone).toLocalDate()
            when {
                day <= today -> Section.TODAY
                day == today.plusDays(1) -> Section.TOMORROW
                // Rolling seven days, not the calendar week: a Sunday evening would otherwise
                // show an empty "this week".
                day <= today.plusDays(6) -> Section.THIS_WEEK
                else -> Section.LATER
            }
        }
    }
}

/**
 * What Home shows. Done reminders are not here (they have their own screen); a [TagFilter] keeps
 * only the reminders that belong under it. The hero is the earliest definite moment among active
 * reminders and is lifted out of its section; a random draw never becomes the hero.
 */
fun groupForHome(
    reminders: List<Reminder>,
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    tagFilter: TagFilter? = null,
    dayStart: LocalTime = DEFAULT_DAY_START,
    shape: DayShape = DayShape.DEFAULT,
): HomeGroups {
    val entries = reminders
        .filter { it.status != Status.DONE }
        .filter { tagFilter == null || tagFilter.matches(it) }
        .map {
            val next = nextFire(it, now, zone, defaultTime, dayStart, shape)
            HomeEntry(
                reminder = it,
                next = next,
                wake = nextWake(it, now, zone, defaultTime, dayStart, shape),
                missedAt = if (next == null) it.missedMoment(now, zone, defaultTime, dayStart, shape) else null,
            )
        }
    val hero = heroOf(entries, now)
    val grouped = entries
        .filter { it !== hero?.entry }
        // A reminder with no trigger but a recurrence is not "kept, not timed": it has a moment.
        .groupBy { sectionOf(it.next, it.reminder.status, it.reminder.rules.isNotEmpty() || it.reminder.recurrence.isAnchored, now, zone) }
        // By the moment, then by the day it was written. **Not by when it was last edited**,
        // which is what it was: in the sections where nothing has a moment — cuando ocurra,
        // sin fecha, en pausa — every card ties on the first key, so the second was the whole
        // order, and fixing a typo threw the reminder to the top of its section. Nothing about
        // editing the words says anything about where a card belongs. **Vencidos has a
        // moment too** (0.67.0): the one that got away. It used to tie on `Instant.MAX` with
        // the rest, so five overdue cards came out in the order they were *typed*; now the one
        // missed longest ago is on top, and the list reads from the past to the future the
        // way the sections under it do.
        .mapValues { (_, list) -> list.sortedWith(compareBy({ it.sortInstant() }, { it.reminder.createdAt })) }
    val ordered = LinkedHashMap<Section, List<HomeEntry>>()
    for (section in Section.entries) grouped[section]?.let { ordered[section] = it }
    return HomeGroups(hero, ordered)
}

/**
 * The one card that glows: **the soonest thing that can happen**, not the soonest thing with a
 * date on it.
 *
 * Those were the same thing until somebody's phone filled up with places. A reminder waiting on
 * an arrival has no date at all, so a single appointment five months out was the only candidate
 * and sat at the top counting down 138 days while five other reminders were going to ring that
 * evening. What a place-with-hours does have is a floor — it cannot ring before its window
 * opens, which is exactly what the alarm is set for ([nextWake]) — and that is a fair thing to
 * rank by and to say: *como pronto*, las 18:30.
 *
 * Three rules fall out of it. A bare place ("al llegar a casa") has no floor and cannot be
 * ranked, so it stays in "cuando ocurra" where it belongs. A random draw is never lifted out,
 * because a random reminder that announces its time is not random. And nothing beyond
 * [HERO_HORIZON] is lifted at all: "lo siguiente" is a promise about soon, and a countdown of
 * months is a card that pushes today's list down the screen to say nothing.
 */
private fun heroOf(entries: List<HomeEntry>, now: Instant): Hero? {
    val horizon = now.plus(HERO_HORIZON)
    return entries
        .mapNotNull { entry ->
            val at = entry.wake?.at ?: return@mapNotNull null
            if (at.isAfter(horizon)) return@mapNotNull null
            when (entry.next) {
                is NextFire.Scheduled -> Hero(entry, atEarliest = false)
                is NextFire.WhenAt -> Hero(entry, atEarliest = true)
                // A window is the whole of what a random reminder will say about itself.
                is NextFire.Sometime, null -> null
            }
        }
        .minByOrNull { it.entry.wake!!.at }
}

/** Past a week, "lo siguiente" is not next; it is just the only one with a date. */
val HERO_HORIZON: Duration = Duration.ofDays(7)

private fun HomeEntry.sortInstant(): Instant = when (val next = next) {
    is NextFire.Scheduled -> next.at
    is NextFire.Sometime -> next.at
    null -> missedAt ?: Instant.MAX
    else -> Instant.MAX
}

/**
 * The moment an overdue reminder missed. The ring is the best witness when there was one; the
 * alarm it was armed for is the next best (a ring the phone slept through); and for one that
 * never got as far as an alarm, the last moment its rules named ([lastMomentGone]), which is
 * the same walk the safety net takes. A paused or unarmed shape with nothing to name gives
 * null, and sorts last.
 */
private fun Reminder.missedMoment(now: Instant, zone: ZoneId, defaultTime: LocalTime, dayStart: LocalTime, shape: DayShape): Instant? {
    if (status != Status.ACTIVE) return null
    lastFiredAt?.let { return it }
    armedFor?.takeIf { !it.isAfter(now) }?.let { return it }
    return lastMomentGone(now, zone, defaultTime, dayStart, shape)
}

/** Every tag in use on open reminders, most used first, then alphabetically; one spelling per tag. */
fun tagsInUse(reminders: List<Reminder>): List<String> {
    val counts = LinkedHashMap<String, Pair<String, Int>>()
    for (reminder in reminders) {
        if (reminder.status == Status.DONE) continue
        for (tag in reminder.tags) {
            val key = tag.lowercase(Locale.ROOT)
            val (spelling, count) = counts[key] ?: (tag to 0)
            counts[key] = spelling to count + 1
        }
    }
    return counts.values
        .sortedWith(compareBy({ -it.second }, { it.first.lowercase(Locale.ROOT) }))
        .map { it.first }
}
