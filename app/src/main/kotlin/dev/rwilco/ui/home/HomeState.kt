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
import dev.rwilco.model.ruleInSet
import dev.rwilco.model.search
import dev.rwilco.model.TagFilter
import dev.rwilco.model.tagFilters
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import dev.rwilco.model.awaitingAnswer
import dev.rwilco.model.momentDealtWith

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
    /**
     * Reminders, but none for today and none missed: the verdict Home never gave (0.68.0). A
     * phone with five reminders next month showed a list and no answer to "am I free today?",
     * while the widget beside it said "0 hoy".
     */
    val quietToday: Boolean = false,
    /** The store could not be read: an error to say, not a loading state to sit in for ever. */
    val failed: Boolean = false,
) {
    /** Nothing open at all (as opposed to a filter that matched nothing). */
    val empty: Boolean get() = loaded && !failed && hero == null && sections.isEmpty() && selectedTag == null
}

/**
 * Where a reminder's card sits in Home's list, or null when it has no row to go to — a filter is
 * hiding it, or the save has not reached the screen yet.
 *
 * The hero counts: it is lifted out of its *section* but it is still a row in the same column,
 * and a list scrolled well down is a list with the hero off the top of it — which is exactly
 * where a reminder goes when an edit gives it the soonest moment on the phone.
 *
 * It exists because "take me to the card I just saved" needs an *index* and a `LazyColumn` only
 * ever knows about the keys it has composed. So this is a **mirror of the order that column is
 * built in**, and the two have to be changed together — which is why it is here, pure, and
 * pinned by a test, rather than counted out at the call site where nothing would ever notice it
 * drifting.
 *
 * [strip], [pinned] and [undoRow] are the items above the list that come and go; searching and
 * the loading placeholder are not asked about, because neither is on screen when somebody
 * arrives back from a save.
 */
fun homeCardIndex(state: HomeUiState, id: String, strip: Boolean, pinned: Boolean, undoRow: Boolean = false): Int? {
    var index = 0
    if (strip) index++
    if (pinned) index++
    if (state.tags.isNotEmpty()) index++
    if (undoRow) index++
    if (state.hero != null) {
        if (state.hero.card.id == id) return index
        index++
    }
    if (state.quietToday) index++
    for (section in state.sections) {
        index++ // the section's own heading
        val at = section.cards.indexOfFirst { it.id == id }
        if (at >= 0) return index + at
        index += section.cards.size
    }
    return null
}

/**
 * The flipped set with [id] shown *open*, whatever the mode is — what a save asks for, so the
 * words are there to read back and the pencil is one tap away.
 *
 * The flipped set holds the exceptions to the mode ([dev.rwilco.ui.home.HomeViewModel.flippedCards]),
 * so "open" is which side of it the card belongs on: an exception while the list is folded away,
 * and no exception while it is not. Pure, because it is the one bit of that state with a rule
 * in it rather than a toggle.
 */
fun shownOpen(flipped: Set<String>, id: String, compact: Boolean): Set<String> =
    if (compact) flipped + id else flipped - id

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
    /** Or the place it was pushed away to: "cuando llegue a casa". The two are never both set. */
    val snoozedToPlace: Trigger.Location? = null,
    /**
     * For a card under "Vencidos", the moment that got away ([dev.rwilco.model.HomeEntry.missedAt]),
     * so the card can say "debía sonar hace 3 h". The rows under it go on describing the rule
     * ("09:00 · lunes") exactly as a future card's do, which is why the section heading was the
     * only thing telling the two apart.
     */
    val missedAt: Instant? = null,
    /**
     * The tag whose colour runs down the card's edge, or null for a reminder with no tags.
     *
     * It was the family of whatever fires next, which was the honest reading and the wrong one
     * to look at: on a real list nearly everything next is a clock, so nearly every card came
     * out the same blue and the rhythm the band exists for never appeared. A tag is what
     * actually varies between one card and the next — and it is the person's own word for the
     * thing, which is a better reason to give something a colour than the shape of its trigger.
     * The first tag, the one the footer reads first; nothing for an untagged reminder, which is
     * a state Home already has a chip for.
     */
    val railTag: String? = null,
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
    /**
     * The moment "saltar la próxima" would let pass, or null where there is nothing to skip.
     *
     * The act itself is not new: a "hecho" given to a recurring reminder that is not waiting
     * for an answer already spends its next round ([momentDealtWith]). What was missing was
     * the name — the only way to miss one round of "cada día" was to pause it and remember to
     * come back. Only a reminder that comes back has a next one to skip; one that is ringing
     * has an answer owed first, and that answer is "hecho".
     */
    val skipsMoment: Instant? = null,
    /**
     * Whether "posponer" is an answer this card can give from Home.
     *
     * Only where it *is* an answer: the reminder rang and nobody has dealt with it since
     * ([awaitingAnswer]), or it is already put off and the question is "until when, then". A
     * card whose moment is still ahead gets no offer — a snooze outranks every rule and is spent
     * on its own, so putting a future reminder off to before its moment rings it twice; moving
     * a moment that has not come is editing, and the card opens the editor.
     */
    val snoozeOffered: Boolean = false,
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
    fun card(reminder: Reminder, missedAt: Instant? = null): ReminderCardUi {
        val standings = reminder.ruleStandings(now, zone, dayStart, shape) { index -> inside(reminder.id, index) }
        val circles = reminder.watchedCircles(now, zone, defaultTime, shape, dayStart)
        val rows = reminder.rules.mapIndexed { index, rule ->
            // Under "a la vez" the row says when the folded rule next holds, which is what
            // will ring; a fold of two moments never does, and the row says nothing.
            val next = reminder.ruleInSet(index, shape)?.let { nextFireOfRule(it, reminder.id, now, zone, defaultTime, shape) }
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
            railTag = reminder.tags.firstOrNull(),
            paused = reminder.status == Status.PAUSED,
            // A paused reminder rings at no moment at all, so a snooze on it is not news.
            snoozedUntil = reminder.snoozedUntil?.takeIf { it > now && reminder.status == Status.ACTIVE },
            snoozedToPlace = reminder.snoozedToPlace?.takeIf { reminder.status == Status.ACTIVE },
            missedAt = missedAt,
            match = reminder.ruleMatch.takeIf { reminder.rules.size > 1 },
            recurrence = reminder.recurrence.takeIf { it.isAnchored },
            snoozeOffered = reminder.awaitingAnswer(now) ||
                (reminder.status == Status.ACTIVE && (reminder.snoozedUntil?.let { it > now } == true || reminder.snoozedToPlace != null)),
            skipsMoment = if (reminder.status == Status.ACTIVE && reminder.recurrence != Recurrence.None) {
                reminder.momentDealtWith(now, zone, defaultTime, dayStart, shape)
            } else {
                null
            },
        )
    }
    val today = now.atZone(zone).toLocalDate()
    val heroToday = groups.hero?.entry?.wake?.at?.atZone(zone)?.toLocalDate() == today
    val busySections = groups.sections.keys.any { it == Section.TODAY || it == Section.OVERDUE }
    return HomeUiState(
        loaded = true,
        quietToday = (groups.hero != null || groups.sections.isNotEmpty()) && !heroToday && !busySections && filter == null,
        hero = groups.hero?.let { hero ->
            HeroUi(
                card = card(hero.entry.reminder),
                at = hero.entry.wake!!.at,
                snoozed = (hero.entry.next as? NextFire.Scheduled)?.snoozed == true,
                atEarliest = hero.atEarliest,
            )
        },
        sections = groups.sections.map { (section, entries) -> SectionUi(section, entries.map { card(it.reminder, it.missedAt) }) },
        tags = tags,
        selectedTag = filter,
        defaultTime = defaultTime,
        dayShape = shape,
    )
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

    /** [done] when it is one already dealt with: found all the same, and said so on the row. */
    data class OfReminder(val id: String, val text: String, val tags: List<String>, val done: Boolean = false) : SearchHitUi {
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
            is SearchHit.OfReminder -> SearchHitUi.OfReminder(hit.reminder.id, hit.reminder.text, hit.reminder.tags, done = hit.reminder.status == Status.DONE)
            is SearchHit.OfTag -> SearchHitUi.OfTag(hit.tag, hit.count)
        }
    },
)
