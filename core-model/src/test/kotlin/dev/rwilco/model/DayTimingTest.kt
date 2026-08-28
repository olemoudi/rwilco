package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * What the rules already said about the time of day, for the calendar that opens under them.
 *
 * Somebody who has just answered "when in the day" should not be asked again three rows down;
 * this is that answer, read off the rules rather than stored anywhere.
 */
class DayTimingTest {

    private val friday = LocalDate.of(2026, 8, 28)
    private val lunch = DayWindow(LocalTime.of(14, 0), LocalTime.of(16, 0))

    private fun timingOf(vararg triggers: Trigger) = dayTimingOf(triggers.map { TriggerRule(it) })

    @Test
    fun `an hour somebody typed comes across as that hour`() {
        assertEquals(DayTiming.At(LocalTime.of(20, 0)), timingOf(Trigger.AtDateTime(friday.atTime(20, 0))))
        assertEquals(DayTiming.At(LocalTime.of(9, 0)), timingOf(Trigger.AtTime(LocalTime.of(9, 0), setOf(DayOfWeek.MONDAY))))
    }

    @Test
    fun `a stretch comes across as that stretch, whichever tile named it`() {
        // The date tile's "en una franja" and the window tile are the same two times said twice.
        assertEquals(DayTiming.In(lunch), timingOf(Trigger.DayRandom(friday, lunch)))
        assertEquals(DayTiming.In(lunch), timingOf(Trigger.Interval(LocalTime.of(14, 0), LocalTime.of(16, 0))))
    }

    @Test
    fun `declining to answer is itself an answer`() {
        assertEquals(DayTiming.Whenever, timingOf(Trigger.DayRandom(friday)))
        assertEquals(DayTiming.Whenever, timingOf(Trigger.Repeat(startsOn = friday)))
    }

    @Test
    fun `the ones with nothing to say say nothing`() {
        // A length, a doorway, a lottery and a fortnight: none of them names a time of day that
        // anybody chose, and putting the settings' own hour into a second control as though
        // somebody had typed it would be inventing an answer.
        assertNull(timingOf(Trigger.Countdown(30)))
        assertNull(timingOf(Trigger.Location(40.4, -3.7, 150, Presence.INSIDE, "Casa")))
        assertNull(timingOf(Trigger.Random(3, Period.DAY, LocalTime.of(9, 0), LocalTime.of(21, 0))))
        assertNull(timingOf(Trigger.DateRange(friday, friday.plusDays(7))))
        assertNull(timingOf(Trigger.OnDate(friday)))
        assertNull(dayTimingOf(emptyList()))
    }

    @Test
    fun `the first rule that says anything is the one that is heard`() {
        // A reminder with two clocks in it has no single answer, and the one written first is
        // the one the person was thinking of. A place in front of a clock is not an obstacle.
        assertEquals(
            DayTiming.At(LocalTime.of(20, 0)),
            timingOf(
                Trigger.Location(40.4, -3.7, 150, Presence.INSIDE, "Casa"),
                Trigger.AtDateTime(friday.atTime(20, 0)),
                Trigger.DayRandom(friday, lunch),
            ),
        )
    }
}
