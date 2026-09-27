package dev.rwilco.model

import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant

/**
 * What "a otro momento…" lists (0.160.0): what was kept off the alert and the answers the app
 * suggests, as one list — and never a button the alert already has, by name or by moment.
 *
 * Every clock here is [Fixtures.now]'s Thursday, 27 August 2026, 15:00 in Madrid, unless a test
 * says otherwise; the terms are the defaults (a day that starts at nine, "tarde" at five, "noche"
 * at eight, the person's own length thirty minutes, the weekend from Friday 20:30).
 */
class SnoozeLaterTest {

    private fun later(settings: AppSettings = AppSettings(), now: Instant = Fixtures.now): List<Pair<String, Instant>> =
        snoozeBoard(settings).laterAt(now, zone, settings.snoozeTerms).map { (offer, until) -> offer.key to until }

    private fun on(day: SnoozeDay, part: SnoozePart) = SnoozeSpec.On(day, SnoozeHour.Part(part)).key

    @Test
    fun `with nothing hidden it is the suggestions, in the order they come back, and none of the alert's`() {
        assertEquals(
            listOf(
                "after:60" to local(2026, 8, 27, 16, 0),
                "after:240" to local(2026, 8, 27, 19, 0),
                on(SnoozeDay.Today, SnoozePart.EVENING) to local(2026, 8, 27, 20, 0),
                // "Mañana a mediodía": two o'clock, and it travels as the hour it is.
                "on:tomorrow:14:00" to local(2026, 8, 28, 14, 0),
                on(SnoozeDay.Tomorrow, SnoozePart.AFTERNOON) to local(2026, 8, 28, 17, 0),
                on(SnoozeDay.Tomorrow, SnoozePart.EVENING) to local(2026, 8, 28, 20, 0),
                // The weekend starts on Friday at 20:30, so its first morning is Saturday's.
                on(SnoozeDay.Weekend, SnoozePart.MORNING) to local(2026, 8, 29, 9, 0),
                "after:2880" to local(2026, 8, 29, 15, 0),
                "after:4320" to local(2026, 8, 30, 15, 0),
                on(SnoozeDay.Weekday(DayOfWeek.MONDAY), SnoozePart.MORNING) to local(2026, 8, 31, 9, 0),
                "after:20160" to local(2026, 9, 10, 15, 0),
                "after:43200" to local(2026, 9, 26, 15, 0),
            ),
            later(),
        )
    }

    @Test
    fun `a row that comes back at the very minute of a button on the alert is that button again`() {
        // At three, "esta tarde" is the "2 h" button, and the suggested half hour is the
        // person's own length (thirty minutes by default): both are left out of the list above.
        val keys = later().map { it.first }
        assertTrue(on(SnoozeDay.Today, SnoozePart.AFTERNOON) !in keys, "esta tarde = 2 h")
        assertTrue("after:30" !in keys, "30 min = the person's own")
        // With their length somewhere else, the half hour is an answer of its own again.
        assertEquals("after:30" to local(2026, 8, 27, 15, 30), later(AppSettings(snoozeCustomMinutes = 45)).first())
        // On a Saturday morning, the weekend's next morning is tomorrow's: one row, not two.
        val saturday = local(2026, 8, 29, 10, 0)
        assertTrue(on(SnoozeDay.Weekend, SnoozePart.MORNING) !in later(now = saturday).map { it.first })
    }

    @Test
    fun `a part of today that has gone is not a row`() {
        val nine = local(2026, 8, 27, 21, 0)
        val keys = later(now = nine).map { it.first }
        assertTrue(on(SnoozeDay.Today, SnoozePart.AFTERNOON) !in keys)
        assertTrue(on(SnoozeDay.Today, SnoozePart.EVENING) !in keys)
        // Lengths always are: four hours from nine at night is one in the morning.
        assertEquals("after:240" to local(2026, 8, 28, 1, 0), later(now = nine).first { it.first == "after:240" })
    }

    @Test
    fun `what was kept off the alert and the suggestions are one list, the most used first`() {
        val settings = AppSettings(
            hiddenSnoozes = setOf(Snooze.WEEKEND.name, Snooze.NEXT_WEEK.name),
            snoozeUses = mapOf("after:4320" to 3, Snooze.NEXT_WEEK.name to 1, Snooze.TEN_MINUTES.name to 50),
        )
        val keys = later(settings).map { it.first }
        // Counted first, suggestion or not; the alert's own ten minutes are not here however used.
        assertEquals(listOf("after:4320", Snooze.NEXT_WEEK.name), keys.take(2))
        assertTrue(Snooze.TEN_MINUTES.name !in keys)
        // Then by when they come back: the weekend (Friday 20:30) between Friday night and Saturday.
        val weekend = keys.indexOf(Snooze.WEEKEND.name)
        assertEquals(on(SnoozeDay.Tomorrow, SnoozePart.EVENING), keys[weekend - 1])
        assertEquals(on(SnoozeDay.Weekend, SnoozePart.MORNING), keys[weekend + 1])
    }

    @Test
    fun `a snooze of the person's own that the app also suggests is one row, and none while it is on the alert`() {
        val own = on(SnoozeDay.Tomorrow, SnoozePart.AFTERNOON)
        val hidden = AppSettings(customSnoozes = listOf(own), hiddenSnoozes = setOf(own))
        assertEquals(1, later(hidden).count { it.first == own })
        val shown = AppSettings(customSnoozes = listOf(own))
        assertTrue(own !in later(shown).map { it.first })
    }

    @Test
    fun `every suggestion is a key that reads back, and a length the wire accepts`() {
        for (spec in SNOOZE_SUGGESTIONS) {
            assertEquals(spec, snoozeSpecOf(spec.key), spec.key)
            // Never the app's own under another name: that would be the same button twice. (The
            // person's own length moves, so it is left somewhere no suggestion is; where it meets
            // one, the moment keeps them apart — see above.)
            assertNull(AppSettings(snoozeCustomMinutes = 45).builtInSaying(spec), spec.key)
        }
        assertEquals(SNOOZE_SUGGESTIONS.size, SNOOZE_SUGGESTIONS.map { it.key }.toSet().size)
    }

    @Test
    fun `taking a suggestion is counted, so it can climb the list`() {
        val used = AppSettings().withSnoozeUsed("on:tomorrow:14:00").withSnoozeUsed("on:tomorrow:14:00")
        assertEquals(mapOf("on:tomorrow:14:00" to 2), used.snoozeUses)
        assertEquals(used.snoozeUses, snoozeBoard(used).uses)
        assertEquals("on:tomorrow:14:00", later(used).first().first)
        // A key that is neither an offer nor a suggestion still counts for nothing.
        assertEquals(emptyMap<String, Int>(), AppSettings().withSnoozeUsed("after:46").snoozeUses)
    }
}
