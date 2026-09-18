package dev.rwilco.ui.settings

import dev.rwilco.model.SnoozeDay
import dev.rwilco.model.SnoozeHour
import dev.rwilco.model.SnoozeLimits
import dev.rwilco.model.SnoozePart
import dev.rwilco.model.SnoozeSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalTime

/** What the sheet behind "Añadir un posponer" would add, which is all the sheet draws. */
class SnoozeDraftTest {

    @Test
    fun `it opens on the answer most often missing, esta noche`() {
        assertEquals(SnoozeSpec.On(SnoozeDay.Today, SnoozeHour.Part(SnoozePart.EVENING)), SnoozeDraft().spec)
    }

    @Test
    fun `a length is an amount of a unit, kept as minutes`() {
        val draft = SnoozeDraft(length = true)
        assertEquals(SnoozeSpec.After(45), draft.spec)
        assertEquals(SnoozeSpec.After(3 * 60), draft.counted(SnoozeUnit.HOURS).spec)
        assertEquals(SnoozeSpec.After(3 * 24 * 60), draft.counted(SnoozeUnit.DAYS).spec)
        // The number does not travel between units: forty-five minutes is not forty-five days.
        assertEquals(SnoozeUnit.DAYS.start, draft.counted(SnoozeUnit.DAYS).amount)
        assertEquals(draft, draft.counted(SnoozeUnit.MINUTES), "the unit it already has changes nothing")
    }

    @Test
    fun `the stepper moves by the unit's step and stops at its ends, all of them lengths the model takes`() {
        assertEquals(50, SnoozeDraft(length = true).stepped(+1).amount)
        assertEquals(40, SnoozeDraft(length = true).stepped(-1).amount)
        for (unit in SnoozeUnit.entries) {
            val low = SnoozeDraft(length = true, unit = unit, amount = unit.amounts.first)
            val high = SnoozeDraft(length = true, unit = unit, amount = unit.amounts.last)
            assertEquals(low, low.stepped(-1))
            assertEquals(high, high.stepped(+1))
            assertTrue(!low.canStep(-1) && low.canStep(+1) && !high.canStep(+1) && high.canStep(-1))
            assertTrue((low.spec as SnoozeSpec.After).minutes in SnoozeLimits.AFTER_MINUTES, "$unit low")
            assertTrue((high.spec as SnoozeSpec.After).minutes in SnoozeLimits.AFTER_MINUTES, "$unit high")
        }
    }

    @Test
    fun `a day takes one of the three parts, or an hour of its own`() {
        val monday = SnoozeDraft(day = SnoozeDay.Weekday(DayOfWeek.MONDAY), part = null, time = LocalTime.of(8, 0))
        assertEquals(SnoozeSpec.On(SnoozeDay.Weekday(DayOfWeek.MONDAY), SnoozeHour.At(LocalTime.of(8, 0))), monday.spec)
        assertEquals(
            SnoozeSpec.On(SnoozeDay.Weekend, SnoozeHour.Part(SnoozePart.EVENING)),
            SnoozeDraft(day = SnoozeDay.Weekend).spec,
        )
    }

    @Test
    fun `it survives being put away and brought back, both halves of it`() {
        val drafts = listOf(
            SnoozeDraft(),
            SnoozeDraft(length = true, amount = 3, unit = SnoozeUnit.DAYS),
            SnoozeDraft(day = SnoozeDay.Weekday(DayOfWeek.SUNDAY), part = null, time = LocalTime.of(21, 45)),
            // The half that is not showing is kept too: switching tabs and back loses nothing.
            SnoozeDraft(length = true, amount = 12, unit = SnoozeUnit.HOURS, day = SnoozeDay.Weekend, part = SnoozePart.MORNING, time = LocalTime.of(7, 15)),
        )
        for (draft in drafts) assertEquals(draft, snoozeDraftOf(draft.saved()), draft.toString())
        assertEquals(SnoozeDraft(), snoozeDraftOf(listOf("nonsense")), "anything else is a fresh one")
    }
}
