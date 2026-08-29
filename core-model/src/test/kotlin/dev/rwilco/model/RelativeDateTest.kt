package dev.rwilco.model

import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A day counted from the one it is used on: what makes a preset for "mañana" mean tomorrow
 * every time it is pressed, rather than the tomorrow of the day it was invented.
 */
class RelativeDateTest {

    private val thursday = LocalDate.of(2026, 8, 27)
    private val nine = LocalTime.of(9, 0)
    private val defaultTime = LocalTime.of(9, 0)
    private fun at(date: LocalDate, time: LocalTime): Instant = date.atTime(time).atZone(zone).toInstant()
    private val now = at(thursday, LocalTime.of(15, 0))

    @Test
    fun `the days people name land where they would point`() {
        assertEquals(LocalDate.of(2026, 8, 28), RelativeDay.In(1, RelativeUnit.DAYS).on(thursday))
        assertEquals(LocalDate.of(2026, 8, 29), RelativeDay.In(2, RelativeUnit.DAYS).on(thursday))
        assertEquals(LocalDate.of(2026, 9, 3), RelativeDay.In(1, RelativeUnit.WEEKS).on(thursday))
        assertEquals(LocalDate.of(2026, 9, 27), RelativeDay.In(1, RelativeUnit.MONTHS).on(thursday))
        assertEquals(LocalDate.of(2026, 8, 31), RelativeDay.NextWeekday(DayOfWeek.MONDAY).on(thursday))
    }

    @Test
    fun `the next monday asked on a monday is a week away, not today`() {
        val monday = LocalDate.of(2026, 8, 31)
        assertEquals(LocalDate.of(2026, 9, 7), RelativeDay.NextWeekday(DayOfWeek.MONDAY).on(monday))
    }

    @Test
    fun `it becomes the plain date it means, with the hour it was given`() {
        val withHour = Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.DAYS), time = nine)
        assertEquals(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 9, 0)), withHour.on(thursday))
        val leftToTheDay = Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.DAYS))
        assertEquals(Trigger.DayRandom(LocalDate.of(2026, 8, 28)), leftToTheDay.on(thursday))
        val window = DayWindow(LocalTime.of(18, 0), LocalTime.of(20, 0))
        val inAWindow = Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.DAYS), window = window)
        assertEquals(Trigger.DayRandom(LocalDate.of(2026, 8, 28), window), inAWindow.on(thursday))
    }

    @Test
    fun `a preset for tomorrow means tomorrow whenever it is used`() {
        val preset = Preset(
            id = "p1",
            name = "Mañana",
            text = "Llamar al taller",
            rules = listOf(TriggerRule(Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.DAYS), time = nine))),
            createdAt = now,
        )
        val today = preset.toReminder(id = "r1", now = now, zone = zone)
        assertEquals(listOf(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 9, 0))), today.rules.map { it.trigger })
        // Three days later it is three days later, which a fixed date could never be.
        val later = preset.toReminder(id = "r2", now = now.plusSeconds(3 * 24 * 3600), zone = zone)
        assertEquals(listOf(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 31, 9, 0))), later.rules.map { it.trigger })
        // And a reminder never carries the shape itself: everything downstream sees a date.
        assertTrue(later.rules.none { it.trigger is Trigger.RelativeDate })
    }

    @Test
    fun `a preset carrying one never goes stale, which is the whole point`() {
        val rules = listOf(TriggerRule(Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.DAYS), time = nine)))
        // The fixed date it was written as: a year on, Home refuses to write it blind.
        val fixed = listOf(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 9, 0))))
        val aYearOn = now.plusSeconds(365 * 24 * 3600L)
        assertTrue(warnings(fixed, aYearOn, zone, defaultTime).any { it is ValidationWarning.InPast })
        assertTrue(warnings(rules, aYearOn, zone, defaultTime).none { it is ValidationWarning.InPast })
    }

    @Test
    fun `asked before it is written, it answers for the day it would be written on`() {
        val rule = TriggerRule(Trigger.RelativeDate(RelativeDay.NextWeekday(DayOfWeek.MONDAY), time = nine))
        val next = nextFireOfRule(rule, "r1", now, zone, defaultTime) as NextFire.Scheduled
        assertEquals(at(LocalDate.of(2026, 8, 31), nine), next.at)
    }

    @Test
    fun `nothing but a relative day is settled, and a rule keeps its fences`() {
        val fenced = TriggerRule(
            Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.WEEKS), time = nine),
            listOf(Condition.AtPlace(40.4, -3.7, 200, "Casa", inside = true)),
        )
        val countdown = TriggerRule(Trigger.Countdown(30))
        val settled = settleRelativeDates(listOf(fenced, countdown), now, zone)
        assertEquals(Trigger.AtDateTime(LocalDateTime.of(2026, 9, 3, 9, 0)), settled[0].trigger)
        assertEquals(fenced.conditions, settled[0].conditions)
        assertEquals(countdown, settled[1])
    }

    @Test
    fun `it survives a write and a read, discriminator and all`() {
        val rules = listOf(
            TriggerRule(Trigger.RelativeDate(RelativeDay.In(3, RelativeUnit.DAYS), time = nine)),
            TriggerRule(Trigger.RelativeDate(RelativeDay.NextWeekday(DayOfWeek.FRIDAY))),
        )
        val raw = ReminderCodec.encodeRules(rules)
        assertTrue(raw.contains("relative_date"), raw)
        assertEquals(rules, ReminderCodec.decodeRules(raw))
    }

    @Test
    fun `the warnings judge it as it will be saved, not as something that comes round again`() {
        // A relative day is resolved once, at the save. Left unresolved, the walk that looks for
        // a moment re-reads the shape from wherever it has got to — so it behaves like a
        // repeating rule, steps over the fence's start date and reports nothing wrong, while
        // what actually gets written is tomorrow at nine and can never pass that fence.
        val inAMonth = now.atZone(zone).toLocalDate().plusDays(30)
        val fenced = listOf(
            TriggerRule(
                Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.DAYS), time = nine),
                listOf(Condition.DateRange(inAMonth, inAMonth.plusDays(7))),
            ),
        )
        val said = warnings(fenced, now, zone, defaultTime)
        assertTrue(said.any { it is ValidationWarning.NeverFires }, "a rule that cannot fire once written must say so: $said")
    }
}
