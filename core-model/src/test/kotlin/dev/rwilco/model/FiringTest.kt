package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.reminder
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

class FiringTest {

    private val tonight = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 30))
    private val past = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 8, 0))
    private val weekly = Trigger.AtTime(LocalTime.of(7, 30), setOf(DayOfWeek.MONDAY))
    private val place = Trigger.Location(40.4, -3.7, 200, Transition.ENTER, "Casa")

    @Test
    fun `a snooze outranks the triggers and is marked as such`() {
        val snoozed = reminder(tonight).copy(snoozedUntil = local(2026, 8, 27, 15, 10))
        val next = nextFire(snoozed, now, zone, defaultTime) as NextFire.Scheduled
        assertEquals(local(2026, 8, 27, 15, 10), next.at)
        assertTrue(next.snoozed)
        assertEquals(tonight, next.trigger, "the row keeps the icon it is recognised by")
    }

    @Test
    fun `a snooze that has passed goes back to the triggers`() {
        val stale = reminder(tonight).copy(snoozedUntil = local(2026, 8, 27, 14, 0))
        val next = nextFire(stale, now, zone, defaultTime) as NextFire.Scheduled
        assertEquals(local(2026, 8, 27, 21, 30), next.at)
        assertFalse(next.snoozed)
    }

    @Test
    fun `dismissing finishes a one-shot and leaves anything that comes round again`() {
        val at = local(2026, 8, 27, 21, 31)
        assertEquals(Status.DONE, statusAfterDismissal(reminder(tonight), at, zone, defaultTime))
        assertEquals(Status.DONE, statusAfterDismissal(reminder(past), now, zone, defaultTime))
        assertEquals(Status.ACTIVE, statusAfterDismissal(reminder(weekly), at, zone, defaultTime))
        assertEquals(Status.ACTIVE, statusAfterDismissal(reminder(place), at, zone, defaultTime))
        assertEquals(
            Status.ACTIVE,
            statusAfterDismissal(reminder(past, weekly), now, zone, defaultTime),
            "one dead trigger does not end a reminder that still repeats",
        )
    }

    @Test
    fun `dismissing ignores a snooze that is still running`() {
        val snoozed = reminder(past).copy(snoozedUntil = now.plusSeconds(600))
        assertEquals(Status.DONE, statusAfterDismissal(snoozed, now, zone, defaultTime))
    }

    @Test
    fun `a missed firing is an armed moment with no ring to match it`() {
        val base = reminder(tonight)
        assertNull(missedFire(base, now), "nothing armed, nothing missed")
        assertNull(missedFire(base.copy(armedFor = now.plusSeconds(60)), now), "still to come")
        assertEquals(
            local(2026, 8, 27, 14, 0),
            missedFire(base.copy(armedFor = local(2026, 8, 27, 14, 0)), now),
            "the phone slept through it",
        )
        assertNull(
            missedFire(base.copy(armedFor = local(2026, 8, 27, 14, 0), lastFiredAt = local(2026, 8, 27, 14, 0)), now),
            "it rang; being ignored is not being missed",
        )
        assertEquals(
            local(2026, 8, 27, 14, 0),
            missedFire(base.copy(armedFor = local(2026, 8, 27, 14, 0), lastFiredAt = local(2026, 8, 20, 7, 30)), now),
            "the last ring was an older occurrence",
        )
        assertNull(missedFire(base.copy(armedFor = local(2026, 8, 27, 14, 0), status = Status.PAUSED), now))
    }

    @Test
    fun `a full-screen alert keeps its notification but hands the noise to the screen`() {
        val plan = firingPlan(setOf(Action.FULL_SCREEN, Action.SOUND, Action.VIBRATE))
        assertTrue(plan.fullScreen)
        assertTrue(plan.notification, "the notification is what is left when the takeover is refused")
        assertTrue(plan.sound)
        assertFalse(plan.notificationSound, "the alert screen rings; the notification must not double it")
        assertFalse(plan.notificationVibrate)
    }

    @Test
    fun `without a full screen the notification carries the sound and the buzz`() {
        val plan = firingPlan(setOf(Action.NOTIFICATION, Action.SOUND, Action.VIBRATE))
        assertFalse(plan.fullScreen)
        assertTrue(plan.notificationSound)
        assertTrue(plan.notificationVibrate)
        val quiet = firingPlan(setOf(Action.NOTIFICATION))
        assertFalse(quiet.notificationSound)
        assertFalse(quiet.notificationVibrate)
    }

    @Test
    fun `snoozes land where they say`() {
        assertEquals(now.plusSeconds(600), Snooze.TEN_MINUTES.until(now, zone, defaultTime))
        assertEquals(now.plusSeconds(3600), Snooze.ONE_HOUR.until(now, zone, defaultTime))
        assertEquals(
            local(2026, 8, 28, 9, 0),
            Snooze.TOMORROW.until(now, zone, defaultTime),
            "tomorrow is the usual hour tomorrow, not twenty-four hours from now",
        )
    }
}
