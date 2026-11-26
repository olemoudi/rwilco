package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * "La compra": the greengrocer, the butcher and the bakery, all three inside two hours — and
 * any one of them alone is not the deed (0.216.0).
 */
class AllPlacesTest {

    private val greengrocer = Trigger.Location(40.40, -3.70, 80, Presence.INSIDE, "Frutería", onCrossing = true)
    private val butcher = Trigger.Location(40.41, -3.71, 80, Presence.INSIDE, "Carnicería", onCrossing = true)
    private val bakery = Trigger.Location(40.42, -3.72, 80, Presence.OUTSIDE, "Panadería", onCrossing = true)
    private val nine = Trigger.TimeOfDay(java.time.LocalTime.of(9, 0))

    private fun shopping(
        within: Int? = 120,
        rules: List<TriggerRule> = listOf(
            TriggerRule(greengrocer, resets = true),
            TriggerRule(nine),
            TriggerRule(butcher, resets = true),
            TriggerRule(bakery, resets = true),
        ),
        lastDealtAt: Instant? = null,
    ) = Reminder(
        id = "shop",
        text = "La compra",
        rules = rules,
        recurrence = Recurrence.Since(1, RecurrenceUnit.WEEKS, allPlacesWithinMinutes = within),
        createdAt = now.minus(Duration.ofDays(10)),
        updatedAt = now.minus(Duration.ofDays(10)),
        lastDealtAt = lastDealtAt,
    )

    private fun visit(rule: Int, place: Trigger.Location, minutesAgo: Long) =
        FiringEvent(FiringKind.VISITED, now.minus(Duration.ofMinutes(minutesAgo)), rule, place.doorDetail())

    @Test
    fun `the places that count as done are the vouching ones, and only places`() {
        assertEquals(listOf(0, 2, 3), shopping().vouchingRules)
        // A clock rule written "resets" by hand vouches for nothing: only a place can.
        assertEquals(listOf(0), shopping(rules = listOf(TriggerRule(greengrocer, resets = true), TriggerRule(nine, resets = true))).vouchingRules)
    }

    @Test
    fun `the window means something only with two or more places that count as done`() {
        assertEquals(Duration.ofHours(2), shopping().allPlacesWindow)
        assertNull(shopping(within = null).allPlacesWindow)
        assertNull(shopping(within = 0).allPlacesWindow)
        assertNull(shopping(rules = listOf(TriggerRule(greengrocer, resets = true), TriggerRule(butcher))).allPlacesWindow)
        val plain = shopping().copy(recurrence = Recurrence.After(1, RecurrenceUnit.WEEKS))
        assertNull(plain.allPlacesWindow)
    }

    @Test
    fun `each place counts on its own when no window was asked for`() {
        assertTrue(shopping(within = null).completesPlaces(0, emptyList(), now))
    }

    @Test
    fun `the first and second places are only visits, the third completes the round`() {
        val routine = shopping()
        assertFalse(routine.completesPlaces(0, emptyList(), now))
        val first = listOf(visit(0, greengrocer, 50))
        assertFalse(routine.completesPlaces(2, first, now))
        val second = first + visit(2, butcher, 20)
        assertEquals(setOf(0, 2), routine.placesVisited(second, now))
        assertTrue(routine.completesPlaces(3, second, now))
    }

    @Test
    fun `the same place twice is still one place`() {
        val history = listOf(visit(0, greengrocer, 50), visit(0, greengrocer, 10))
        assertFalse(shopping().completesPlaces(0, history, now))
        assertFalse(shopping().completesPlaces(2, history, now))
    }

    @Test
    fun `a visit older than the window does not count, and the window slides`() {
        val routine = shopping()
        val stale = listOf(visit(0, greengrocer, 130), visit(2, butcher, 30))
        assertFalse(routine.completesPlaces(3, stale, now), "the greengrocer was two hours and ten minutes ago")
        // The greengrocer again, an hour ago: the later visit is the one that counts.
        assertTrue(routine.completesPlaces(3, stale + visit(0, greengrocer, 60), now))
    }

    @Test
    fun `a hecho ends the round, and the visits before it count for nothing`() {
        val history = listOf(visit(0, greengrocer, 50), visit(2, butcher, 40))
        val doneBetween = shopping(lastDealtAt = now.minus(Duration.ofMinutes(45)))
        assertEquals(setOf(2), doneBetween.placesVisited(history, now))
        assertFalse(doneBetween.completesPlaces(3, history, now))
    }

    @Test
    fun `an undone reset does not let its visits complete the next round on their own`() {
        val history = listOf(
            visit(0, greengrocer, 50),
            visit(2, butcher, 40),
            FiringEvent(FiringKind.RESET, now.minus(Duration.ofMinutes(30)), 3, bakery.doorDetail()),
            FiringEvent(FiringKind.UNRESET, now.minus(Duration.ofMinutes(25))),
        )
        // The undo put the count back before the round: lastDealtAt no longer bounds it.
        assertFalse(shopping().completesPlaces(3, history, now))
        assertTrue(shopping().placesVisited(history, now).isEmpty())
    }

    @Test
    fun `a visit counts for the place as the rule stands now`() {
        val history = listOf(visit(0, greengrocer, 50), visit(2, butcher, 40))
        // The butcher replaced by another shop in the editor: its old line is not this place.
        val edited = shopping(
            rules = listOf(
                TriggerRule(greengrocer, resets = true),
                TriggerRule(nine),
                TriggerRule(butcher.copy(label = "Pescadería"), resets = true),
                TriggerRule(bakery, resets = true),
            ),
        )
        assertEquals(setOf(0), edited.placesVisited(history, now))
        // Or the other side of the same line: arriving is not leaving.
        val otherSide = shopping(
            rules = listOf(
                TriggerRule(greengrocer.copy(presence = Presence.OUTSIDE), resets = true),
                TriggerRule(nine),
                TriggerRule(butcher, resets = true),
                TriggerRule(bakery, resets = true),
            ),
        )
        assertEquals(setOf(2), otherSide.placesVisited(history, now))
    }

    @Test
    fun `a line that is not a visit, or is from the future, is not a visit`() {
        val history = listOf(
            FiringEvent(FiringKind.ASKED, now.minus(Duration.ofMinutes(10)), 0, greengrocer.doorDetail()),
            FiringEvent(FiringKind.VISITED, now.plus(Duration.ofMinutes(10)), 2, butcher.doorDetail()),
            FiringEvent(FiringKind.VISITED, now.minus(Duration.ofMinutes(10)), null, butcher.doorDetail()),
        )
        assertTrue(shopping().placesVisited(history, now).isEmpty())
    }

    @Test
    fun `the window survives a change of span, and a preset of the routine keeps it`() {
        val routine = Recurrence.Since(1, RecurrenceUnit.WEEKS, allPlacesWithinMinutes = 90)
        assertEquals(90, (routine.withSpanOf(Recurrence.After(2, RecurrenceUnit.WEEKS)) as Recurrence.Since).allPlacesWithinMinutes)
        assertEquals(90, (routine.withSpanOf(Recurrence.Since(3, RecurrenceUnit.DAYS)) as Recurrence.Since).allPlacesWithinMinutes)
    }

    @Test
    fun `the window is never written when nobody asked for it, and reads back when somebody did`() {
        val plain = shopping(within = null)
        val json = ReminderCodec.encodeRecurrence(plain.recurrence)
        assertFalse(json.contains("allPlaces"), json)
        val asked = ReminderCodec.encodeRecurrence(shopping().recurrence)
        assertEquals(shopping().recurrence, ReminderCodec.decodeRecurrence(asked))
    }
}
