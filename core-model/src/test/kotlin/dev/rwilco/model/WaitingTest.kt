package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.reminder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalTime

/** What Home's card at the top is about: the answers somebody still owes. */
class WaitingTest {

    private val rang = now.minusSeconds(600)
    private val fired = reminder(Trigger.AtTime(LocalTime.of(14, 50), java.time.DayOfWeek.entries.toSet()))
        .copy(lastFiredAt = rang)

    @Test
    fun `a ring nobody answered is waiting, card or no card`() {
        // The row is the first witness, and it is the one that survives a shade somebody
        // cleared half asleep: the notification is gone and the answer is still owed.
        assertTrue(fired.answerOwed(now, cardOpen = false))
        assertTrue(fired.answerOwed(now, cardOpen = true))
    }

    @Test
    fun `an answer is an answer, whichever door it came through`() {
        assertFalse(fired.copy(lastDealtAt = now).answerOwed(now))
        assertFalse(fired.copy(snoozedUntil = now.plusSeconds(600)).answerOwed(now))
        assertFalse(fired.copy(status = Status.PAUSED).answerOwed(now))
        assertFalse(fired.copy(status = Status.DONE).answerOwed(now))
    }

    @Test
    fun `a card in the shade is the other witness, for the answers written nowhere`() {
        // A routine's question: it was asked, "todavía no" writes nothing at all, and the row
        // is not owed an answer either way. Only the shade knows, so only the shade is asked.
        val asked = reminder(Trigger.AtTime(LocalTime.of(9, 0), java.time.DayOfWeek.entries.toSet()))
            .copy(recurrence = Recurrence.After(1, RecurrenceUnit.DAYS), askedAt = now.minusSeconds(120))
        assertFalse(asked.answerOwed(now, cardOpen = false))
        assertTrue(asked.answerOwed(now, cardOpen = true))
    }

    @Test
    fun `a firing asked for nothing never had a card to answer`() {
        // Every action off is an answer too: the moment passes without a word on purpose, and
        // the reminder is simply overdue afterwards. A red row about it would be the app
        // inventing a question nobody was asked.
        assertFalse(fired.copy(actions = emptySet()).answerOwed(now))
        // Unless there is a card after all — which there is not, but the shade has the last
        // word on its own contents.
        assertTrue(fired.copy(actions = emptySet()).answerOwed(now, cardOpen = true))
    }

    @Test
    fun `a contact is never on this card, told or not`() {
        // Its telling is a card in the shade with two answers on it, and it still stays in its
        // own row: that row is built on purpose not to look like a debt, and this card is a
        // complaint. Asked and answered by the owner, 2026-09-20.
        val ana = reminder(id = "ana", text = "Ana").copy(
            contactKind = ContactKind.WORK,
            recurrence = Recurrence.Since(21, RecurrenceUnit.DAYS),
            lastFiredAt = rang,
        )
        assertTrue(ana.contactOwed(now), "told about and unanswered, which is its own row on Home")
        assertFalse(ana.answerOwed(now, cardOpen = true))
    }

    @Test
    fun `the longest wait comes first, and the count runs from the ring`() {
        val early = fired.copy(id = "early", lastFiredAt = now.minusSeconds(3600))
        val late = fired.copy(id = "late", lastFiredAt = now.minusSeconds(60))
        val answered = fired.copy(id = "answered", lastDealtAt = now)
        val owed = answersOwed(listOf(late, answered, early), now)
        assertEquals(listOf("early", "late"), owed.map { it.id })
        assertEquals(now.minusSeconds(3600), early.owedSince())
    }

    @Test
    fun `with no ring behind it the count runs from the question, then from the net`() {
        val bare = reminder().copy(lastFiredAt = null, askedAt = null, nudgedAt = null)
        assertEquals(bare.updatedAt, bare.owedSince())
        assertEquals(now.minusSeconds(30), bare.copy(nudgedAt = now.minusSeconds(30)).owedSince())
        assertEquals(
            now.minusSeconds(90),
            bare.copy(askedAt = now.minusSeconds(90), nudgedAt = now.minusSeconds(30)).owedSince(),
        )
    }
}
