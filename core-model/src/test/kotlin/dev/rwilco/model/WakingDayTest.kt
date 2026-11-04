package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * "Mañana" and "por la mañana" as somebody means them (2026-11-04): tomorrow is the day that
 * starts when they wake up, until they go to bed; and a moment the app chooses for them is never
 * one they are asleep for.
 *
 * Calendar: 2026-08-29 is a Saturday, 09-01 a Tuesday. The default day: up at eight, to bed at
 * half eleven; the weekend up at ten, to bed at half one.
 */
class WakingDayTest {

    private val shape = DayShape.DEFAULT

    @Test
    fun `the first half of the night still belongs to the evening before`() {
        val tuesday = LocalDate.of(2026, 9, 1)
        assertEquals(tuesday.minusDays(1), shape.wakingDate(tuesday.atTime(0, 15)))
        assertEquals(tuesday.minusDays(1), shape.wakingDate(tuesday.atTime(3, 30)))
        // Halfway between half eleven and eight is a quarter to four.
        assertEquals(tuesday, shape.wakingDate(tuesday.atTime(3, 45)))
        assertEquals(tuesday, shape.wakingDate(tuesday.atTime(7, 0)))
        assertEquals(tuesday, shape.wakingDate(tuesday.atTime(12, 0)))
        assertEquals(tuesday, shape.wakingDate(tuesday.atTime(23, 59)))
        // A Friday night out ends at half one: Saturday at one in the morning is still Friday.
        val saturday = LocalDate.of(2026, 8, 29)
        assertEquals(saturday.minusDays(1), shape.wakingDate(saturday.atTime(1, 0)))
    }

    @Test
    fun `nine on a weekend morning is the weekend's waking hour, and an awake hour stays`() {
        val saturday = LocalDate.of(2026, 8, 29)
        assertEquals(saturday.atTime(10, 0), shape.awakeHourOn(saturday, LocalTime.of(9, 0)))
        assertEquals(saturday.atTime(11, 0), shape.awakeHourOn(saturday, LocalTime.of(11, 0)))
        val tuesday = LocalDate.of(2026, 9, 1)
        assertEquals(tuesday.atTime(9, 0), shape.awakeHourOn(tuesday, LocalTime.of(9, 0)))
        // Past bedtime is not moved to the next morning: the hour is not in the morning at all.
        assertEquals(tuesday.atTime(23, 45), shape.awakeHourOn(tuesday, LocalTime.of(23, 45)))
    }

    @Test
    fun `manana written at half past midnight is the coming day`() {
        val smallHours = local(2026, 9, 1, 0, 30)
        val tomorrow = TriggerRule(Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.DAYS), time = LocalTime.of(9, 0)))
        assertEquals(
            Trigger.AtDateTime(LocalDateTime.of(2026, 9, 1, 9, 0)),
            settleRelativeDates(listOf(tomorrow), smallHours, zone, shape).single().trigger,
        )
        // "Hoy" stays the calendar's: it cannot be a day already over.
        val today = TriggerRule(Trigger.RelativeDate(RelativeDay.In(0, RelativeUnit.DAYS), time = LocalTime.of(9, 0)))
        assertEquals(
            Trigger.AtDateTime(LocalDateTime.of(2026, 9, 1, 9, 0)),
            settleRelativeDates(listOf(today), smallHours, zone, shape).single().trigger,
        )
        // And at ten in the morning "mañana" is the calendar's tomorrow, as ever.
        assertEquals(
            Trigger.AtDateTime(LocalDateTime.of(2026, 9, 2, 9, 0)),
            settleRelativeDates(listOf(tomorrow), local(2026, 9, 1, 10, 0), zone, shape).single().trigger,
        )
    }

    @Test
    fun `the tomorrow chip at half past midnight is the coming day`() {
        val tuesday = LocalDate.of(2026, 9, 1)
        assertEquals(tuesday, DateShortcut.TOMORROW.on(tuesday, shape.wakingDate(tuesday.atTime(0, 30))))
        assertEquals(tuesday.plusDays(1), DateShortcut.TOMORROW.on(tuesday, shape.wakingDate(tuesday.atTime(10, 0))))
    }

    @Test
    fun `manana typed at half past midnight is the coming day`() {
        val reading = readWhen("llamar a mamá mañana a las 10", local(2026, 9, 1, 0, 30), zone, shape = shape)!!
        val once = reading.understood as Understood.Once
        assertEquals(LocalDate.of(2026, 9, 1), (settleRelativeDates(listOf(TriggerRule(once.trigger)), local(2026, 9, 1, 0, 30), zone, shape).single().trigger as Trigger.AtDateTime).at.toLocalDate())
    }

    @Test
    fun `a weekend date with no hour opens when the weekend gets up, not asleep at nine`() {
        val now = local(2026, 8, 27, 15, 0)
        val range = nextFireOf(Trigger.DateRange(LocalDate.of(2026, 8, 29), LocalDate.of(2026, 8, 30)), "r1", now, zone, defaultTime) as NextFire.Scheduled
        assertEquals(local(2026, 8, 29, 10, 0), range.at)
        assertFalse(hushedByTheHour(range.at, range.at, zone, shape))
        val day = nextFireOf(Trigger.OnDate(LocalDate.of(2026, 8, 29)), "r1", now, zone, defaultTime) as NextFire.Scheduled
        assertEquals(local(2026, 8, 29, 10, 0), day.at)
        // A weekday keeps the default hour.
        val monday = nextFireOf(Trigger.OnDate(LocalDate.of(2026, 8, 31)), "r1", now, zone, defaultTime) as NextFire.Scheduled
        assertEquals(local(2026, 8, 31, 9, 0), monday.at)
    }
}
