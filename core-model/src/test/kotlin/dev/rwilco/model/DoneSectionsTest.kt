package dev.rwilco.model

import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The history in three bands, and the three months it is kept for.
 *
 * The clock is a Thursday at 15:00 in Madrid ([Fixtures.now]).
 */
class DoneSectionsTest {

    private val now = local(2026, 8, 27, 15, 0)

    private fun done(id: String, at: Instant?, status: Status = Status.DONE) = Reminder(
        id = id,
        text = id,
        status = status,
        createdAt = local(2020, 1, 1, 9, 0),
        updatedAt = at ?: local(2020, 1, 1, 9, 0),
        doneAt = at,
    )

    @Test
    fun `the fortnight of bars is oldest first and ends today`() {
        val counts = doneByDay(
            listOf(
                done("today-1", local(2026, 8, 27, 1, 0)),
                done("today-2", local(2026, 8, 27, 14, 30)),
                done("monday", local(2026, 8, 24, 9, 0)),
                // The first day still inside a fortnight ending today, and the one before it.
                done("edge-in", local(2026, 8, 14, 9, 0)),
                done("edge-out", local(2026, 8, 13, 23, 59)),
            ),
            now,
            zone,
        )
        assertEquals(14, counts.size)
        assertEquals(1, counts.first(), "the fourteenth day back is in")
        assertEquals(2, counts.last(), "today is the last bar")
        assertEquals(1, counts[13 - 3], "monday, three days back")
        assertEquals(4, counts.sum(), "and the day before the window is not counted")
    }

    @Test
    fun `nothing finished is a fortnight of empty bars, not an empty chart`() {
        // The chart draws a dash per empty day, so the row has to keep its length.
        assertEquals(List(14) { 0 }, doneByDay(emptyList(), now, zone))
        assertEquals(emptyList<Int>(), doneByDay(emptyList(), now, zone, days = 0))
    }

    @Test
    fun `a row filed before doneAt existed is counted by the day it was last touched`() {
        // The same fallback groupDone uses: finishedAt() is never null, so a bar is never lost.
        val counts = doneByDay(listOf(done("old", at = null).copy(updatedAt = local(2026, 8, 26, 9, 0))), now, zone)
        assertEquals(1, counts[13 - 1])
    }

    @Test
    fun `today, the week behind it, and everything before that`() {
        val groups = groupDone(
            listOf(
                done("this-morning", local(2026, 8, 27, 1, 0)),
                done("just-now", local(2026, 8, 27, 14, 30)),
                done("monday", local(2026, 8, 24, 9, 0)),
                done("a-week-ago", local(2026, 8, 21, 9, 0)),
                done("last-month", local(2026, 7, 3, 9, 0)),
            ),
            now,
            zone,
        )
        // One in the morning is still today; eight days back is not "this week".
        assertEquals(listOf("just-now", "this-morning"), groups.getValue(DoneSection.TODAY).map { it.id })
        assertEquals(listOf("monday", "a-week-ago"), groups.getValue(DoneSection.LAST_WEEK).map { it.id })
        assertEquals(listOf("last-month"), groups.getValue(DoneSection.EARLIER).map { it.id })
        // Display order is declaration order, and empty bands are not there at all.
        assertEquals(listOf(DoneSection.TODAY, DoneSection.LAST_WEEK, DoneSection.EARLIER), groups.keys.toList())
        assertTrue(groupDone(emptyList(), now, zone).isEmpty())
    }

    @Test
    fun `the seventh day back is still the week`() {
        val groups = groupDone(
            listOf(done("edge", local(2026, 8, 21, 23, 59)), done("over", local(2026, 8, 20, 23, 59))),
            now,
            zone,
        )
        assertEquals(listOf("edge"), groups.getValue(DoneSection.LAST_WEEK).map { it.id })
        assertEquals(listOf("over"), groups.getValue(DoneSection.EARLIER).map { it.id })
    }

    @Test
    fun `a row with no moment of its own falls back on when it was last touched`() {
        // Nothing writes one of these now — setStatus stamps doneAt — but a row from before it
        // did would otherwise have no band to sit in and no age to be swept at.
        val old = done("old", at = null).copy(updatedAt = local(2026, 1, 5, 9, 0))
        assertEquals(listOf("old"), groupDone(listOf(old), now, zone).getValue(DoneSection.EARLIER).map { it.id })
        assertEquals(listOf("old"), expiredDone(listOf(old), now, zone))
    }

    @Test
    fun `three months is where the history stops`() {
        val kept = done("kept", local(2026, 6, 1, 9, 0))
        val gone = done("gone", local(2026, 5, 26, 9, 0))
        assertEquals(listOf("gone"), expiredDone(listOf(kept, gone), now, zone))
        assertEquals(local(2026, 5, 27, 15, 0), doneCutoff(now, zone))
    }

    @Test
    fun `only what was finished is swept`() {
        // Pausing something for four months is a decision; forgetting it would be the app
        // overruling it. So would deleting a live reminder written a year ago.
        val paused = done("paused", local(2026, 1, 1, 9, 0), status = Status.PAUSED)
        val active = done("active", local(2026, 1, 1, 9, 0), status = Status.ACTIVE)
        assertTrue(expiredDone(listOf(paused, active), now, zone).isEmpty())
    }
}
