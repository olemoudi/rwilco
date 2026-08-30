package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The clock is not a fact the app owns, and both of the ways it moves are ordinary.
 *
 * A phone crosses a time zone and every wall-clock promise the app made now means a different
 * instant — that is the point of storing them without a zone, and `SystemEventsReceiver` re-arms
 * for exactly this. And a clock can go *backwards*: an NTP correction, a person fixing a
 * mis-set date, a dual-SIM phone taking the network's word for it. A reminder that has already
 * rung must not ring again because the hour it rang in came round a second time.
 */
class TravellingClockTest {

    private val madrid: ZoneId = zone
    private val tokyo: ZoneId = ZoneId.of("Asia/Tokyo")

    private val halfNine = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 21, 30))

    private fun reminder(vararg triggers: Trigger, lastFiredAt: Instant? = null) = Fixtures.reminder(*triggers)
        .copy(lastFiredAt = lastFiredAt)

    @Test
    fun `half past nine stays half past nine when the phone flies`() {
        val written = reminder(halfNine)
        val fromMadrid = nextFire(written, Fixtures.now, madrid, defaultTime) as NextFire.Scheduled
        val fromTokyo = nextFire(written, Fixtures.now, tokyo, defaultTime) as NextFire.Scheduled

        assertEquals(LocalTime.of(21, 30), fromMadrid.at.atZone(madrid).toLocalTime())
        assertEquals(LocalTime.of(21, 30), fromTokyo.at.atZone(tokyo).toLocalTime())
        assertEquals(
            Duration.ofHours(7),
            Duration.between(fromTokyo.at, fromMadrid.at),
            "Tokyo gets there seven hours earlier, which is why the arrival re-arms everything",
        )
    }

    @Test
    fun `a weekly arrangement is read in the zone the phone is standing in`() {
        // Friday 07:30 in Madrid is still Thursday evening in the Americas, so the same rule
        // picks a different day depending on where the phone woke up. The rule is the promise;
        // the zone is what turns it into an instant.
        val fridayMorning = Trigger.AtTime(LocalTime.of(7, 30), setOf(DayOfWeek.FRIDAY))
        val mexico = ZoneId.of("America/Mexico_City")
        val thursdayLunch = LocalDateTime.of(2026, 8, 27, 14, 0).atZone(madrid).toInstant()

        val here = nextFireOf(fridayMorning, "r1", thursdayLunch, madrid, defaultTime) as NextFire.Scheduled
        val there = nextFireOf(fridayMorning, "r1", thursdayLunch, mexico, defaultTime) as NextFire.Scheduled

        assertEquals(DayOfWeek.FRIDAY, here.at.atZone(madrid).dayOfWeek)
        assertEquals(DayOfWeek.FRIDAY, there.at.atZone(mexico).dayOfWeek)
        assertTrue(there.at > here.at, "the Friday further west has not happened yet")
    }

    @Test
    fun `a moment that has rung does not come round again when the clock is put back`() {
        // It rang at 21:30. Then the phone's clock jumped back twenty minutes — so by the app's
        // reckoning 21:30 has not happened yet. It has: the ring is the record, not the clock.
        val rang = local(2026, 8, 28, 21, 30)
        val corrected = rang.minus(Duration.ofMinutes(20))
        val alreadyRung = reminder(halfNine, lastFiredAt = rang)

        assertNull(
            nextWake(alreadyRung, corrected, madrid, defaultTime),
            "the ring is what makes a moment spent, not the hour on the face of the clock",
        )
    }

    @Test
    fun `a weekly reminder put back an hour waits for next week, not for the hour again`() {
        val weekly = Trigger.AtTime(LocalTime.of(7, 30), setOf(DayOfWeek.THURSDAY))
        val rang = local(2026, 8, 27, 7, 30)
        val putBack = rang.minus(Duration.ofMinutes(45))

        assertEquals(
            local(2026, 9, 3, 7, 30),
            nextWake(reminder(weekly, lastFiredAt = rang), putBack, madrid, defaultTime)?.at,
            "next Thursday, not this one over again",
        )
    }

    @Test
    fun `a clock put forward hands over the moments it skipped rather than losing them`() {
        // The other direction: the clock jumps forward past a moment nobody heard. That is a
        // missed firing, and Home has to say so — silence is the one failure a reminder cannot
        // afford.
        val armed = local(2026, 8, 28, 21, 30)
        val jumped = armed.plus(Duration.ofHours(3))
        val waiting = reminder(halfNine).copy(armedFor = armed, armedRule = 0)

        assertEquals(armed, missedFire(waiting, jumped))
        assertNull(missedFire(waiting.copy(lastFiredAt = armed), jumped), "one that rang was not missed")
    }

    @Test
    fun `a snooze keeps its moment when the clock is put back`() {
        // Rang at 21:30, put off to 22:00 — then the clock is corrected back half an hour.
        // The snooze is an answer already given and stands; nothing is owed meanwhile.
        val rang = local(2026, 8, 28, 21, 30)
        val until = local(2026, 8, 28, 22, 0)
        val put = reminder(halfNine, lastFiredAt = rang).copy(snoozedUntil = until, armedFor = until)
        val corrected = rang.minus(Duration.ofMinutes(30))

        assertEquals(Wake(until, null), nextWake(put, corrected, madrid, defaultTime))
        assertNull(missedFire(put, corrected), "nothing is owed while the snooze stands")
    }

    @Test
    fun `a clock put back does not re-owe a moment already answered`() {
        val armed = local(2026, 8, 28, 21, 30)
        val answered = reminder(halfNine).copy(armedFor = armed, armedRule = 0, lastDealtAt = armed.plusSeconds(300))
        assertNull(missedFire(answered, armed.minus(Duration.ofHours(1))), "armed is ahead again by the corrected clock")
        assertNull(missedFire(answered, armed.plusSeconds(600)), "and the hecho already answered it either side of the jump")
    }

    @Test
    fun `spring forward in the southern hemisphere resolves the gap forward too`() {
        // Sydney's clocks jump 02:00 to 03:00 on 2026-10-04, in the months Madrid's stand still.
        val sydney = ZoneId.of("Australia/Sydney")
        val inTheGap = Trigger.AtDateTime(LocalDateTime.of(2026, 10, 4, 2, 30))
        val next = nextFireOf(inTheGap, "r1", Fixtures.now, sydney, defaultTime) as NextFire.Scheduled
        assertEquals(LocalTime.of(3, 30), next.at.atZone(sydney).toLocalTime(), "half past a missing hour rings when the clock lands")
    }

    @Test
    fun `the autumn overlap rings on its first pass, whatever the hemisphere`() {
        // 2026-04-05 in Sydney: 03:00 falls back to 02:00, so 02:30 happens twice.
        val sydney = ZoneId.of("Australia/Sydney")
        val doubled = Trigger.AtDateTime(LocalDateTime.of(2026, 4, 5, 2, 30))
        val next = nextFireOf(doubled, "r1", local(2026, 4, 4, 12, 0), sydney, defaultTime) as NextFire.Scheduled
        assertEquals(ZoneOffset.ofHours(11), next.at.atZone(sydney).offset, "still daylight time: the earlier of the two")
    }

    @Test
    fun `a quarter-hour offset zone keeps its wall clock`() {
        val kathmandu = ZoneId.of("Asia/Kathmandu")
        val friday = Trigger.AtTime(LocalTime.of(9, 30), setOf(DayOfWeek.FRIDAY))
        val next = nextFireOf(friday, "r1", Fixtures.now, kathmandu, defaultTime) as NextFire.Scheduled
        val there = next.at.atZone(kathmandu)
        assertEquals(LocalTime.of(9, 30), there.toLocalTime())
        assertEquals(ZoneOffset.ofHoursMinutes(5, 45), there.offset, "+05:45 is nobody's special case")
    }
}
