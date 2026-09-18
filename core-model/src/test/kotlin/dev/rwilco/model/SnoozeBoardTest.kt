package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which snooze offers the alert shows, and which wait behind "a otro momento".
 *
 * The alert offered every answer there is, every time: seven lengths, the places, the calendar —
 * up to ten held buttons on the one screen that is answered half awake. What is shown is the
 * person's to say now, and the rest is one button away, the ones actually used first.
 */
class SnoozeBoardTest {

    @Test
    fun `with nothing hidden the alert is exactly what it was`() {
        val board = snoozeBoard(AppSettings())
        assertEquals(Snooze.entries.map(SnoozeOffer::BuiltIn), board.shown, "every answer, in the order it always had")
        assertEquals(emptyList<SnoozeOffer>(), board.more)
        assertTrue(board.placesShown)
    }

    @Test
    fun `what is hidden waits behind the other door, the most used first`() {
        val settings = AppSettings(
            hiddenSnoozes = setOf("TOMORROW", "WEEKEND", "NEXT_WEEK", SNOOZE_PLACES),
            snoozeUses = mapOf("NEXT_WEEK" to 9, "WEEKEND" to 2, "TEN_MINUTES" to 40),
        )
        val board = snoozeBoard(settings)
        assertEquals(listOf(Snooze.TEN_MINUTES, Snooze.CUSTOM, Snooze.TWO_HOURS, Snooze.TOMORROW_MORNING).map(SnoozeOffer::BuiltIn), board.shown)
        // By use, and between two never used (or used alike) the order they always had.
        assertEquals(listOf(Snooze.NEXT_WEEK, Snooze.WEEKEND, Snooze.TOMORROW).map(SnoozeOffer::BuiltIn), board.more)
        assertFalse(board.placesShown)
    }

    @Test
    fun `a name this build does not know hides nothing and counts for nothing`() {
        val settings = AppSettings(hiddenSnoozes = setOf("FROM_A_NEWER_BUILD"), snoozeUses = mapOf("FROM_A_NEWER_BUILD" to 99))
        assertEquals(Snooze.entries.map(SnoozeOffer::BuiltIn), snoozeBoard(settings).shown)
        // Counted only when it is an offer: "a date" and "a week" are said by other doors.
        assertEquals(emptyMap<String, Int>(), AppSettings().withSnoozeUsed("a date").snoozeUses)
        assertEquals(mapOf("TWO_HOURS" to 2), AppSettings().withSnoozeUsed("TWO_HOURS").withSnoozeUsed("TWO_HOURS").snoozeUses)
    }

    @Test
    fun `hiding and showing is one switch per offer, and everything may be hidden`() {
        val hidden = AppSettings().withSnoozeShown("WEEKEND", shown = false).withSnoozeShown(SNOOZE_PLACES, shown = false)
        assertEquals(setOf("WEEKEND", SNOOZE_PLACES), hidden.hiddenSnoozes)
        assertEquals(emptySet<String>(), hidden.withSnoozeShown("WEEKEND", shown = true).withSnoozeShown(SNOOZE_PLACES, shown = true).hiddenSnoozes)
        // The other door is always there, so an alert with no lengths on it is still answerable.
        val all = Snooze.entries.fold(AppSettings()) { settings, snooze -> settings.withSnoozeShown(snooze.name, shown = false) }
        assertEquals(emptyList<SnoozeOffer>(), snoozeBoard(all).shown)
        assertEquals(Snooze.entries.map(SnoozeOffer::BuiltIn), snoozeBoard(all).more)
        // Something that is not an offer is not a switch.
        assertEquals(AppSettings(), AppSettings().withSnoozeShown("nonsense", shown = false))
    }

    @Test
    fun `the app's own keep their order wherever the person's length has been taken`() {
        // Five hours is longer than two, and the alert somebody already knows still reads
        // ten minutes, theirs, two hours: an update does not rearrange it.
        for (minutes in listOf(5, 30, 300, 720)) {
            assertEquals(Snooze.entries.map(SnoozeOffer::BuiltIn), snoozeBoard(AppSettings(snoozeCustomMinutes = minutes)).shown, "$minutes min")
        }
    }

    @Test
    fun `a snooze lands where the person's own terms say`() {
        val settings = AppSettings(snoozeCustomMinutes = 45, dayStart = java.time.LocalTime.of(7, 30), weekendTime = java.time.LocalTime.of(18, 0))
        val terms = settings.snoozeTerms
        assertEquals(Fixtures.now.plusSeconds(45 * 60), Snooze.CUSTOM.until(Fixtures.now, Fixtures.zone, terms))
        for (snooze in Snooze.entries) {
            assertEquals(
                snooze.until(Fixtures.now, Fixtures.zone, settings.weekendDay, settings.weekendTime, settings.dayStart, settings.snoozeCustomMinutes),
                snooze.until(Fixtures.now, Fixtures.zone, terms),
                snooze.name,
            )
        }
    }
}
