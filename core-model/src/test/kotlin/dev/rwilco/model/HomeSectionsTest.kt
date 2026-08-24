package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.reminder
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class HomeSectionsTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        Trigger.AtDateTime(LocalDateTime.of(year, month, day, hour, minute))

    private fun scheduled(trigger: Trigger.AtDateTime) =
        NextFire.Scheduled(trigger.at.atZone(zone).toInstant(), trigger)

    @Test
    fun `sections follow the local calendar with a rolling week`() {
        val section = { t: Trigger.AtDateTime -> sectionOf(scheduled(t), Status.ACTIVE, hasRules = true, now, zone) }
        assertEquals(Section.TODAY, section(at(2026, 8, 27, 23, 59)))
        assertEquals(Section.TOMORROW, section(at(2026, 8, 28, 0, 0)))
        assertEquals(Section.TOMORROW, section(at(2026, 8, 28, 23, 59)))
        assertEquals(Section.THIS_WEEK, section(at(2026, 8, 29, 0, 0)))
        assertEquals(Section.THIS_WEEK, section(at(2026, 9, 2, 12, 0)), "today + 6 is still this week")
        assertEquals(Section.LATER, section(at(2026, 9, 3, 12, 0)), "today + 7 is later")
    }

    @Test
    fun `whenever, paused and overdue are their own sections`() {
        val place = Trigger.Location(40.4, -3.7, 200, Transition.ENTER, "Casa")
        assertEquals(Section.WHENEVER, sectionOf(NextFire.WhenAt(place), Status.ACTIVE, hasRules = true, now, zone))
        val random = Trigger.Random(1, Period.DAY, LocalTime.of(10, 0), LocalTime.of(20, 0), emptySet())
        assertEquals(Section.WHENEVER, sectionOf(NextFire.Sometime(now, now, now, random), Status.ACTIVE, hasRules = true, now, zone))
        assertEquals(Section.PAUSED, sectionOf(scheduled(at(2026, 8, 27, 23, 0)), Status.PAUSED, hasRules = true, now, zone))
        assertEquals(Section.OVERDUE, sectionOf(null, Status.ACTIVE, hasRules = true, now, zone))
        // Nothing to fire and nothing that ever was: kept, not missed.
        assertEquals(Section.NO_TRIGGER, sectionOf(null, Status.ACTIVE, hasRules = false, now, zone))
    }

    @Test
    fun `the hero is the earliest definite moment and leaves its section`() {
        val soon = reminder(at(2026, 8, 27, 16, 0), id = "soon")
        val tonight = reminder(at(2026, 8, 27, 21, 0), id = "tonight")
        val random = reminder(
            Trigger.Random(1, Period.DAY, LocalTime.of(15, 30), LocalTime.of(15, 45), emptySet()),
            id = "random",
        )
        val groups = groupForHome(listOf(tonight, random, soon), now, zone, defaultTime)
        assertEquals("soon", groups.hero!!.reminder.id)
        assertEquals(listOf("tonight"), groups.sections[Section.TODAY]!!.map { it.reminder.id })
        assertEquals(listOf("random"), groups.sections[Section.WHENEVER]!!.map { it.reminder.id })
    }

    @Test
    fun `a random draw is never the hero even when it is the only thing`() {
        val random = reminder(Trigger.Random(1, Period.DAY, LocalTime.of(15, 30), LocalTime.of(20, 0), emptySet()))
        val groups = groupForHome(listOf(random), now, zone, defaultTime)
        assertNull(groups.hero)
        assertEquals(setOf(Section.WHENEVER), groups.sections.keys)
    }

    @Test
    fun `done reminders are left out and the tag filter keeps matching ones case-insensitively`() {
        val a = reminder(at(2026, 8, 27, 18, 0), id = "a", tags = listOf("Compra"))
        val b = reminder(at(2026, 8, 27, 19, 0), id = "b", tags = listOf("casa"))
        val done = reminder(at(2026, 8, 27, 20, 0), id = "done", tags = listOf("compra"), status = Status.DONE)
        val groups = groupForHome(listOf(a, b, done), now, zone, defaultTime, tagFilter = "compra")
        assertEquals("a", groups.hero!!.reminder.id)
        assertTrue(groups.sections.isEmpty())
        val unfiltered = groupForHome(listOf(a, b, done), now, zone, defaultTime)
        assertEquals(listOf("b"), unfiltered.sections[Section.TODAY]!!.map { it.reminder.id })
    }

    @Test
    fun `sections come out in display order and sorted by time inside`() {
        val later = reminder(at(2026, 9, 20, 9, 0), id = "later")
        val paused = reminder(at(2026, 8, 27, 20, 0), id = "paused", status = Status.PAUSED)
        val overdue = reminder(at(2026, 8, 27, 8, 0), id = "overdue")
        val week2 = reminder(at(2026, 8, 30, 12, 0), id = "week2")
        val week1 = reminder(at(2026, 8, 29, 12, 0), id = "week1")
        val hero = reminder(at(2026, 8, 27, 16, 0), id = "hero")
        val groups = groupForHome(listOf(later, paused, overdue, week2, week1, hero), now, zone, defaultTime)
        assertEquals(listOf(Section.OVERDUE, Section.THIS_WEEK, Section.LATER, Section.PAUSED), groups.sections.keys.toList())
        assertEquals(listOf("week1", "week2"), groups.sections[Section.THIS_WEEK]!!.map { it.reminder.id })
    }

    @Test
    fun `tags in use are counted across open reminders, most used first`() {
        val reminders = listOf(
            reminder(id = "1", tags = listOf("casa", "Compra")),
            reminder(id = "2", tags = listOf("compra")),
            reminder(id = "3", tags = listOf("Trabajo")),
            reminder(id = "4", tags = listOf("viejo"), status = Status.DONE),
        )
        assertEquals(listOf("Compra", "casa", "Trabajo"), tagsInUse(reminders))
    }

    @Test
    fun `a repeating reminder is grouped by its next occurrence`() {
        val weekly = reminder(Trigger.AtTime(LocalTime.of(7, 30), setOf(DayOfWeek.MONDAY)), id = "weekly")
        val groups = groupForHome(listOf(weekly), now, zone, defaultTime)
        assertEquals("weekly", groups.hero!!.reminder.id)
        assertEquals(local(2026, 8, 31, 7, 30), (groups.hero!!.next as NextFire.Scheduled).at)
        assertEquals(LocalDate.of(2026, 8, 31), (groups.hero!!.next as NextFire.Scheduled).at.atZone(zone).toLocalDate())
    }
}
