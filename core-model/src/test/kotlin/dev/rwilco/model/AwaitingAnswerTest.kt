package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.reminder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalTime

/** What keeps a reminder on the alert screen, and what takes it down from any door. */
class AwaitingAnswerTest {

    private val rang = now.minusSeconds(60)
    private val fired = reminder(Trigger.AtTime(LocalTime.of(14, 59), java.time.DayOfWeek.entries.toSet())).copy(lastFiredAt = rang)

    @Test
    fun `rang and not dealt with is owed an answer`() {
        assertTrue(fired.awaitingAnswer(now))
    }

    @Test
    fun `never rang is owed nothing`() {
        assertFalse(fired.copy(lastFiredAt = null).awaitingAnswer(now))
    }

    @Test
    fun `dealt with since it rang is answered, dealt with before is not`() {
        assertFalse(fired.copy(lastDealtAt = rang).awaitingAnswer(now))
        assertFalse(fired.copy(lastDealtAt = now).awaitingAnswer(now))
        assertTrue(fired.copy(lastDealtAt = rang.minusSeconds(1)).awaitingAnswer(now))
    }

    @Test
    fun `a snooze is an answer`() {
        assertFalse(fired.copy(snoozedUntil = now.plusSeconds(600)).awaitingAnswer(now))
        assertTrue(fired.copy(snoozedUntil = now.minusSeconds(1)).awaitingAnswer(now), "a snooze that has run out no longer answers")
    }

    @Test
    fun `paused or done is answered`() {
        assertFalse(fired.copy(status = Status.PAUSED).awaitingAnswer(now))
        assertFalse(fired.copy(status = Status.DONE).awaitingAnswer(now))
    }
}

/** A moment slept through by a minute is still that moment; one slept through by an hour is news. */
class LatePresentationTest {
    private val now = Fixtures.now

    @Test
    fun `a live firing stays live`() {
        org.junit.jupiter.api.Assertions.assertNull(lateForPresentation(null, now))
    }

    @Test
    fun `a few minutes late rings like the moment itself`() {
        org.junit.jupiter.api.Assertions.assertNull(lateForPresentation(now.minusSeconds(90), now))
        org.junit.jupiter.api.Assertions.assertNull(lateForPresentation(now.minus(LATE_IS_MISSED).plusSeconds(1), now))
    }

    @Test
    fun `a good while late is the quiet note about a moment that passed`() {
        val missed = now.minus(LATE_IS_MISSED)
        org.junit.jupiter.api.Assertions.assertEquals(missed, lateForPresentation(missed, now))
        org.junit.jupiter.api.Assertions.assertEquals(now.minusSeconds(7200), lateForPresentation(now.minusSeconds(7200), now))
    }
}
