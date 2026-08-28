package dev.rwilco.ui.home

import dev.rwilco.model.DayShape
import dev.rwilco.model.Action
import dev.rwilco.model.Condition
import dev.rwilco.model.DEFAULT_DAY_START
import dev.rwilco.model.NextFire
import dev.rwilco.model.Recurrence
import dev.rwilco.model.Reminder
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.RuleStanding
import dev.rwilco.model.watchedCircles
import dev.rwilco.model.watchingRule
import dev.rwilco.model.ruleStandings
import dev.rwilco.model.SearchHit
import dev.rwilco.model.Section
import dev.rwilco.model.Status
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerFamily
import dev.rwilco.model.family
import dev.rwilco.model.groupForHome
import dev.rwilco.model.isAnchored
import dev.rwilco.model.nextFireOfRule
import dev.rwilco.model.togetherRule
import dev.rwilco.model.search
import dev.rwilco.model.TagFilter
import dev.rwilco.model.tagFilters
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class HomeUiState(
    val loaded: Boolean = false,
    val hero: HeroUi? = null,
    val sections: List<SectionUi> = emptyList(),
    val tags: List<TagFilter> = emptyList(),
    val selectedTag: TagFilter? = null,
    /** When date-only reminders ring; the cards say so under the date. */
    val defaultTime: LocalTime = LocalTime.of(9, 0),
    /** The hours somebody is up; carried so a preset written in one tap is judged by them too. */
    val dayShape: DayShape = DayShape.DEFAULT,
) {
    /** Nothing open at all (as opposed to a filter that matched nothing). */
    val empty: Boolean get() = loaded && hero == null && sections.isEmpty() && selectedTag == null
}

/**
 * The one card that glows. [snoozed] when the moment shown is a "remind me later" rather than
 * the reminder's own: without it a postponed reminder comes back as "next up" with a countdown
 * and no hint that it is there because somebody pushed it away.
 */
/**
 * [atEarliest] is a moment the reminder cannot ring *before* rather than one it will ring at:
 * a place with hours on it, whose window opens then. The card says so instead of promising.
 */
data class HeroUi(
    val card: ReminderCardUi,
    val at: Instant,
    val snoozed: Boolean = false,
    val atEarliest: Boolean = false,
)

data class SectionUi(val section: Section, val cards: List<ReminderCardUi>)

data class ReminderCardUi(
    val id: String,
    val text: String,
    val tags: List<String>,
    val triggers: List<TriggerRowUi>,
    val actions: Set<Action>,
    val paused: Boolean,
    /**
     * How this card's rules are read together, or null when there is only one of them and
     * there is no set to read.
     *
     * The reading itself rather than a word for it: the card draws the set as a tree
     * ([RuleTree]) whose trunk and glyph both come from this, and a string resource could only
     * ever have said one of the two.
     */
    val match: RuleMatch? = null,
    /**
     * When somebody pushed it away to, while that moment is still ahead.
     *
     * It is on the card and not only on the hero because a snooze is the whole answer to "when
     * does this ring next", and the rows underneath cannot say it: they go on describing the
     * rule, so a fortnightly reminder put off for two hours read as a fortnight away — the one
     * card on Home that was telling somebody the opposite of what would happen. The hero says
     * it in its own words (it is what fires next); every other card says it here.
     */
    val snoozedUntil: Instant? = null,
    /**
     * The colour band down the card's edge: the family of what will ring, or null for a
     * reminder with no "when" at all.
     *
     * The family of the rule with the earliest moment, because that is the one the card is
     * about; failing that the first rule's, so a card that is only places still says "place";
     * and [TriggerFamily.TIME] for a reminder whose whole arrangement is a recurrence, which is
     * a clock even though it carries no trigger. The hero is left out of this — it already
     * wears the amber and its lamp, and two marks on one card is one too many.
     */
    val rail: TriggerFamily? = null,
    /**
     * The recurrence, when it works out its own moments — and only then.
     *
     * It gets a row of its own because it is a "when" like any other and the triggers cannot
     * speak for it: a reminder whose only arrangement is "cada 6 h" or "cada lunes" has no
     * trigger at all, and without this its card said nothing whatsoever about when it rings.
     * `ByTrigger` is left out on purpose: the random window already on the card IS that answer,
     * and saying it twice is noise.
     */
    val recurrence: Recurrence? = null,
)

/** One trigger row: the strings are formatted in the composable, which has the locale. */
data class TriggerRowUi(
    val trigger: Trigger,
    /** What has to hold for the moment to count; empty for most rules. */
    val conditions: List<Condition>,
    val family: TriggerFamily,
    /** The definite moment, when there is one. */
    val nextAt: Instant?,
    /** A random trigger's window for the day of its next draw. */
    val window: Pair<Instant, Instant>?,
    /**
     * Where this rule stands in its set right now — ticked off under "todos", true or not under
     * "a la vez". Null for a lone rule and for "cualquiera", where a rule has no standing.
     */
    val standing: RuleStanding? = null,
    /**
     * Whether the place watch is spending anything on this rule's circle right now.
     *
     * Always true for everything that is not a place: nothing else costs a radio. A circle
     * whose gate is shut ("en la oficina, a la vez que de 17 a 19", at three in the morning)
     * is judged for free on the positions other reminders pay for and asks for none of its
     * own, and the mark says so — the shape is the battery, the colour is the answer.
     */
    val watched: Boolean = true,
)

/** Everything Home shows, from the open reminders. Pure and JVM-tested. */
fun buildHomeState(
    reminders: List<Reminder>,
    defaultTime: LocalTime,
    now: Instant,
    zone: ZoneId,
    selectedTag: TagFilter?,
    dayStart: LocalTime = DEFAULT_DAY_START,
    /** The hours somebody is up, which is where a moment "at random during the day" comes from. */
    shape: DayShape = DayShape.DEFAULT,
    /** Is the phone inside this rule's circle? Only the place watch knows; null when nothing does. */
    inside: (String, Int) -> Boolean? = { _, _ -> null },
): HomeUiState {
    val tags = tagFilters(reminders)
    // A filter on something that is no longer offered is no filter: the last reminder carrying
    // that tag was deleted, the last untagged one was named, the last paused one resumed.
    // Case-insensitively, and by the spelling on offer: the chip reads whichever spelling
    // came first, and a filter set on "Compra" must not clear because that reminder went and
    // "compra" is what is left.
    val filter = selectedTag?.let { chosen ->
        if (chosen !is TagFilter.Named) chosen.takeIf { it in tags }
        else tags.firstOrNull { it is TagFilter.Named && it.tag.equals(chosen.tag, ignoreCase = true) }
    }
    val groups = groupForHome(reminders, now, zone, defaultTime, filter, dayStart, shape)
    fun card(reminder: Reminder): ReminderCardUi {
        val standings = reminder.ruleStandings(now, zone, dayStart, shape) { index -> inside(reminder.id, index) }
        val circles = reminder.watchedCircles(now, zone, defaultTime, shape, dayStart)
        val rows = reminder.rules.mapIndexed { index, rule ->
            // Under "a la vez" the row says when the folded rule next holds, which is what
            // will ring; a fold of two moments never does, and the row says nothing.
            val next = reminder.togetherRule(index)?.let { nextFireOfRule(it, reminder.id, now, zone, defaultTime, shape) }
            TriggerRowUi(
                trigger = rule.trigger,
                conditions = rule.conditions,
                family = rule.trigger.family,
                nextAt = (next as? NextFire.Scheduled)?.at,
                window = (next as? NextFire.Sometime)?.let { it.windowStart to it.windowEnd },
                standing = standings.getOrNull(index),
                watched = rule.trigger !is Trigger.Location || circles.watchingRule(index),
            )
        }
        return ReminderCardUi(
            id = reminder.id,
            text = reminder.text,
            tags = reminder.tags,
            triggers = rows,
            actions = reminder.actions,
            rail = railFamily(reminder, rows),
            paused = reminder.status == Status.PAUSED,
            // A paused reminder rings at no moment at all, so a snooze on it is not news.
            snoozedUntil = reminder.snoozedUntil?.takeIf { it > now && reminder.status == Status.ACTIVE },
            match = reminder.ruleMatch.takeIf { reminder.rules.size > 1 },
            recurrence = reminder.recurrence.takeIf { it.isAnchored },
        )
    }
    return HomeUiState(
        loaded = true,
        hero = groups.hero?.let { hero ->
            HeroUi(
                card = card(hero.entry.reminder),
                at = hero.entry.wake!!.at,
                snoozed = (hero.entry.next as? NextFire.Scheduled)?.snoozed == true,
                atEarliest = hero.atEarliest,
            )
        },
        sections = groups.sections.map { (section, entries) -> SectionUi(section, entries.map { card(it.reminder) }) },
        tags = tags,
        selectedTag = filter,
        defaultTime = defaultTime,
        dayShape = shape,
    )
}

/**
 * The family a card's colour band belongs to. See [ReminderCardUi.rail].
 *
 * Pure and separate from [buildHomeState] so the rule is one readable sentence and a test can
 * hold it: the earliest moment's family, else the first rule's, else the clock when a
 * recurrence is doing the work on its own, else nothing.
 */
internal fun railFamily(reminder: Reminder, rows: List<TriggerRowUi>): TriggerFamily? {
    rows.filter { it.nextAt != null }.minByOrNull { it.nextAt!! }?.let { return it.family }
    rows.firstOrNull()?.let { return it.family }
    return TriggerFamily.TIME.takeIf { reminder.recurrence.isAnchored }
}

/** What the magnifier shows: the query as typed, and what it found. */
data class SearchUiState(
    val open: Boolean = false,
    val query: String = "",
    val hits: List<SearchHitUi> = emptyList(),
) {
    /** Typed something, found nothing — as opposed to an empty field, which found nothing yet. */
    val nothingFound: Boolean get() = open && query.isNotBlank() && hits.isEmpty()
}

/** One result. The two kinds are separate types because tapping them does different things. */
sealed interface SearchHitUi {
    /** Stable across queries so the list animates rows instead of rebuilding them. */
    val key: String

    data class OfReminder(val id: String, val text: String, val tags: List<String>) : SearchHitUi {
        override val key: String get() = "reminder-$id"
    }

    /** [count] open reminders carry it; tapping filters Home by it. */
    data class OfTag(val tag: String, val count: Int) : SearchHitUi {
        override val key: String get() = "tag-$tag"
    }
}

/** The search results for [query] over the open reminders. Pure and JVM-tested. */
fun buildSearchState(reminders: List<Reminder>, query: String, open: Boolean): SearchUiState = SearchUiState(
    open = open,
    query = query,
    hits = if (!open) emptyList() else search(reminders, query).map { hit ->
        when (hit) {
            is SearchHit.OfReminder -> SearchHitUi.OfReminder(hit.reminder.id, hit.reminder.text, hit.reminder.tags)
            is SearchHit.OfTag -> SearchHitUi.OfTag(hit.tag, hit.count)
        }
    },
)
