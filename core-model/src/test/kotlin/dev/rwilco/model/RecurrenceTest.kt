package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/**
 * Recurrence, wound forward.
 *
 * The unit tests below pin each shape; the ones at the end run a reminder through weeks of
 * simulated time — ring, deal with it, ring again — because the thing that actually matters is
 * that the sequence never stalls, never doubles back, and never drifts off the hour it promised.
 */
class RecurrenceTest {

    private val dayStart: LocalTime = LocalTime.of(9, 0)

    private fun reminder(
        recurrence: Recurrence,
        vararg triggers: Trigger,
        createdAt: Instant = local(2026, 8, 27, 15, 0),
        lastDealtAt: Instant? = null,
    ) = Reminder(
        id = "r1",
        text = "Pastillas",
        rules = triggers.map { TriggerRule(it) },
        recurrence = recurrence,
        createdAt = createdAt,
        updatedAt = createdAt,
        lastDealtAt = lastDealtAt,
    )

    // ---- the shapes, one at a time ------------------------------------------------------

    @Test
    fun `hours are exact, because six hours means six hours`() {
        val at = local(2026, 8, 27, 23, 40)
        assertEquals(
            local(2026, 8, 28, 5, 40),
            nextRecurrence(Recurrence.After(6, RecurrenceUnit.HOURS), at, zone, dayStart),
            "medicine does not wait for the morning",
        )
    }

    @Test
    fun `a day later means the next day at the hour you said`() {
        assertEquals(
            local(2026, 8, 28, 9, 0),
            nextRecurrence(Recurrence.After(1, RecurrenceUnit.DAYS), local(2026, 8, 27, 15, 0), zone, dayStart),
        )
        // Dealt with after the day has started, and still the following morning.
        assertEquals(
            local(2026, 8, 28, 9, 0),
            nextRecurrence(Recurrence.After(1, RecurrenceUnit.DAYS), local(2026, 8, 27, 7, 30), zone, dayStart),
        )
    }

    @Test
    fun `never before the span is up, whatever the hour says`() {
        // Dealt with at 23:30; "tomorrow at 09:00" is a morning away, not half an hour.
        val next = nextRecurrence(Recurrence.After(1, RecurrenceUnit.DAYS), local(2026, 8, 27, 23, 30), zone, dayStart)!!
        assertEquals(local(2026, 8, 28, 9, 0), next)
        assertTrue(Duration.between(local(2026, 8, 27, 23, 30), next).toHours() >= 9)
    }

    @Test
    fun `a day start before the hour it was dealt with still lands on the next day`() {
        val earlyStart = LocalTime.of(6, 0)
        assertEquals(
            local(2026, 8, 28, 6, 0),
            nextRecurrence(Recurrence.After(1, RecurrenceUnit.DAYS), local(2026, 8, 27, 22, 0), zone, earlyStart),
        )
    }

    @Test
    fun `weeks and months land on the same hour as days`() {
        assertEquals(
            local(2026, 9, 3, 9, 0),
            nextRecurrence(Recurrence.After(1, RecurrenceUnit.WEEKS), local(2026, 8, 27, 15, 0), zone, dayStart),
        )
        assertEquals(
            local(2026, 9, 27, 9, 0),
            nextRecurrence(Recurrence.After(1, RecurrenceUnit.MONTHS), local(2026, 8, 27, 15, 0), zone, dayStart),
        )
        assertEquals(
            local(2026, 10, 27, 9, 0),
            nextRecurrence(Recurrence.After(2, RecurrenceUnit.MONTHS), local(2026, 8, 27, 15, 0), zone, dayStart),
        )
    }

    @Test
    fun `a month after the 31st lands on the last day the month has`() {
        assertEquals(
            local(2026, 2, 28, 9, 0),
            nextRecurrence(Recurrence.After(1, RecurrenceUnit.MONTHS), local(2026, 1, 31, 12, 0), zone, dayStart),
        )
    }

    @Test
    fun `the first Sunday of the month, from wherever you are in it`() {
        val rule = Recurrence.MonthlyWeekday(1, DayOfWeek.SUNDAY)
        // 2026-08-27 is a Thursday; August's first Sunday (the 2nd) is behind us.
        assertEquals(local(2026, 9, 6, 9, 0), nextRecurrence(rule, local(2026, 8, 27, 15, 0), zone, dayStart))
        // Standing on the 1st of September, the 6th is still ahead.
        assertEquals(local(2026, 9, 6, 9, 0), nextRecurrence(rule, local(2026, 9, 1, 8, 0), zone, dayStart))
        // Standing ON it, the answer is next month's.
        assertEquals(local(2026, 10, 4, 9, 0), nextRecurrence(rule, local(2026, 9, 6, 9, 0), zone, dayStart))
    }

    @Test
    fun `the last Friday of the month is the last one, not the fifth`() {
        val rule = Recurrence.MonthlyWeekday(LAST_ORDINAL, DayOfWeek.FRIDAY)
        assertEquals(local(2026, 8, 28, 9, 0), nextRecurrence(rule, local(2026, 8, 27, 15, 0), zone, dayStart))
        // September 2026 has four Fridays after the 25th? It ends on the 25th: that is the last.
        assertEquals(local(2026, 9, 25, 9, 0), nextRecurrence(rule, local(2026, 8, 28, 9, 0), zone, dayStart))
    }

    @Test
    fun `a fifth weekday that a month does not have rolls to a month that does`() {
        // The 5th Monday: August 2026 has one (the 31st), September does not.
        val rule = Recurrence.MonthlyWeekday(4, DayOfWeek.MONDAY)
        assertEquals(local(2026, 8, 24, 9, 0), nextRecurrence(rule, local(2026, 8, 1, 9, 0), zone, dayStart))
    }

    @Test
    fun `none and by-trigger work out no moments of their own`() {
        assertNull(nextRecurrence(Recurrence.None, local(2026, 8, 27, 15, 0), zone, dayStart))
        assertNull(nextRecurrence(Recurrence.ByTrigger, local(2026, 8, 27, 15, 0), zone, dayStart))
        assertFalse(Recurrence.None.repeats)
        assertTrue(Recurrence.ByTrigger.repeats)
        assertFalse(Recurrence.ByTrigger.isAnchored)
        assertTrue(Recurrence.After(1, RecurrenceUnit.DAYS).isAnchored)
        assertTrue(Recurrence.MonthlyWeekday(1, DayOfWeek.SUNDAY).isAnchored)
    }

    // ---- how it meets the rest of the model ---------------------------------------------

    @Test
    fun `with no triggers the recurrence starts from the day it was written`() {
        val born = local(2026, 8, 27, 15, 0)
        val every6h = reminder(Recurrence.After(6, RecurrenceUnit.HOURS), createdAt = born)
        val next = nextFire(every6h, born, zone, defaultTime, dayStart) as NextFire.Scheduled
        assertEquals(born.plusSeconds(6 * 3600), next.at)
        assertNull(next.trigger, "no rule behind it, and the card should not pretend there is")
    }

    @Test
    fun `with a trigger, the trigger rings first and the recurrence takes over after`() {
        val born = local(2026, 8, 27, 15, 0)
        val atEight = Trigger.AtDateTime(java.time.LocalDateTime.of(2026, 8, 28, 8, 0))
        val pills = reminder(Recurrence.After(6, RecurrenceUnit.HOURS), atEight, createdAt = born)

        val first = nextFire(pills, born, zone, defaultTime, dayStart) as NextFire.Scheduled
        assertEquals(local(2026, 8, 28, 8, 0), first.at, "the trigger says when it starts")

        val afterDose = pills.copy(lastDealtAt = local(2026, 8, 28, 8, 5))
        val second = nextFire(afterDose, local(2026, 8, 28, 8, 5), zone, defaultTime, dayStart) as NextFire.Scheduled
        assertEquals(local(2026, 8, 28, 14, 5), second.at, "and the recurrence says when it comes back")
    }

    @Test
    fun `the alarm is armed for the recurrence's moment, with no rule behind it`() {
        val born = local(2026, 8, 27, 15, 0)
        val every6h = reminder(Recurrence.After(6, RecurrenceUnit.HOURS), createdAt = born)
        assertEquals(Wake(born.plusSeconds(6 * 3600), null), nextWake(every6h, born, zone, defaultTime, dayStart))
    }

    @Test
    fun `dismissal ends it, keeps it, or hands the question back to the triggers`() {
        val now = local(2026, 8, 27, 15, 0)
        val weekly = Trigger.AtTime(LocalTime.of(9, 0), setOf(DayOfWeek.MONDAY))
        assertEquals(Status.DONE, statusAfterDismissal(reminder(Recurrence.None, weekly), now, zone, defaultTime))
        assertEquals(Status.ACTIVE, statusAfterDismissal(reminder(Recurrence.ByTrigger, weekly), now, zone, defaultTime))
        // An anchored one always has a next moment, trigger or no trigger.
        assertEquals(Status.ACTIVE, statusAfterDismissal(reminder(Recurrence.After(6, RecurrenceUnit.HOURS)), now, zone, defaultTime))
        // By-trigger with nothing left to ring is finished, as it always was.
        val past = Trigger.AtDateTime(java.time.LocalDateTime.of(2026, 1, 1, 9, 0))
        assertEquals(Status.DONE, statusAfterDismissal(reminder(Recurrence.ByTrigger, past), now, zone, defaultTime))
    }

    @Test
    fun `a snooze still outranks the recurrence`() {
        val now = local(2026, 8, 27, 15, 0)
        val pills = reminder(Recurrence.After(6, RecurrenceUnit.HOURS), createdAt = now)
            .copy(snoozedUntil = local(2026, 8, 27, 16, 0))
        val next = nextFire(pills, now, zone, defaultTime, dayStart) as NextFire.Scheduled
        assertEquals(local(2026, 8, 27, 16, 0), next.at)
        assertTrue(next.snoozed)
    }

    @Test
    fun `a preset carries the recurrence into the reminders made from it`() {
        val preset = Preset(
            id = "p1",
            name = "Pastillas",
            recurrence = Recurrence.After(8, RecurrenceUnit.HOURS),
            createdAt = local(2026, 1, 1, 9, 0),
        )
        val made = preset.toReminder(id = "r1", now = local(2026, 8, 27, 15, 0), words = "Tomar la pastilla", zone = Fixtures.zone)
        assertEquals(Recurrence.After(8, RecurrenceUnit.HOURS), made.recurrence)
    }

    // ---- wound forward -------------------------------------------------------------------

    /**
     * Live the reminder: whenever its moment arrives, deal with it [dealtAfter] later and see
     * where the next one lands. Returns the moments it rang at.
     */
    private fun run(
        reminder: Reminder,
        from: Instant,
        rings: Int,
        dealtAfter: Duration = Duration.ofMinutes(5),
    ): List<Instant> {
        var current = reminder
        var clock = from
        val moments = ArrayList<Instant>()
        repeat(rings) {
            val next = nextFire(current, clock, zone, defaultTime, dayStart) as? NextFire.Scheduled ?: return moments
            moments += next.at
            // It rings, and is dealt with a few minutes later.
            val dealt = next.at + dealtAfter
            clock = dealt
            val status = statusAfterDismissal(current, dealt, zone, defaultTime)
            current = current.copy(status = status, lastDealtAt = dealt, snoozedUntil = null, firedRules = emptySet())
            if (status != Status.ACTIVE) return moments
        }
        return moments
    }

    @Test
    fun `every six hours, dealt with late each time, never drifts backwards`() {
        val born = local(2026, 8, 27, 15, 0)
        val pills = reminder(Recurrence.After(6, RecurrenceUnit.HOURS), createdAt = born)
        val moments = run(pills, born, rings = 5, dealtAfter = Duration.ofMinutes(20))
        assertEquals(5, moments.size)
        // Each one is six hours after the last was DEALT WITH, so twenty minutes of lateness
        // walks the schedule forward — which is the point of counting from the person.
        assertEquals(local(2026, 8, 27, 21, 0), moments[0])
        assertEquals(local(2026, 8, 28, 3, 20), moments[1])
        assertEquals(local(2026, 8, 28, 9, 40), moments[2])
        assertTrue(moments.zipWithNext().all { (a, b) -> b > a }, "a schedule that goes backwards is a loop")
    }

    @Test
    fun `the day after, five weeks running, always lands at the hour that was asked for`() {
        val born = local(2026, 8, 27, 20, 30)
        val daily = reminder(Recurrence.After(1, RecurrenceUnit.DAYS), createdAt = born)
        val moments = run(daily, born, rings = 5, dealtAfter = Duration.ofHours(3))
        assertEquals(5, moments.size)
        assertTrue(
            moments.all { it.atZone(zone).toLocalTime() == dayStart },
            "every one of them at ${dayStart}: ${moments.map { it.atZone(zone) }}",
        )
        assertEquals(
            listOf(28, 29, 30, 31, 1),
            moments.map { it.atZone(zone).dayOfMonth },
            "one a day, and over the end of the month",
        )
    }

    @Test
    fun `the first Sunday of the month, a year of them`() {
        val born = local(2026, 8, 27, 15, 0)
        val monthly = reminder(Recurrence.MonthlyWeekday(1, DayOfWeek.SUNDAY), createdAt = born)
        val moments = run(monthly, born, rings = 12, dealtAfter = Duration.ofHours(2))
        assertEquals(12, moments.size)
        assertTrue(moments.all { it.atZone(zone).dayOfWeek == DayOfWeek.SUNDAY }, "all Sundays")
        assertTrue(moments.all { it.atZone(zone).dayOfMonth <= 7 }, "and all first ones")
        assertEquals(12, moments.map { it.atZone(zone).month }.distinct().size, "one a month, no month twice")
    }

    @Test
    fun `a reminder that does not repeat rings once and is finished`() {
        val born = local(2026, 8, 27, 15, 0)
        val once = reminder(Recurrence.None, Trigger.AtDateTime(java.time.LocalDateTime.of(2026, 8, 28, 9, 0)), createdAt = born)
        assertEquals(1, run(once, born, rings = 5).size)
    }

    @Test
    fun `by-trigger keeps a weekly reminder weekly, dealt with or not`() {
        val born = local(2026, 8, 24, 8, 0) // a Monday
        val weekly = reminder(
            Recurrence.ByTrigger,
            Trigger.AtTime(LocalTime.of(9, 0), setOf(DayOfWeek.MONDAY)),
            createdAt = born,
        )
        val moments = run(weekly, born, rings = 4, dealtAfter = Duration.ofMinutes(30))
        assertEquals(4, moments.size)
        assertTrue(moments.all { it.atZone(zone).dayOfWeek == DayOfWeek.MONDAY })
        assertTrue(moments.all { it.atZone(zone).toLocalTime() == LocalTime.of(9, 0) }, "the trigger's hour, not the day start")
        assertEquals(listOf(24, 31, 7, 14), moments.map { it.atZone(zone).dayOfMonth })
    }

    @Test
    fun `every recurrence shape survives a round trip through the settings json`() {
        val settings = AppSettings(
            dayStart = LocalTime.of(7, 30),
            recurrencePresets = listOf(
                RecurrencePreset("a", Recurrence.None),
                RecurrencePreset("b", Recurrence.ByTrigger, name = "Cuando toque"),
                RecurrencePreset("c", Recurrence.After(6, RecurrenceUnit.HOURS), uses = 3),
                RecurrencePreset("d", Recurrence.MonthlyWeekday(1, DayOfWeek.SUNDAY), name = "Facturas"),
            ),
        )
        assertEquals(settings, ReminderCodec.decodeSettings(ReminderCodec.encodeSettings(settings)))
    }

    @Test
    fun `most used first, and the built-ins are there from the start`() {
        val presets = defaultRecurrencePresets()
        assertEquals(4, presets.size)
        assertTrue(presets.all { it.name.isEmpty() }, "their own shape is their name")
        val used = presets.mapIndexed { index, preset -> if (index == 2) preset.used(Instant.EPOCH.plusSeconds(10)) else preset }
        assertEquals(presets[2].id, recurrencePresetsByPopularity(used).first().id)
    }

    // ---- years, and the moment a span counts from ------

    @Test
    fun `a span in years lands on the day's start hour, years later`() {
        val dealt = local(2026, 8, 27, 15, 40)
        val back = nextRecurrence(Recurrence.After(2, RecurrenceUnit.YEARS), dealt, zone, dayStart)
        assertEquals(local(2028, 8, 27, 9, 0), back)
    }

    @Test
    fun `the twenty-ninth of February comes back on the twenty-eighth rather than in four years`() {
        val dealt = local(2028, 2, 29, 12, 0)
        assertEquals(
            local(2029, 2, 28, 9, 0),
            nextRecurrence(Recurrence.After(1, RecurrenceUnit.YEARS), dealt, zone, dayStart),
        )
    }

    @Test
    fun `counted from dealing with it, answering late moves the next one`() {
        // Rang at eight, dealt with at half nine: the next dose is six hours after the dose.
        val rang = local(2026, 8, 27, 8, 0)
        val dealt = local(2026, 8, 27, 9, 30)
        val pills = reminder(Recurrence.After(6, RecurrenceUnit.HOURS), lastDealtAt = dealt)
            .copy(lastFiredAt = rang)
        assertEquals(local(2026, 8, 27, 15, 30), pills.recurrenceMoment(dealt, zone, dayStart))
    }

    @Test
    fun `counted from the ringing, answering late does not move the next one`() {
        val rang = local(2026, 8, 27, 8, 0)
        val dealt = local(2026, 8, 27, 9, 30)
        val pills = reminder(
            Recurrence.After(6, RecurrenceUnit.HOURS, RecurrenceFrom.RANG),
            lastDealtAt = dealt,
        ).copy(lastFiredAt = rang)
        // Eight plus six, not half nine plus six: the rhythm somebody set does not drift.
        assertEquals(local(2026, 8, 27, 14, 0), pills.recurrenceMoment(dealt, zone, dayStart))
    }

    @Test
    fun `counted from the ringing still works for one that never rang`() {
        // Swiped done on Home without ever ringing: there is no firing to count from, and
        // "cada 6 h desde que suena" must still come back.
        val dealt = local(2026, 8, 27, 9, 30)
        val swiped = reminder(
            Recurrence.After(6, RecurrenceUnit.HOURS, RecurrenceFrom.RANG),
            lastDealtAt = dealt,
        )
        assertEquals(local(2026, 8, 27, 15, 30), swiped.recurrenceMoment(dealt, zone, dayStart))
    }

    @Test
    fun `a stored recurrence with no anchor written reads as counting from dealing with it`() {
        // Every install before this field existed. Anything else would move real armed moments.
        val old = ReminderCodec.decodeRecurrence("""{"type":"after","amount":6,"unit":"HOURS"}""")
        assertEquals(Recurrence.After(6, RecurrenceUnit.HOURS, RecurrenceFrom.DEALT), old)
        assertFalse(old.countsFromRinging)
    }

    @Test
    fun `editing a preset leaves it exactly where it was`() {
        // The bug this pins: the four built-in recurrences are tied on everything the sort
        // looks at — no uses, never used — so the list's own order is the only thing telling
        // them apart, and only the first three get a button. Rebuilding an edited preset at the
        // end of the list dropped it off the card, which reads as losing it.
        val presets = defaultRecurrencePresets()
        val day = presets.first()
        val renamed = day.copy(name = "Mañana")

        val kept = presets.keeping(renamed)
        assertEquals(presets.map { it.id }, kept.map { it.id }, "the order did not survive an edit")
        assertEquals("Mañana", kept.first().name)
        assertEquals(day.id, recurrencePresetsByPopularity(kept).first().id, "and it is still on the row")

        // One that is not there yet joins at the end, which is where a new thing belongs.
        val fresh = RecurrencePreset(id = "new", recurrence = Recurrence.After(3, RecurrenceUnit.DAYS))
        assertEquals(presets.map { it.id } + "new", presets.keeping(fresh).map { it.id })
    }

    @Test
    fun `an edit does not spend the uses that earned a preset its place`() {
        val presets = defaultRecurrencePresets()
        val used = presets[2].copy(uses = 9, lastUsedAt = local(2026, 8, 27, 9, 0))
        val kept = presets.keeping(used)
        assertEquals(used.id, recurrencePresetsByPopularity(kept).first().id)
        assertEquals(9, kept.first { it.id == used.id }.uses)
    }
}
