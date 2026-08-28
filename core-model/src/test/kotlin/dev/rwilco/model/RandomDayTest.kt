package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.reminder
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * "At random during the day", for a date and for a recurrence.
 *
 * The whole point of the setting is that the day it lands on decides the window, so most of
 * this is the same question asked of a Tuesday, a Friday, a Saturday and a Sunday. The draw
 * itself only has to be inside its window and hold still; which minute it picks is nobody's
 * business, including this test's.
 */
class RandomDayTest {

    private val shape = DayShape(
        hours = AwakeHours(
            wake = LocalTime.of(8, 0),
            sleep = LocalTime.of(23, 30),
            weekendWake = LocalTime.of(10, 0),
            weekendSleep = LocalTime.of(1, 30),
        ),
        weekendFrom = DayOfWeek.FRIDAY,
        weekendFromTime = LocalTime.of(20, 30),
        weekendTo = DayOfWeek.SUNDAY,
        weekendToTime = LocalTime.of(22, 0),
    )

    private fun date(day: Int) = LocalDate.of(2026, 8, day)

    /** Long before any of these days, so nothing is filtered out for being in the past. */
    private val early: Instant = local(2026, 8, 20, 6, 0)

    private fun drawFor(trigger: Trigger, at: Instant = early, id: String = "r1"): Instant? =
        (nextFireOf(trigger, id, at, zone, defaultTime, shape) as? NextFire.Scheduled)?.at

    private fun assertInside(moment: Instant?, window: AwakeWindow) {
        val at = requireNotNull(moment) { "nothing was drawn at all" }
        val local = at.atZone(zone).toLocalDateTime()
        assertTrue(
            !local.isBefore(window.from) && local.isBefore(window.to),
            "$local is outside ${window.from}..${window.to}",
        )
    }

    // ---- a date drawn at random -------------------------------------------------------------

    @Test
    fun `a weekday is drawn from the weekday hours`() {
        assertInside(drawFor(Trigger.DayRandom(date(25))), shape.awakeOn(date(25)))
    }

    @Test
    fun `a saturday is drawn from the weekend hours`() {
        val window = shape.awakeOn(date(29))
        assertEquals(LocalDateTime.of(2026, 8, 29, 10, 0), window.from)
        assertInside(drawFor(Trigger.DayRandom(date(29))), window)
    }

    @Test
    fun `a friday can be drawn into the small hours of saturday`() {
        val window = shape.awakeOn(date(28))
        assertEquals(LocalDateTime.of(2026, 8, 29, 1, 30), window.to)
        assertInside(drawFor(Trigger.DayRandom(date(28))), window)
    }

    @Test
    fun `a sunday is put to bed at the weekday hour`() {
        val window = shape.awakeOn(date(30))
        assertEquals(LocalDateTime.of(2026, 8, 30, 23, 30), window.to)
        assertInside(drawFor(Trigger.DayRandom(date(30))), window)
    }

    @Test
    fun `the same day drawn twice is the same moment`() {
        val once = drawFor(Trigger.DayRandom(date(29)))
        val twice = drawFor(Trigger.DayRandom(date(29)), at = early.plusSeconds(3600))
        assertEquals(once, twice)
    }

    @Test
    fun `two reminders on the same day do not have to ring together`() {
        val one = drawFor(Trigger.DayRandom(date(29)), id = "one")
        val other = drawFor(Trigger.DayRandom(date(29)), id = "other")
        assertNotEquals(one, other)
    }

    @Test
    fun `a day already gone has nothing left to draw`() {
        assertNull(drawFor(Trigger.DayRandom(date(20)), at = now))
    }

    @Test
    fun `the hours somebody keeps move the moment`() {
        val nightOwl = shape.copy(hours = shape.hours.copy(wake = LocalTime.of(14, 0), sleep = LocalTime.of(23, 0)))
        val moment = (nextFireOf(Trigger.DayRandom(date(25)), "r1", early, zone, defaultTime, nightOwl) as NextFire.Scheduled).at
        assertTrue(moment.atZone(zone).toLocalTime() >= LocalTime.of(14, 0))
    }

    // ---- a recurrence drawn at random -------------------------------------------------------

    private fun weekly(days: Set<DayOfWeek>) = Trigger.Repeat(
        startsOn = date(24),
        unit = RepeatUnit.WEEK,
        time = null,
        days = days,
    )

    @Test
    fun `a recurrence with no hour draws one from each day it lands on`() {
        val everyDay = Trigger.Repeat(startsOn = date(24), unit = RepeatUnit.DAY, time = null)
        // Tuesday, Friday, Saturday and Sunday of the same week, each inside its own window.
        var at = local(2026, 8, 24, 6, 0)
        val moments = buildList {
            repeat(7) {
                val moment = (nextFireOf(everyDay, "r1", at, zone, defaultTime, shape) as NextFire.Scheduled).at
                add(moment)
                at = moment
            }
        }
        for (moment in moments) {
            val day = moment.atZone(zone).toLocalDate()
            val window = shape.awakeOn(day)
            // The moment belongs to the day whose window holds it, which for a Friday night
            // draw is the day before the date it reads as.
            val ownWindow = if (moment.atZone(zone).toLocalDateTime() < window.from) shape.awakeOn(day.minusDays(1)) else window
            assertInside(moment, ownWindow)
        }
    }

    @Test
    fun `a weekly saturday is drawn from the weekend and a weekly tuesday is not`() {
        // The two windows do not merely differ, they overlap the wrong way round: a Saturday
        // runs to half one on Sunday morning, so its draw can be *earlier* on the clock than a
        // Tuesday's and still be later in the day. The window is the thing to ask, not the hour.
        assertInside(drawFor(weekly(setOf(DayOfWeek.SATURDAY))), shape.awakeOn(date(29)))
        assertInside(drawFor(weekly(setOf(DayOfWeek.TUESDAY))), shape.awakeOn(date(25)))
        val tuesday = drawFor(weekly(setOf(DayOfWeek.TUESDAY)))!!.atZone(zone).toLocalTime()
        assertTrue(
            tuesday >= LocalTime.of(8, 0) && tuesday <= LocalTime.of(23, 30),
            "a Tuesday should be inside the working day and nothing else: $tuesday",
        )
    }

    @Test
    fun `an hour of its own ignores the hours somebody keeps`() {
        val atSeven = Trigger.Repeat(startsOn = date(24), unit = RepeatUnit.DAY, time = LocalTime.of(7, 0))
        val nightOwl = shape.copy(hours = AwakeHours(wake = LocalTime.of(14, 0), sleep = LocalTime.of(23, 0)))
        val moment = (nextFireOf(atSeven, "r1", early, zone, defaultTime, nightOwl) as NextFire.Scheduled).at
        assertEquals(LocalTime.of(7, 0), moment.atZone(zone).toLocalTime())
    }

    // ---- through a whole reminder -----------------------------------------------------------

    @Test
    fun `a reminder made of a recurrence knows when it next rings`() {
        val everyThursdayAtNine = Trigger.Repeat(
            startsOn = date(24),
            unit = RepeatUnit.WEEK,
            time = LocalTime.of(9, 0),
            days = setOf(DayOfWeek.THURSDAY),
        )
        // now is Thursday the 27th at 15:00: nine has been and gone, so it is next Thursday.
        val next = nextFire(reminder(everyThursdayAtNine), now, zone, defaultTime, shape = shape)
        assertEquals(local(2026, 9, 3, 9, 0), (next as NextFire.Scheduled).at)
    }

    @Test
    fun `a recurrence that has run out has nothing next`() {
        val threeDays = Trigger.Repeat(
            startsOn = date(24),
            unit = RepeatUnit.DAY,
            time = LocalTime.of(9, 0),
            ends = RepeatEnd.After(3),
        )
        assertNull(nextFire(reminder(threeDays), now, zone, defaultTime, shape = shape))
    }

    @Test
    fun `a recurrence with an end date stops at it`() {
        val untilSunday = Trigger.Repeat(
            startsOn = date(24),
            unit = RepeatUnit.DAY,
            time = LocalTime.of(9, 0),
            ends = RepeatEnd.On(date(30)),
        )
        assertEquals(
            local(2026, 8, 30, 9, 0),
            (nextFire(reminder(untilSunday), local(2026, 8, 29, 12, 0), zone, defaultTime, shape = shape) as NextFire.Scheduled).at,
        )
        assertNull(nextFire(reminder(untilSunday), local(2026, 8, 30, 12, 0), zone, defaultTime, shape = shape))
    }

    @Test
    fun `the wake a scheduler sets is the moment that was drawn`() {
        val trigger = Trigger.DayRandom(date(29))
        val wake = nextWake(reminder(trigger), early, zone, defaultTime, shape = shape)
        assertEquals(drawFor(trigger), wake?.at)
    }


    // ---- a day written while it is under way ------------------------------------------------

    private fun bare(day: Int, window: DayWindow? = null) = listOf(TriggerRule(Trigger.DayRandom(date(day), window)))

    private fun settled(rules: List<TriggerRule>, at: Instant) =
        settleDays(rules, at, zone, shape).single().trigger as Trigger.DayRandom

    @Test
    fun `a day written while it is under way is drawn from what is left of it`() {
        // Thursday the 27th at 15:00:20: the next whole minute is 15:01, and the day this
        // person is up for ends at half past eleven.
        val at = local(2026, 8, 27, 15, 0).plusSeconds(20)
        assertEquals(DayWindow(LocalTime.of(15, 1), LocalTime.of(23, 30)), settled(bare(27), at).window)
        // And the draw is then always ahead: the whole point.
        for (id in listOf("r1", "r2", "r3", "r4", "r5")) {
            val next = drawFor(settleDays(bare(27), at, zone, shape).single().trigger, at = at, id = id)
            assertTrue(next != null && next > at, "$id: $next is not ahead of $at")
        }
        // A stretch of its own is narrowed the same way: "a la hora de comer" at three is three to four.
        val lunch = DayWindow(LocalTime.of(14, 0), LocalTime.of(16, 0))
        assertEquals(DayWindow(LocalTime.of(15, 1), LocalTime.of(16, 0)), settled(bare(27, lunch), at).window)
        // Exactly on the minute: the next whole minute is still the next one.
        assertEquals(LocalTime.of(15, 1), settled(bare(27), local(2026, 8, 27, 15, 0)).window?.from)
    }

    @Test
    fun `a friday under way runs on to the weekend's bedtime, the next morning`() {
        val at = local(2026, 8, 28, 22, 10)
        val day = settled(bare(28), at)
        assertEquals(DayWindow(LocalTime.of(22, 11), LocalTime.of(1, 30)), day.window)
        // Which DayWindow.on lays over midnight, so the draw is inside Friday night.
        assertInside(drawFor(day, at = at), AwakeWindow(date(28).atTime(22, 11), date(29).atTime(1, 30)))
    }

    @Test
    fun `a day is left alone when it has not opened, when it has closed, and when the clock is past midnight`() {
        // Not yet up: nothing to narrow.
        assertEquals(bare(27), settleDays(bare(27), local(2026, 8, 27, 6, 0), zone, shape))
        // Already in bed: the "ya ha pasado" word is the right one, and the window says so.
        assertEquals(bare(27), settleDays(bare(27), local(2026, 8, 27, 23, 45), zone, shape))
        val lunch = DayWindow(LocalTime.of(14, 0), LocalTime.of(16, 0))
        assertEquals(bare(27, lunch), settleDays(bare(27, lunch), local(2026, 8, 27, 17, 0), zone, shape))
        // The small hours of Saturday belong to Friday's waking window, but a window laid on the
        // Friday cannot start on the Saturday: left as written.
        assertEquals(bare(28), settleDays(bare(28), local(2026, 8, 29, 0, 30), zone, shape))
        // Another day altogether, and every other kind of trigger, untouched.
        assertEquals(bare(29), settleDays(bare(29), local(2026, 8, 27, 15, 0), zone, shape))
        val others = listOf(
            TriggerRule(Trigger.AtDateTime(date(27).atTime(20, 0))),
            TriggerRule(Trigger.Countdown(30, early)),
            TriggerRule(Trigger.Interval(LocalTime.of(16, 0), LocalTime.of(17, 0))),
        )
        assertEquals(others, settleDays(others, local(2026, 8, 27, 15, 0), zone, shape))
    }

    @Test
    fun `the editor's warning and the save agree, without either knowing the id`() {
        // At five in the evening "hoy a cualquier hora" is not in the past: it is tonight.
        val at = local(2026, 8, 27, 17, 0)
        assertTrue(warnings(bare(27), at, zone, defaultTime, shape = shape).none { it is ValidationWarning.InPast })
        // At a quarter to midnight it is, and the word is said.
        assertTrue(warnings(bare(27), local(2026, 8, 27, 23, 45), zone, defaultTime, shape = shape).any { it is ValidationWarning.InPast })
        // A preset made into a reminder in the evening is settled on the way, and rings tonight.
        val preset = Preset(id = "p", name = "Basura", text = "Sacar la basura", rules = bare(27), createdAt = early)
        val made = preset.toReminder(id = "r9", now = at, zone = zone, shape = shape)
        val next = nextFire(made, at, zone, defaultTime, shape = shape)
        assertTrue(next is NextFire.Scheduled && next.at > at && next.at.atZone(zone).toLocalDate() == date(27), "$next")
    }
}
