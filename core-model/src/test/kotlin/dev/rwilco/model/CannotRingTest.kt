package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The one arrangement the app can *prove* will fail, and the three doors that now say so.
 *
 * "Todos los lunes a las 9:00, y sólo si es de 18:00 a 22:00" is the shape: the moments it names
 * and the hours it allows never meet. [warnings] said it while it was being written and nothing
 * said it afterwards — no row on the card (there is no missed moment to name) and no word from
 * the net ([lastMomentGone] answers null, so [netDue] had nothing to be about).
 */
class CannotRingTest {

    private val impossible = TriggerRule(
        Trigger.AtTime(LocalTime.of(9, 0), setOf(DayOfWeek.MONDAY)),
        listOf(Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0))),
    )

    private fun reminderOf(vararg rules: TriggerRule, recurrence: Recurrence = Recurrence.None) = Reminder(
        id = "r1",
        text = "Buy filters",
        rules = rules.toList(),
        recurrence = recurrence,
        createdAt = now.minusSeconds(3600),
        updatedAt = now.minusSeconds(3600),
    )

    @Test
    fun `an hour its own fences never allow can never ring`() {
        val reminder = reminderOf(impossible)
        assertNull(nextFire(reminder, now, zone, defaultTime), "it has no moment ahead")
        assertTrue(reminder.cannotRing(now, zone, defaultTime))
    }

    /**
     * The line the whole predicate is drawn on. A date already gone when the reminder was
     * written *had* a moment; it is a different sentence and the editor has one for it
     * ([ValidationWarning.InPast]). Read off `lastMomentGone` instead — nothing ahead, nothing
     * behind — this is indistinguishable from an impossible shape, because that walk starts on
     * the day the reminder was written and never sees it.
     */
    @Test
    fun `a one-shot already past when it was written is late, not impossible`() {
        val late = reminderOf(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 9, 0))))
        assertNull(nextFire(late, now, zone, defaultTime))
        assertNull(late.lastMomentGone(now, zone, defaultTime), "written after the moment it names")
        assertFalse(late.cannotRing(now, zone, defaultTime))
    }

    @Test
    fun `a reminder whose moments have simply been and gone is overdue, not impossible`() {
        // A date last week: it had a moment, it came, nobody was told. That is the ordinary end
        // of a reminder and a different sentence.
        val gone = reminderOf(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 24, 9, 0))))
        assertNull(nextFire(gone, now, zone, defaultTime))
        assertFalse(gone.cannotRing(now, zone, defaultTime), "it had a moment; it just passed")
    }

    @Test
    fun `a note with no when is not broken`() {
        assertFalse(reminderOf().cannotRing(now, zone, defaultTime), "nothing rings it, and nothing was meant to")
    }

    @Test
    fun `a paused reminder is quiet because somebody said so`() {
        val paused = reminderOf(impossible).copy(status = Status.PAUSED)
        assertFalse(paused.cannotRing(now, zone, defaultTime))
    }

    @Test
    fun `a reminder that still has a moment ahead is fine`() {
        val ahead = reminderOf(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 16, 0))))
        assertFalse(ahead.cannotRing(now, zone, defaultTime))
    }

    /**
     * The history is deliberately not asked: the editor judges a draft, which has none, so a
     * clause about ringing would have warned over the save button about an arrangement Home
     * then said nothing about. [lastMomentGone] already walks a copy with nothing spent, so
     * "it never produced a moment" is a fact about the shape.
     */
    @Test
    fun `a ring on the row does not make an impossible shape possible`() {
        val edited = reminderOf(impossible).copy(lastFiredAt = now.minusSeconds(7200))
        assertTrue(edited.cannotRing(now, zone, defaultTime))
    }

    @Test
    fun `the net has a word for it, anchored on the day it was written`() {
        val reminder = reminderOf(impossible)
        val due = reminder.netDue(now, zone, defaultTime, SafetyNetSettings())
        assertEquals(NetWord.CANNOT_RING, due?.word)
        assertEquals(reminder.createdAt, due?.about, "the only honest moment there is")
        assertFalse(NetWord.CANNOT_RING.saysItGotAway, "nothing got away; nothing was coming")
    }

    @Test
    fun `it says it once`() {
        val reminder = reminderOf(impossible)
        val first = reminder.netDue(now, zone, defaultTime, SafetyNetSettings())!!
        val said = reminder.copy(nudgedAt = first.about)
        assertNull(said.netDue(now, zone, defaultTime, SafetyNetSettings()), "a second word is the nagging this is not")
    }

    @Test
    fun `Home marks the card`() {
        val reminder = reminderOf(impossible)
        val groups = groupForHome(listOf(reminder), now, zone, defaultTime)
        val entry = groups.sections.getValue(Section.OVERDUE).single()
        assertNull(entry.missedAt, "it never had a moment to miss, which is the whole problem")
        assertTrue(entry.cannotRing, "and now the card can say so")
    }

    @Test
    fun `an ordinary overdue card is not marked`() {
        val gone = reminderOf(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 24, 9, 0))))
        val entry = groupForHome(listOf(gone), now, zone, defaultTime).sections.getValue(Section.OVERDUE).single()
        assertFalse(entry.cannotRing)
    }

    @Test
    fun `a calendar whose fences never allow a date can never ring either`() {
        // "Todos los lunes, y sólo el día 1": a Monday that is also the first is four years off,
        // which the walk finds — so the fence has to be one nothing can ever clear.
        val reminder = reminderOf(
            recurrence = Recurrence.Calendar(
                repeat = Trigger.Repeat(startsOn = LocalDate.of(2026, 8, 27), unit = RepeatUnit.WEEK, days = setOf(DayOfWeek.MONDAY), time = LocalTime.of(9, 0)),
                conditions = listOf(Condition.OnDays(setOf(DayOfWeek.TUESDAY))),
            ),
        )
        assertTrue(reminder.cannotRing(now, zone, defaultTime))
    }
}
