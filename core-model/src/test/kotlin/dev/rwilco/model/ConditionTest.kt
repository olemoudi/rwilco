package dev.rwilco.model

import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalTime

class ConditionTest {

    private val afternoon = Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0))
    private val night = Condition.TimeWindow(LocalTime.of(22, 0), LocalTime.of(6, 0))

    @Test
    fun `a plain window holds between its ends, the start counting and the end not`() {
        assertTrue(afternoon.holdsAt(local(2026, 8, 27, 18, 0), zone))
        assertTrue(afternoon.holdsAt(local(2026, 8, 27, 21, 59), zone))
        assertFalse(afternoon.holdsAt(local(2026, 8, 27, 22, 0), zone))
        assertFalse(afternoon.holdsAt(local(2026, 8, 27, 17, 59), zone))
    }

    @Test
    fun `a window that ends before it starts crosses midnight`() {
        assertTrue(night.holdsAt(local(2026, 8, 27, 23, 30), zone))
        assertTrue(night.holdsAt(local(2026, 8, 28, 2, 0), zone))
        assertFalse(night.holdsAt(local(2026, 8, 28, 6, 0), zone))
        assertFalse(night.holdsAt(local(2026, 8, 28, 12, 0), zone))
    }

    @Test
    fun `days pick the day the window opened, not the one the clock is on`() {
        // 2026-08-27 is a Thursday; 02:00 on the Friday belongs to Thursday's night.
        val thursdayNight = night.copy(days = setOf(DayOfWeek.THURSDAY))
        assertTrue(thursdayNight.holdsAt(local(2026, 8, 27, 23, 30), zone))
        assertTrue(thursdayNight.holdsAt(local(2026, 8, 28, 2, 0), zone))
        assertFalse(thursdayNight.holdsAt(local(2026, 8, 28, 23, 30), zone), "that is Friday's night")
    }

    @Test
    fun `no days means every day`() {
        assertTrue(afternoon.holdsAt(local(2026, 8, 29, 19, 0), zone))
        assertFalse(afternoon.copy(days = setOf(DayOfWeek.MONDAY)).holdsAt(local(2026, 8, 29, 19, 0), zone))
    }

    @Test
    fun `a rule with no conditions holds at any moment`() {
        assertTrue(emptyList<Condition>().allHoldAt(local(2026, 8, 27, 3, 0), zone))
        assertFalse(listOf(afternoon, night).allHoldAt(local(2026, 8, 27, 19, 0), zone), "both, or neither")
    }
}
