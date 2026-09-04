package dev.rwilco.ui.home

import dev.rwilco.model.momentDealtWith
import dev.rwilco.model.Period
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.Reminder
import dev.rwilco.model.Section
import dev.rwilco.model.Status
import dev.rwilco.model.TagFilter
import dev.rwilco.model.Presence
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.model.TriggerFamily
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import dev.rwilco.model.Deadline
import dev.rwilco.model.RuleMatch

class HomeStateTest {

    private val zone = ZoneId.of("Europe/Madrid")
    private val now: Instant = LocalDateTime.of(2026, 8, 27, 15, 0).atZone(zone).toInstant()
    private val defaultTime = LocalTime.of(9, 0)

    private fun reminder(id: String, vararg triggers: Trigger, tags: List<String> = emptyList(), status: Status = Status.ACTIVE) =
        Reminder(id = id, text = "text $id", tags = tags, rules = triggers.map(::TriggerRule), status = status, createdAt = now, updatedAt = now)

    private val soon = reminder("soon", Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 16, 0)), tags = listOf("casa"))
    private val place = reminder("place", Trigger.Location(40.4, -3.7, 200, Presence.INSIDE, "Casa"), tags = listOf("casa"))
    private val random = reminder("random", Trigger.Random(2, Period.DAY, LocalTime.of(10, 0), LocalTime.of(20, 0), emptySet()), tags = listOf("salud"))
    private val paused = reminder("paused", Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 16, 0)), status = Status.PAUSED)

    @Test
    fun `the card's colour band is its first tag, and nothing without one`() {
        // The band was the family of whatever fires next, which came out blue on nearly every
        // card because nearly everything next is a clock. A tag is what actually varies.
        val filed = reminder("filed", Trigger.AtDateTime(LocalDateTime.of(2026, 8, 29, 9, 0)), tags = listOf("casa", "salud"))
        val unfiled = reminder("unfiled", Trigger.AtDateTime(LocalDateTime.of(2026, 8, 29, 10, 0)))
        val state = buildHomeState(listOf(filed, unfiled), defaultTime, now, zone, selectedTag = null)
        val cards = (listOfNotNull(state.hero?.card) + state.sections.flatMap { it.cards }).associateBy { it.id }
        assertEquals("casa", cards.getValue("filed").railTag, "the first tag, the one the footer reads first")
        assertNull(cards.getValue("unfiled").railTag)
    }

    @Test
    fun `the card carries the deadline and when the round runs out, where they mean anything`() {
        val window = Deadline.Window(LocalTime.of(18, 0), LocalTime.of(22, 0))
        val close = LocalDateTime.of(2026, 8, 27, 22, 0).atZone(zone).toInstant()
        val bounded = reminder("bounded", Trigger.TimeOfDay(LocalTime.of(20, 0)), Trigger.Location(40.4, -3.7, 200, Presence.INSIDE, "Casa"))
            .copy(ruleMatch = RuleMatch.ALL, deadline = window, expiresAt = close)
        fun card(reminder: Reminder): ReminderCardUi {
            val state = buildHomeState(listOf(reminder), defaultTime, now, zone, selectedTag = null)
            return (listOfNotNull(state.hero?.card) + state.sections.flatMap { it.cards }).first { it.id == reminder.id }
        }
        assertEquals(window, card(bounded).deadline)
        assertEquals(close, card(bounded).expiresAt)
        assertNull(card(bounded.copy(ruleMatch = RuleMatch.ANY)).deadline, "cualquiera has nothing to give up on")
        assertNull(card(bounded.copy(rules = bounded.rules.take(1))).deadline, "one rule is not a set")
        assertNull(card(bounded.copy(status = Status.PAUSED)).expiresAt, "paused rings at no moment, and runs out at none")
        assertEquals(window, card(bounded.copy(status = Status.PAUSED)).deadline)
    }

    @Test
    fun `home says when there is nothing for today, and only then`() {
        val nextWeek = reminder("later", Trigger.AtDateTime(LocalDateTime.of(2026, 9, 10, 9, 0)))
        assertTrue(buildHomeState(listOf(nextWeek), defaultTime, now, zone, selectedTag = null).quietToday, "a list with nothing today")
        assertFalse(buildHomeState(listOf(nextWeek, soon), defaultTime, now, zone, selectedTag = null).quietToday, "the hero is today")
        val missed = reminder("missed", Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 12, 0)))
        assertFalse(buildHomeState(listOf(nextWeek, missed), defaultTime, now, zone, selectedTag = null).quietToday, "something is overdue")
        assertFalse(buildHomeState(emptyList(), defaultTime, now, zone, selectedTag = null).quietToday, "nothing at all is the empty state's job")
        assertFalse(buildHomeState(listOf(nextWeek), defaultTime, now, zone, selectedTag = TagFilter.Untagged).quietToday, "a filter is a different question")
        // And the row counts as a row for the scroll arithmetic: one more than without it.
        val state = buildHomeState(listOf(nextWeek), defaultTime, now, zone, selectedTag = null)
        val without = homeCardIndex(state.copy(quietToday = false), "later", strip = false, pinned = false)!!
        assertEquals(without + 1, homeCardIndex(state, "later", strip = false, pinned = false), "the quiet row pushes the card down by one")
    }

    @Test
    fun `an overdue card says how long ago its moment went, and no other card does`() {
        // Under "Vencidos" every row went on describing the rule exactly as a future card's
        // rows do, so the heading was the only thing telling the two apart.
        val missed = reminder("missed", Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 12, 0)))
            .copy(lastFiredAt = LocalDateTime.of(2026, 8, 27, 12, 0).atZone(zone).toInstant())
        val state = buildHomeState(listOf(missed, soon, paused), defaultTime, now, zone, selectedTag = null)
        val overdue = state.sections.single { it.section == Section.OVERDUE }.cards.single()
        assertEquals(LocalDateTime.of(2026, 8, 27, 12, 0).atZone(zone).toInstant(), overdue.missedAt)
        assertNull(state.hero?.card?.missedAt, "the one that glows has a moment ahead of it")
        assertNull(state.sections.single { it.section == Section.PAUSED }.cards.single().missedAt, "a paused card has missed nothing")
    }

    @Test
    fun `a postponed reminder says so on its card, not only when it is the one that glows`() {
        // The one that sent this looking: "los viernes cada dos semanas", put off for two hours.
        // Something sooner takes the glowing card, so this one is a plain card in a section —
        // and a plain card knew nothing about a snooze, so it went on saying "in a fortnight".
        val fortnightly = Reminder(
            id = "fortnightly",
            text = "Sacar el contenedor",
            recurrence = Recurrence.Calendar(
                Trigger.Repeat(
                    startsOn = LocalDate.of(2026, 8, 28),
                    every = 2,
                    unit = dev.rwilco.model.RepeatUnit.WEEK,
                    time = LocalTime.of(9, 0),
                    days = setOf(DayOfWeek.FRIDAY),
                ),
            ),
            status = Status.ACTIVE,
            createdAt = now,
            updatedAt = now,
            snoozedUntil = now.plusSeconds(2 * 3600),
        )
        val state = buildHomeState(listOf(fortnightly, soon), defaultTime, now, zone, selectedTag = null)
        assertEquals("soon", state.hero?.card?.id)
        val card = state.sections.flatMap { it.cards }.first { it.id == "fortnightly" }
        assertEquals(now.plusSeconds(2 * 3600), card.snoozedUntil)
    }

    @Test
    fun `a card put off until a place says so, and offers to change its mind`() {
        val door = Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa", onCrossing = true)
        val waiting = soon.copy(snoozedToPlace = door)
        val state = buildHomeState(listOf(waiting, place), defaultTime, now, zone, selectedTag = null)
        assertNull(state.hero, "a place with no floor is never the hero")
        val card = state.sections.flatMap { it.cards }.first { it.id == "soon" }
        assertEquals(door, card.snoozedToPlace)
        assertNull(card.snoozedUntil)
        assertTrue(card.snoozeOffered, "already put off: the question is where to, then")
        // Paused, it waits at no door the card need mention.
        val held = buildHomeState(listOf(waiting.copy(status = Status.PAUSED)), defaultTime, now, zone, selectedTag = null)
        assertNull(held.sections.flatMap { it.cards }.single().snoozedToPlace)
    }

    @Test
    fun `a snooze that has come and gone is not on the card any more`() {
        val past = reminder("past", Trigger.AtDateTime(LocalDateTime.of(2026, 8, 29, 16, 0)))
            .copy(snoozedUntil = now.minusSeconds(60))
        // Paused is the other one: it rings at no moment at all, so a snooze on it says nothing.
        val held = reminder("held", Trigger.AtDateTime(LocalDateTime.of(2026, 8, 29, 16, 0)), status = Status.PAUSED)
            .copy(snoozedUntil = now.plusSeconds(3600))
        val state = buildHomeState(listOf(past, held), defaultTime, now, zone, selectedTag = null)
        val cards = (listOfNotNull(state.hero?.card) + state.sections.flatMap { it.cards }).associateBy { it.id }
        assertNull(cards.getValue("past").snoozedUntil)
        assertNull(cards.getValue("held").snoozedUntil)
    }

    @Test
    fun `a row says whether its circle is costing anything`() {
        // The shape is the battery and the colour is the answer, so the row has to carry both.
        // "En casa, a la vez que de 20 a 22" at three in the afternoon: the circle cannot ring
        // for five hours and is not worth a position until two before, so it rides along on
        // whatever the others pay for — and the card says so.
        val gated = Reminder(
            id = "gated",
            text = "Regar",
            rules = listOf(
                TriggerRule(Trigger.Location(40.4, -3.7, 200, Presence.INSIDE, "Casa")),
                TriggerRule(Trigger.Interval(LocalTime.of(20, 0), LocalTime.of(22, 0))),
            ),
            ruleMatch = dev.rwilco.model.RuleMatch.TOGETHER,
            status = Status.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
        val state = buildHomeState(listOf(gated, place), defaultTime, now, zone, selectedTag = null)
        val cards = listOfNotNull(state.hero?.card) + state.sections.flatMap { it.cards }
        val rows = cards.first { it.id == "gated" }.triggers
        assertFalse(rows[0].watched, "a circle five hours from being able to ring is not worth a position")
        assertTrue(rows[1].watched, "and nothing but a place is ever not watched")
        // A place with nothing in front of it is watched, and the row says nothing about
        // battery it does not have to.
        assertTrue(cards.first { it.id == "place" }.triggers[0].watched)
    }

    @Test
    fun `the hero and the sections come out as cards with per-trigger moments`() {
        val state = buildHomeState(listOf(soon, place, random, paused), defaultTime, now, zone, selectedTag = null)
        assertTrue(state.loaded)
        assertFalse(state.empty)
        assertEquals("soon", state.hero!!.card.id)
        assertEquals(LocalDateTime.of(2026, 8, 27, 16, 0).atZone(zone).toInstant(), state.hero!!.at)
        assertEquals(listOf(Section.WHENEVER, Section.PAUSED), state.sections.map { it.section })
        val whenever = state.sections[0].cards
        assertEquals(listOf("random", "place"), whenever.map { it.id }, "a draw with a moment sorts before a place")
        val randomRow = whenever[0].triggers.single()
        assertEquals(TriggerFamily.CHANCE, randomRow.family)
        assertNull(randomRow.nextAt)
        assertNotNull(randomRow.window)
        val placeRow = whenever[1].triggers.single()
        assertEquals(TriggerFamily.PLACE, placeRow.family)
        assertNull(placeRow.nextAt)
        assertNull(placeRow.window)
        assertTrue(state.sections[1].cards.single().paused)
        // The tags in use, and then the app's own three — one reminder here carries no tag, one
        // is paused and one rings at a place, so all three have something to show.
        assertEquals(
            listOf(TagFilter.Named("casa"), TagFilter.Named("salud"), TagFilter.Untagged, TagFilter.Paused, TagFilter.Place),
            state.tags,
        )
    }

    @Test
    fun `a tag filter narrows the cards and a stale filter is dropped`() {
        val filtered = buildHomeState(listOf(soon, place, random), defaultTime, now, zone, selectedTag = TagFilter.Named("casa"))
        assertEquals(TagFilter.Named("casa"), filtered.selectedTag)
        assertEquals("soon", filtered.hero!!.card.id)
        assertEquals(listOf("place"), filtered.sections.single().cards.map { it.id })

        val stale = buildHomeState(listOf(soon), defaultTime, now, zone, selectedTag = TagFilter.Named("trabajo"))
        assertNull(stale.selectedTag, "a filter on a tag nobody has anymore is silently cleared")
        assertEquals("soon", stale.hero!!.card.id)
    }

    @Test
    fun `no open reminders is the empty state, a filter that matches nothing is not`() {
        val done = reminder("done", Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 16, 0)), status = Status.DONE)
        assertTrue(buildHomeState(listOf(done), defaultTime, now, zone, selectedTag = null).empty)
        assertTrue(buildHomeState(emptyList(), defaultTime, now, zone, selectedTag = null).empty)
        val filtered = buildHomeState(listOf(soon, random), defaultTime, now, zone, selectedTag = TagFilter.Named("salud"))
        assertFalse(filtered.empty)
    }

    @Test
    fun `a recurrence that works out its own moments gets a row of its own`() {
        // "Cada 6 h" carries no trigger at all, so the card had nothing to show and said nothing
        // whatsoever about when it rings — a shape that was real, armed and invisible.
        val pills = Reminder(
            id = "pills",
            text = "Tomar la pastilla",
            recurrence = Recurrence.After(6, RecurrenceUnit.HOURS),
            createdAt = now.minusSeconds(3600),
            updatedAt = now.minusSeconds(3600),
        )

        val card = cardFor(pills)
        assertTrue(card.triggers.isEmpty(), "there is no trigger to show")
        assertEquals(Recurrence.After(6, RecurrenceUnit.HOURS), card.recurrence)
    }

    @Test
    fun `a recurrence the triggers already answer for is not said twice`() {
        // ByTrigger IS the repeating trigger on the card above it; a second row saying so is
        // noise. And a reminder that does not repeat has nothing to say either.
        val weekly = Trigger.AtTime(LocalTime.of(9, 0), setOf(DayOfWeek.MONDAY))
        val base = Reminder(id = "r", text = "Regar", rules = listOf(TriggerRule(weekly)), createdAt = now, updatedAt = now)

        assertNull(cardFor(base.copy(recurrence = Recurrence.ByTrigger)).recurrence)
        assertNull(cardFor(base).recurrence)
        // One that works out its own moments shows up even next to a trigger: it is the answer
        // to a different question, and the trigger cannot speak for it.
        assertEquals(
            Recurrence.After(1, RecurrenceUnit.DAYS),
            cardFor(base.copy(recurrence = Recurrence.After(1, RecurrenceUnit.DAYS))).recurrence,
        )
    }

    private fun cardFor(reminder: Reminder): ReminderCardUi {
        val state = buildHomeState(listOf(reminder), LocalTime.of(9, 0), now, zone, selectedTag = null)
        return state.hero?.card ?: state.sections.first().cards.first()
    }

    @Test
    fun `before the first emission the screen is neither loaded nor empty`() {
        val blank = HomeUiState()
        assertFalse(blank.loaded)
        assertFalse(blank.empty)
        val nothing = buildHomeState(emptyList(), defaultTime, now, zone, selectedTag = null)
        assertTrue(nothing.loaded)
        assertTrue(nothing.empty)
    }

    @Test
    fun `posponer is offered only where it is an answer`() {
        val rang = soon.copy(id = "rang", lastFiredAt = now.minusSeconds(600))
        val putOff = soon.copy(id = "putOff", snoozedUntil = now.plusSeconds(3600))
        val spentSnooze = soon.copy(id = "spentSnooze", lastFiredAt = now.minusSeconds(600), lastDealtAt = now.minusSeconds(300))
        val state = buildHomeState(listOf(soon, rang, putOff, spentSnooze, paused), defaultTime, now, zone, selectedTag = null)
        val cards = (listOfNotNull(state.hero?.card) + state.sections.flatMap { it.cards }).associateBy { it.id }
        assertFalse(cards.getValue("soon").snoozeOffered, "a moment still ahead is edited, not put off")
        assertTrue(cards.getValue("rang").snoozeOffered, "rang and unanswered: posponer is the answer")
        assertTrue(cards.getValue("putOff").snoozeOffered, "already put off: until when, then")
        assertFalse(cards.getValue("spentSnooze").snoozeOffered, "dealt with since it rang")
        assertFalse(cards.getValue("paused").snoozeOffered)
    }

    @Test
    fun `only a reminder that comes back, and is not ringing, has a next one to skip`() {
        val daily = reminder("daily", Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 20, 0))).copy(recurrence = Recurrence.After(1, RecurrenceUnit.DAYS))
        val once = reminder("once", Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 20, 0)))
        // Rang an hour ago and nobody answered: the answer owed is "hecho", not a skip.
        val ringing = daily.copy(id = "ringing", lastFiredAt = now.minusSeconds(3_600), armedFor = now.minusSeconds(3_600))
        val state = buildHomeState(listOf(daily, once, ringing), defaultTime, now, zone, selectedTag = null)
        val cards = (listOfNotNull(state.hero?.card) + state.sections.flatMap { it.cards }).associateBy { it.id }
        assertEquals(LocalDateTime.of(2026, 8, 27, 20, 0).atZone(zone).toInstant(), cards.getValue("daily").skipsMoment)
        assertNull(cards.getValue("once").skipsMoment, "a one-off has no next one")
        assertNull(cards.getValue("ringing").skipsMoment, "a ring waiting for an answer is not skipped, it is answered")
    }
    /** A card is only an id here: nothing else takes part in where it sits. */
    private fun card(id: String) = ReminderCardUi(
        id = id,
        text = id,
        tags = emptyList(),
        triggers = emptyList(),
        actions = emptySet(),
        paused = false,
    )

    @Test
    fun `the index of a card mirrors the order the list is built in`() {
        // This is the one function in Home that is a copy of something else — the order of the
        // LazyColumn — so it is the one worth pinning. A drift here is a save that scrolls to
        // the wrong reminder, which is worse than not scrolling at all.
        val a = card("a")
        val b = card("b")
        val c = card("c")
        val state = HomeUiState(
            loaded = true,
            hero = null,
            sections = listOf(
                SectionUi(Section.TODAY, listOf(a, b)),
                SectionUi(Section.LATER, listOf(c)),
            ),
            tags = emptyList(),
        )
        // Nothing above the list: heading, a, b, heading, c.
        assertEquals(1, homeCardIndex(state, "a", strip = false, pinned = false))
        assertEquals(2, homeCardIndex(state, "b", strip = false, pinned = false))
        assertEquals(4, homeCardIndex(state, "c", strip = false, pinned = false))
        assertNull(homeCardIndex(state, "nobody", strip = false, pinned = false))

        // Each thing above the list pushes everything down by exactly one.
        assertEquals(2, homeCardIndex(state, "a", strip = true, pinned = false))
        assertEquals(2, homeCardIndex(state, "a", strip = false, pinned = true))
        assertEquals(3, homeCardIndex(state, "a", strip = true, pinned = true))
        val withTags = state.copy(tags = listOf(TagFilter.Untagged))
        assertEquals(2, homeCardIndex(withTags, "a", strip = false, pinned = false))
        // The "deleted · undo" row sits under the tags and above the cards while it lasts.
        assertEquals(3, homeCardIndex(withTags, "a", strip = false, pinned = false, undoRow = true))
        // The hero is lifted out of its section but it is still a row in the same column, and a
        // list scrolled well down has it off the top — which is where an edit that gives a
        // reminder the soonest moment on the phone sends it.
        val withHero = withTags.copy(hero = HeroUi(card("hero"), Instant.EPOCH))
        assertEquals(1, homeCardIndex(withHero, "hero", strip = false, pinned = false))
        assertEquals(3, homeCardIndex(withHero, "a", strip = false, pinned = false))
    }

    @Test
    fun `a reminder resting between two rounds says the round it will ring, not the one it just spent`() {
        // The complaint from the phone (0.74.0): "los lunes, y vuelve cada semana", swiped done
        // on a Thursday. The swipe was right — the reminder stays open and rings the Monday
        // after — but every card row was asked from *now*, so the card went on naming the
        // Monday that had just been dealt through, which is the one Monday it will not ring on.
        // The class's own "now" is Thursday 27 August 2026, so the Monday to come is the 31st.
        val monday = LocalDate.of(2026, 8, 31)
        val weekly = Reminder(
            id = "bins",
            text = "sacar el cubo",
            rules = listOf(TriggerRule(Trigger.Weekday(setOf(DayOfWeek.MONDAY)))),
            recurrence = Recurrence.After(1, RecurrenceUnit.WEEKS),
            createdAt = now,
            updatedAt = now,
        )
        val before = buildHomeState(listOf(weekly), defaultTime, now, zone, selectedTag = null)
        assertEquals(monday, before.hero?.at?.atZone(zone)?.toLocalDate(), "it fires on the Monday to come")
        assertNull(before.hero?.card?.returnsAt, "nothing is resting yet, and the rules say it themselves")

        // Dealt with on the Thursday, exactly as ReminderFiring.dismiss leaves the row.
        val spent = weekly.momentDealtWith(now, zone, defaultTime)!!
        val dealt = weekly.copy(lastDealtAt = now, dealtThrough = spent)
        val after = buildHomeState(listOf(dealt), defaultTime, now, zone, selectedTag = null)
        val card = after.sections.flatMap { it.cards }.single { it.id == "bins" }
        assertEquals(
            monday.plusWeeks(1),
            card.triggers.single().nextAt?.atZone(zone)?.toLocalDate(),
            "the row names the Monday it will ring on",
        )
        assertEquals(
            monday.plusWeeks(1),
            card.returnsAt?.atZone(zone)?.toLocalDate(),
            "and the recurrence row says when it comes back, because past a week it is not the hero",
        )
    }

    @Test
    fun `a circle finer than most of this phone's positions is marked on the rule it belongs to`() {
        // The editor says it while a radius is being chosen, which reaches the next place
        // somebody writes and none of the ones already written — and those are the ones quietly
        // not ringing. So the card says it on the rule, from what the watch's own looks carry.
        val home = reminder("home", Trigger.Location(40.4, -3.7, 50, Presence.INSIDE, "casa"))
        val wide = reminder("wide", Trigger.Location(40.4, -3.7, 200, Presence.INSIDE, "club"))
        fun rowOf(id: String, accuracy: Int?) = buildHomeState(listOf(home, wide), defaultTime, now, zone, selectedTag = null, fixAccuracyM = accuracy)
            .let { state -> (listOfNotNull(state.hero?.card) + state.sections.flatMap { it.cards }).single { it.id == id } }
            .triggers.single()

        // The number travels with the mark: the words are about how often, not whether, and
        // "±70 m" is what makes that readable.
        assertEquals(70, rowOf("home", 70).doubtM, "fifty metres is inside a seventy-metre doubt")
        assertNull(rowOf("wide", 70).doubtM, "two hundred is not")
        assertNull(rowOf("home", null).doubtM, "and nothing is said about a phone the watch has barely looked with")
        assertNull(rowOf("home", 30).doubtM, "nor about one whose positions are tighter than the circle")
    }

    @Test
    fun `a card shown open is on whichever side of the flipped set the mode is not`() {
        // What a save asks for: the card it just wrote, open, whatever the list is doing.
        assertEquals(setOf("new"), shownOpen(emptySet(), "new", compact = true), "folded list: an exception")
        assertEquals(emptySet<String>(), shownOpen(emptySet(), "new", compact = false), "open list: nothing to say")
        // And it is not a toggle: asking twice, or asking about a card already on the wrong
        // side of the mode, still leaves it open.
        assertEquals(setOf("new"), shownOpen(setOf("new"), "new", compact = true))
        assertEquals(setOf("other"), shownOpen(setOf("new", "other"), "new", compact = false), "a card folded by hand opens, and nobody else moves")
    }

}
