package dev.rwilco.model

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/** Declaration order is display order. */
enum class Section { OVERDUE, TODAY, TOMORROW, THIS_WEEK, LATER, WHENEVER, NO_TRIGGER, PAUSED }

data class HomeEntry(val reminder: Reminder, val next: NextFire?)

data class HomeGroups(
    /** The next definite moment across everything: the one card that glows. */
    val hero: HomeEntry?,
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
 * What Home shows. Done reminders are not here (they have their own screen); a tag filter keeps
 * only reminders carrying that tag. The hero is the earliest definite moment among active
 * reminders and is lifted out of its section; a random draw never becomes the hero.
 */
fun groupForHome(
    reminders: List<Reminder>,
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    tagFilter: String? = null,
    dayStart: LocalTime = DEFAULT_DAY_START,
): HomeGroups {
    val entries = reminders
        .filter { it.status != Status.DONE }
        .filter { tagFilter == null || it.tags.any { tag -> tag.equals(tagFilter, ignoreCase = true) } }
        .map { HomeEntry(it, nextFire(it, now, zone, defaultTime, dayStart)) }
    val hero = entries
        .filter { it.next is NextFire.Scheduled }
        .minByOrNull { (it.next as NextFire.Scheduled).at }
    val grouped = entries
        .filter { it !== hero }
        // A reminder with no trigger but a recurrence is not "kept, not timed": it has a moment.
        .groupBy { sectionOf(it.next, it.reminder.status, it.reminder.rules.isNotEmpty() || it.reminder.recurrence.isAnchored, now, zone) }
        .mapValues { (_, list) -> list.sortedWith(compareBy({ it.sortInstant() }, { -it.reminder.updatedAt.toEpochMilli() })) }
    val ordered = LinkedHashMap<Section, List<HomeEntry>>()
    for (section in Section.entries) grouped[section]?.let { ordered[section] = it }
    return HomeGroups(hero, ordered)
}

private fun HomeEntry.sortInstant(): Instant = when (val next = next) {
    is NextFire.Scheduled -> next.at
    is NextFire.Sometime -> next.at
    else -> Instant.MAX
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
