package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A countdown is a length, not a moment. It used to be stored as the date-time it worked out to,
 * which meant a preset could only ever hold the half hour after the day it was written.
 */
class CountdownTriggerTest {

    private fun reminderWith(vararg triggers: Trigger, createdAt: java.time.Instant = now) = Reminder(
        id = "r1",
        text = "Sacar el pan del horno",
        rules = triggers.map { TriggerRule(it) },
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @Test
    fun `a countdown that has not started answers from now`() {
        val soon = reminderWith(Trigger.Countdown(30))
        val next = nextFire(soon, now, zone, defaultTime) as NextFire.Scheduled
        assertEquals(now.plusSeconds(30 * 60), next.at)
    }

    @Test
    fun `once started it counts from its own beginning, whatever the clock does later`() {
        val started = Trigger.Countdown(30, startedAt = now)
        val tenPast = now.plusSeconds(600)
        val next = nextFire(reminderWith(started), tenPast, zone, defaultTime) as NextFire.Scheduled
        assertEquals(now.plusSeconds(30 * 60), next.at, "twenty minutes left, not thirty")
    }

    @Test
    fun `a countdown that has run out is past, like any other moment`() {
        val started = Trigger.Countdown(30, startedAt = now)
        assertNull(nextFire(reminderWith(started), now.plusSeconds(31 * 60), zone, defaultTime))
    }

    @Test
    fun `starting the clock stamps only the ones that have not begun`() {
        val rules = listOf(
            TriggerRule(Trigger.Countdown(30)),
            TriggerRule(Trigger.Countdown(45, startedAt = now.minusSeconds(600))),
            TriggerRule(Trigger.OnDate(java.time.LocalDate.of(2026, 9, 1))),
        )
        val started = startCountdowns(rules, now)
        assertEquals(now, (started[0].trigger as Trigger.Countdown).startedAt)
        assertEquals(now.minusSeconds(600), (started[1].trigger as Trigger.Countdown).startedAt, "one already ticking is left alone")
        assertEquals(rules[2], started[2])
    }

    @Test
    fun `a shape keeps the length and drops the moment`() {
        val rules = listOf(TriggerRule(Trigger.Countdown(30, startedAt = now)), TriggerRule(Trigger.Countdown(45)))
        val cleared = clearCountdowns(rules)
        assertTrue(cleared.all { (it.trigger as Trigger.Countdown).startedAt == null })
        assertEquals(listOf(30, 45), cleared.map { (it.trigger as Trigger.Countdown).minutes })
    }

    @Test
    fun `a preset holding a countdown makes a reminder that starts ticking now`() {
        val preset = Preset(
            id = "p1",
            name = "Pan en el horno",
            rules = listOf(TriggerRule(Trigger.Countdown(25))),
            createdAt = now.minusSeconds(90 * 24 * 3600L),
        )
        val later = now.plusSeconds(3600)
        val made = preset.toReminder(id = "r1", now = later, words = "Sacar el pan")
        assertEquals(later, (made.rules.single().trigger as Trigger.Countdown).startedAt)
        val next = nextFire(made, later, zone, defaultTime) as NextFire.Scheduled
        assertEquals(later.plusSeconds(25 * 60), next.at, "three months after it was invented, it is still 25 minutes")
    }

    @Test
    fun `a countdown is the time family, its own kind, and survives json`() {
        val trigger = Trigger.Countdown(90, startedAt = now)
        assertEquals(TriggerFamily.TIME, trigger.family)
        assertEquals(TriggerKind.COUNTDOWN, trigger.kind)
        val rules = listOf(TriggerRule(trigger))
        assertEquals(rules, ReminderCodec.decodeRules(ReminderCodec.encodeRules(rules)))
    }

    @Test
    fun `a length outside what the sheet allows is what blocks a save`() {
        assertEquals(TriggerProblem.COUNTDOWN_OUT_OF_RANGE, problemOf(Trigger.Countdown(0)))
        assertEquals(TriggerProblem.COUNTDOWN_OUT_OF_RANGE, problemOf(Trigger.Countdown(MAX_COUNTDOWN_MINUTES + 1)))
        assertNull(problemOf(Trigger.Countdown(1)))
        assertNull(problemOf(Trigger.Countdown(MAX_COUNTDOWN_MINUTES)))
    }
}
