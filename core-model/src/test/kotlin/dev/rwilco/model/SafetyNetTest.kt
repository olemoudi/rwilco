package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** The quiet word about a reminder that got away, either way it got away. */
class SafetyNetTest {

    private val settings = SafetyNetSettings()

    private fun reminder(
        vararg rules: TriggerRule,
        recurrence: Recurrence = Recurrence.None,
        safetyNet: Boolean = true,
        lastFiredAt: Instant? = null,
        lastDealtAt: Instant? = null,
        nudgedAt: Instant? = null,
        snoozedUntil: Instant? = null,
        status: Status = Status.ACTIVE,
    ) = Reminder(
        id = "r1",
        text = "Tomar la pastilla",
        rules = rules.toList(),
        recurrence = recurrence,
        status = status,
        createdAt = now.minusSeconds(86_400),
        updatedAt = now.minusSeconds(86_400),
        snoozedUntil = snoozedUntil,
        lastFiredAt = lastFiredAt,
        lastDealtAt = lastDealtAt,
        safetyNet = safetyNet,
        nudgedAt = nudgedAt,
    )

    private fun Reminder.cadence() = ringCadence(now, zone, defaultTime)

    // ---- how often it comes back ------------------------------------------------------------

    @Test
    fun `a span answers with its own step, whatever the anchor has done`() {
        val sixHourly = reminder(recurrence = Recurrence.After(6, RecurrenceUnit.HOURS))
        assertEquals(Duration.ofHours(6), sixHourly.cadence())
        // Rung six hours ago and ignored, it has no next moment of its own — and its rhythm is
        // still six hours, which is the whole reason the cadence is asked of the shape.
        val ignored = sixHourly.copy(lastFiredAt = now.minusSeconds(6 * 3600))
        assertNull(nextFire(ignored, now, zone, defaultTime), "spent until it is dealt with")
        assertEquals(Duration.ofHours(6), ignored.cadence())
        assertEquals(Duration.ofDays(1), reminder(recurrence = Recurrence.After(1, RecurrenceUnit.DAYS)).cadence())
        assertEquals(Duration.ofDays(7), reminder(recurrence = Recurrence.After(1, RecurrenceUnit.WEEKS)).cadence())
    }

    @Test
    fun `a calendar answers with the gap between two of its own dates`() {
        val daily = Recurrence.Calendar(Trigger.Repeat(startsOn = LocalDate.of(2026, 8, 1), unit = RepeatUnit.DAY, time = LocalTime.of(9, 0)))
        assertEquals(Duration.ofDays(1), reminder(recurrence = daily).cadence())
        val weekly = Recurrence.Calendar(Trigger.Repeat(startsOn = LocalDate.of(2026, 8, 3), unit = RepeatUnit.WEEK, time = LocalTime.of(9, 0)))
        assertEquals(Duration.ofDays(7), reminder(recurrence = weekly).cadence())
    }

    @Test
    fun `a rule that keeps producing moments answers with its own rhythm`() {
        // Three a day inside a ten-hour window: the gaps are what they are, and all of them are
        // well under a day.
        val random = reminder(TriggerRule(Trigger.Random(3, Period.DAY, LocalTime.of(10, 0), LocalTime.of(20, 0))), recurrence = Recurrence.ByTrigger)
        val gap = random.cadence()
        assertTrue(gap != null && gap < Duration.ofDays(1) && gap > Duration.ZERO, "$gap")
        // A window opens once a day, every day.
        val daily = reminder(TriggerRule(Trigger.Interval(LocalTime.of(18, 0), LocalTime.of(20, 0))), recurrence = Recurrence.ByTrigger)
        assertEquals(Duration.ofDays(1), daily.cadence())
    }

    @Test
    fun `a reminder with nothing left to ring has no cadence at all`() {
        val once = reminder(TriggerRule(Trigger.AtDateTime(LocalDate.of(2026, 8, 27).atTime(9, 0))), lastFiredAt = local(2026, 8, 27, 9, 0))
        assertNull(once.cadence())
        // And so does a place: nothing can say when somebody will be back.
        assertNull(reminder(TriggerRule(Trigger.Location(40.4, -3.7, 150, Presence.INSIDE, "Casa"))).cadence())
    }

    // ---- how long it waits -------------------------------------------------------------------

    @Test
    fun `with nothing coming back it waits the whole day, and otherwise a tenth of the gap`() {
        assertEquals(Duration.ofHours(24), netWait(null, settings))
        assertEquals(Duration.ofHours(24), netWait(Duration.ofDays(30), settings), "never longer than the whole wait")
        assertEquals(Duration.ofMinutes(144), netWait(Duration.ofDays(1), settings), "a tenth of a day")
        assertEquals(Duration.ofMinutes(36), netWait(Duration.ofHours(6), settings))
        // The settings are the settings: a different tenth, a different wait.
        assertEquals(Duration.ofHours(12), netWait(Duration.ofDays(1), settings.copy(fraction = 2)))
        assertEquals(Duration.ofHours(2), netWait(null, settings.copy(afterHours = 2)))
    }

    @Test
    fun `rings closer together than the floor carry no net`() {
        assertTrue(tooFastForNet(Duration.ofMinutes(30), settings))
        assertFalse(tooFastForNet(Duration.ofHours(1), settings), "an hour is the floor, and the floor is allowed")
        assertFalse(tooFastForNet(null, settings), "nothing is coming to bury it")
        assertFalse(tooFastForNet(Duration.ofMinutes(30), settings.copy(minCadenceMinutes = 20)))
    }

    // ---- when the word is due ----------------------------------------------------------------

    private fun Reminder.due(at: Instant = now) = nudgeAt(at, zone, defaultTime, settings)

    @Test
    fun `a one-shot rung and ignored is owed a word a day later`() {
        val rang = local(2026, 8, 27, 9, 0)
        val once = reminder(TriggerRule(Trigger.AtDateTime(LocalDate.of(2026, 8, 27).atTime(9, 0))), lastFiredAt = rang)
        assertEquals(rang.plus(Duration.ofHours(24)), once.due())
    }

    @Test
    fun `something six-hourly is caught in thirty-six minutes, before the next one buries it`() {
        val rang = local(2026, 8, 27, 14, 0)
        val pills = reminder(recurrence = Recurrence.After(6, RecurrenceUnit.HOURS), lastFiredAt = rang)
        assertEquals(rang.plus(Duration.ofMinutes(36)), pills.due())
    }

    @Test
    fun `a moment that came while a fence was shut, with none left, is the other way one gets away`() {
        // "El jueves a las nueve, y sólo si estoy en casa." Thursday nine came while nobody was
        // home; nothing rang, and there is no second Thursday for this one.
        val thursday = LocalDate.of(2026, 8, 27)
        val home = Condition.AtPlace(40.4, -3.7, 200, "Casa")
        val missed = reminder(
            TriggerRule(Trigger.AtDateTime(thursday.atTime(9, 0)), listOf(home)),
        ).copy(createdAt = local(2026, 8, 26, 12, 0), updatedAt = local(2026, 8, 26, 12, 0))
        // Nothing left to ring, and it never did.
        assertNull(nextFire(missed, now, zone, defaultTime))
        assertNull(missed.lastFiredAt)
        val due = missed.netDue(now, zone, defaultTime, settings)
        assertEquals(NetWord.NEVER_RANG, due?.word)
        assertEquals(local(2026, 8, 27, 9, 0), due?.about, "the moment that came and went")
        assertEquals(local(2026, 8, 28, 9, 0), due?.at, "a day later, since nothing else is coming")
    }

    @Test
    fun `a moment still ahead is not something that got away`() {
        val friday = LocalDate.of(2026, 8, 28)
        val ahead = reminder(TriggerRule(Trigger.AtDateTime(friday.atTime(9, 0))))
        assertNull(ahead.netDue(now, zone, defaultTime, settings), "it has not happened yet")
    }

    @Test
    fun `something that comes round again is never the never-rang kind`() {
        // A daily whose window was shut this morning still has tomorrow, so nothing got away.
        val daily = reminder(
            TriggerRule(Trigger.Interval(LocalTime.of(9, 0), LocalTime.of(10, 0))),
            recurrence = Recurrence.ByTrigger,
        )
        assertNull(daily.netDue(now, zone, defaultTime, settings))
    }

    @Test
    fun `dealing with it, or being told about it once, takes the never-rang word down too`() {
        val thursday = LocalDate.of(2026, 8, 27)
        val home = Condition.AtPlace(40.4, -3.7, 200, "Casa")
        val missed = reminder(
            TriggerRule(Trigger.AtDateTime(thursday.atTime(9, 0)), listOf(home)),
        ).copy(createdAt = local(2026, 8, 26, 12, 0), updatedAt = local(2026, 8, 26, 12, 0))
        assertNull(missed.copy(safetyNet = false).netDue(now, zone, defaultTime, settings))
        assertNull(missed.copy(lastDealtAt = now).netDue(now, zone, defaultTime, settings), "dealt with")
        assertNull(missed.copy(status = Status.PAUSED).netDue(now, zone, defaultTime, settings))
        assertNull(missed.copy(snoozedUntil = now.plusSeconds(3600)).netDue(now, zone, defaultTime, settings))
        assertNull(
            missed.copy(nudgedAt = local(2026, 8, 28, 9, 0)).netDue(now, zone, defaultTime, settings),
            "said once is said",
        )
    }

    @Test
    fun `every answer there is takes the net down`() {
        val rang = local(2026, 8, 27, 9, 0)
        val once = reminder(TriggerRule(Trigger.AtDateTime(LocalDate.of(2026, 8, 27).atTime(9, 0))), lastFiredAt = rang)
        assertNull(once.copy(safetyNet = false).due(), "not asked for")
        // Never having rung is not an answer — it is the other way one gets away, and the net
        // says so in its own words.
        assertEquals(
            NetWord.NEVER_RANG,
            once.copy(lastFiredAt = null).netDue(now, zone, defaultTime, settings)?.word,
        )
        assertNull(once.copy(lastDealtAt = rang.plusSeconds(60)).due(), "hecho")
        assertNull(once.copy(snoozedUntil = now.plusSeconds(3600)).due(), "a snooze is an answer")
        assertNull(once.copy(status = Status.PAUSED).due(), "paused")
        assertNull(once.copy(status = Status.DONE).due())
    }

    @Test
    fun `one word per firing, and a fresh one for the next`() {
        val rang = local(2026, 8, 27, 9, 0)
        val once = reminder(TriggerRule(Trigger.AtDateTime(LocalDate.of(2026, 8, 27).atTime(9, 0))), lastFiredAt = rang)
        assertNull(once.copy(nudgedAt = rang.plus(Duration.ofHours(24))).due(), "already said once")
        assertNull(once.copy(nudgedAt = rang).due(), "at the ring counts as said")
        // A later ring is a new firing, and the net is owed again.
        val again = once.copy(nudgedAt = rang, lastFiredAt = rang.plusSeconds(86_400))
        assertEquals(rang.plusSeconds(86_400).plus(Duration.ofHours(24)), again.due())
    }

    @Test
    fun `a reminder that comes back every few minutes is left alone`() {
        // Five times inside one hour: the next one is always sooner than any net, and a word
        // between two of them is noise. A span cannot be this fast (hours are its smallest
        // unit); a random window can.
        val rang = local(2026, 8, 27, 14, 0)
        val fast = reminder(
            TriggerRule(Trigger.Random(5, Period.DAY, LocalTime.of(10, 0), LocalTime.of(11, 0))),
            recurrence = Recurrence.ByTrigger,
            lastFiredAt = rang,
        )
        val cadence = fast.cadence()
        assertTrue(cadence != null && cadence < Duration.ofHours(1), "these draws are minutes apart: $cadence")
        assertTrue(tooFastForNet(cadence, settings))
        assertNull(fast.due(), "so the net is never armed")
    }
}
