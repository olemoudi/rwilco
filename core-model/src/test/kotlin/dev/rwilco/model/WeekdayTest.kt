package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * The days of the week on their own — the half of "los viernes a las 14:00" that used to be
 * welded to the other one.
 *
 * Most of this is about what it does in company, because that is what it is for: it is the
 * *state* a set lands inside, so "los viernes" beside "a las 14:00" under "a la vez" is the
 * sentence it reads as and nothing else.
 */
class WeekdayTest {

    private val fridays = Trigger.Weekday(setOf(DayOfWeek.FRIDAY))

    /** Thursday 2026-08-27 at 15:00, the shared clock. */
    private val now = Fixtures.now

    @Test
    fun `it is a state, true for the whole of an allowed day`() {
        assertFalse(fridays.isMoment)
        assertEquals(Condition.OnDays(setOf(DayOfWeek.FRIDAY)), fridays.asState())
        // The whole of Friday, not the hours it happens to ring in: "los viernes" means the
        // whole Friday to anybody who says it.
        val state = fridays.asState()!!
        assertTrue(state.holdsAt(local(2026, 8, 28, 2, 0), zone), "the small hours of Friday")
        assertTrue(state.holdsAt(local(2026, 8, 28, 23, 50), zone), "and the end of it")
        assertFalse(state.holdsAt(local(2026, 8, 29, 12, 0), zone), "but not the Saturday")
    }

    @Test
    fun `on its own it opens the next allowed day`() {
        // A shape that leaves the hour to the day, read exactly as a date with no hour is: the
        // stretch this person is up for, opened at its start.
        val next = nextFireOf(fridays, "r1", now, zone, defaultTime) as NextFire.Scheduled
        assertEquals(local(2026, 8, 28, 8, 0), next.at, "Friday, when this person is up")
        // Friday already under way: the next one is a week later, not this morning again.
        val after = nextFireOf(fridays, "r1", local(2026, 8, 28, 15, 0), zone, defaultTime) as NextFire.Scheduled
        assertEquals(local(2026, 9, 4, 8, 0), after.at)
    }

    @Test
    fun `an hour fence moves the opening rather than killing it`() {
        // The same treatment a day with no hour gets: the fences reach the opening, so "los
        // viernes, sólo de 16 a 17" opens at four rather than at breakfast and being rejected
        // every Friday for five years until the walk gives up and says "nunca".
        val rule = TriggerRule(fridays, listOf(Condition.TimeWindow(LocalTime.of(16, 0), LocalTime.of(17, 0))))
        val next = nextFireOfRule(rule, "r1", now, zone, defaultTime) as NextFire.Scheduled
        assertEquals(local(2026, 8, 28, 16, 0), next.at)
    }

    @Test
    fun `the days are the trigger, so none of them is nonsense`() {
        assertEquals(TriggerProblem.DAYS_EMPTY, problemOf(Trigger.Weekday(emptySet())))
        assertNull(problemOf(fridays))
        // Unlike a window or an hour, where an empty set is "every day" and perfectly sensible.
        assertNull(problemOf(Trigger.TimeOfDay(LocalTime.of(9, 0), emptySet())))
        // And it never fires either, which is what stops a bad one being armed for ever.
        assertNull(nextFireOf(Trigger.Weekday(emptySet()), "r1", now, zone, defaultTime))
    }

    @Test
    fun `beside an hour, at once, it is exactly "los viernes a las 14 00"`() {
        val reminder = Fixtures.reminder(
            Trigger.TimeOfDay(LocalTime.of(14, 0)),
            fridays,
        ).copy(ruleMatch = RuleMatch.TOGETHER)
        val next = nextFire(reminder, now, zone, defaultTime) as NextFire.Scheduled
        assertEquals(local(2026, 8, 28, 14, 0), next.at, "Friday at two, and no other day")
        // The Thursday two o'clock this clock has just gone past is not it, and neither is
        // Saturday's: from Friday evening the next is a week away.
        val later = nextFire(reminder, local(2026, 8, 28, 15, 0), zone, defaultTime) as NextFire.Scheduled
        assertEquals(local(2026, 9, 4, 14, 0), later.at)
    }

    @Test
    fun `it is one tile of its own, in the time family`() {
        assertEquals(TriggerKind.WEEKDAY, fridays.kind)
        assertEquals(TriggerFamily.TIME, fridays.family)
        assertTrue(TriggerKind.WEEKDAY in OFFERED_KINDS)
        assertEquals(setOf(DayOfWeek.FRIDAY), fridays.namedDays)
        // It names no hour anybody typed, which is what "justo el plazo" would have to adopt.
        assertNull(fridays.hourNamed)
        // It comes round again on its own, so it is never "ya ha pasado".
        assertFalse(fridays.isOneShot)
    }

    @Test
    fun `the discriminator is frozen, and so is the condition it folds to`() {
        val json = Json { classDiscriminator = "type"; encodeDefaults = true }
        assertEquals(
            """{"type":"weekday","days":["FRIDAY"]}""",
            json.encodeToString(Trigger.serializer(), fridays),
        )
        assertEquals(fridays, json.decodeFromString(Trigger.serializer(), """{"type":"weekday","days":["FRIDAY"]}"""))
        assertEquals(
            """{"type":"on_days","days":["FRIDAY"]}""",
            json.encodeToString(Condition.serializer(), Condition.OnDays(setOf(DayOfWeek.FRIDAY))),
        )
    }

    @Test
    fun `a build that cannot read it drops the rule, never the reminder`() {
        // The house rule: an unknown trigger is skipped and the reminder survives with what is
        // left. This is what an older build meets when it opens a phone written by this one.
        val raw = """[{"trigger":{"type":"weekday","days":["FRIDAY"]}},{"trigger":{"type":"countdown","minutes":30}}]"""
        val decoded = ReminderCodec.decodeRules(raw)
        assertEquals(2, decoded.size, "this build reads both")
        assertEquals(fridays, decoded.first().trigger)
    }
}
