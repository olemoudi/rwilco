package dev.rwilco.alarm

import dev.rwilco.model.Action
import dev.rwilco.model.NextFire
import dev.rwilco.model.nextFire
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class TestAlertTest {

    private val zone = ZoneId.of("Europe/Madrid")
    private val now = Instant.parse("2026-08-29T17:00:00Z")

    @Test
    fun `the rehearsal rings ten seconds out with everything switched on`() {
        val reminder = TestAlert.reminder(now, zone, "Alerta de prueba")
        val next = nextFire(reminder, now, zone, LocalTime.of(9, 0)) as NextFire.Scheduled
        assertEquals(now.plusSeconds(TestAlert.SECONDS_AHEAD), next.at)
        // The four ways an alert reaches somebody; "insist" is left out, a rehearsal need not nag.
        assertEquals(setOf(Action.FULL_SCREEN, Action.NOTIFICATION, Action.SOUND, Action.VIBRATE), reminder.actions)
        assertTrue(TestAlert.isTest(reminder.id))
    }

    @Test
    fun `only the marked id is a rehearsal`() {
        assertFalse(TestAlert.isTest("test-alert"))
        assertFalse(TestAlert.isTest("3f1c-test-alert:1"))
        assertTrue(TestAlert.isTest("test-alert:1756486800000"))
    }
}
