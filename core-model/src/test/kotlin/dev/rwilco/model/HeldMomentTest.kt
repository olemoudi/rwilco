package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.reminder
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * A moment armed, come and not answered is OWED: a re-arm pass leaves it as it is, for the
 * delivery in flight (or the next catch-up) to ring. Two reminders due at nine used to lose one
 * of them to the other's re-arm, which ran between the alarm's moment and its firing.
 */
class HeldMomentTest {

    private val tonight = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 30))
    private val halfNine = local(2026, 8, 27, 21, 30)

    @Test
    fun `a re-arm pass between the moment and the ring does not spend it`() {
        val phone = Simulation(reminder(tonight), now)
        assertEquals(halfNine, phone.arm()?.at)
        // The broadcast is on its way; another reminder's ring re-arms everything meanwhile.
        phone.now = halfNine.plusMillis(400)
        assertEquals(Wake(halfNine, 0), phone.arm(), "held as it stands, not moved on")
        assertEquals(halfNine, phone.reminder.armedFor, "the row still says the moment is owed")
        // Then the delivery arrives and rings, once, as the delivery of that moment.
        val ring = phone.step()
        assertEquals(halfNine.plusMillis(400), ring?.at)
        assertEquals(0, ring?.ruleIndex)
        assertEquals(1, phone.rings.size)
        assertNull(phone.arm(), "rung, nothing is owed and nothing is left")
    }

    @Test
    fun `an answer given after the moment means it is not owed`() {
        val base = reminder(tonight).copy(armedFor = halfNine, armedRule = 0)
        val later = halfNine.plusSeconds(30)
        assertEquals(halfNine, missedFire(base, later), "unanswered: owed")
        assertNull(missedFire(base.copy(lastDealtAt = later), later), "'hecho' from the card before it got through")
        assertNull(missedFire(base.copy(snoozedUntil = later.plusSeconds(3600)), later), "put off before it got through")
        assertEquals(
            halfNine,
            missedFire(base.copy(lastDealtAt = halfNine.minusSeconds(3600)), later),
            "an earlier 'hecho' answered an earlier moment",
        )
        assertEquals(halfNine, missedFire(base.copy(snoozedUntil = halfNine.minusSeconds(60)), later), "a snooze that ran out answers nothing")
    }

    @Test
    fun `a moment noted under ALL is spent and the next one armed`() {
        val friday = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 10, 0))
        val phone = Simulation(reminder(tonight, friday).copy(ruleMatch = RuleMatch.ALL), now)
        assertNull(phone.step(), "the first of the set is written down, not rung")
        assertEquals(listOf(Wake(halfNine, 0)), phone.noted)
        assertEquals(setOf(0), phone.reminder.firedRules)
        assertEquals(Wake(local(2026, 8, 28, 10, 0), 1), phone.arm(), "the set now waits on the other")
        val ring = phone.step()
        assertEquals(local(2026, 8, 28, 10, 0), ring?.rangFor)
        assertEquals(1, phone.rings.size)
    }

    @Test
    fun `the launch catch-up still rings a moment the phone slept through, once`() {
        val phone = Simulation(reminder(tonight), now)
        val rings = phone.sleepUntil(halfNine.plusSeconds(3 * 3600))
        assertEquals(listOf(halfNine), rings.map { it.late }, "rung late, once, about the moment that was missed")
        assertNull(nextWake(phone.reminder, phone.now, zone, defaultTime), "nothing left to arm")
    }
}
