package dev.rwilco.ui.editor

import dev.rwilco.data.FiringEvent
import dev.rwilco.data.FiringKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** What a routine's history comes to: the "hechos", counted, and how far apart. */
class HistorySummaryTest {

    private val t0: Instant = Instant.parse("2026-08-01T09:00:00Z")
    private fun at(days: Long, kind: FiringKind) = FiringEvent(kind, t0.plus(Duration.ofDays(days)))

    @Test
    fun `the hechos are counted newest first, questions are not, and an undone reset is not one`() {
        val history = listOf(
            at(44, FiringKind.ASKED),
            at(42, FiringKind.DEALT),
            at(30, FiringKind.UNRESET),
            at(30, FiringKind.RESET),
            at(21, FiringKind.RESET),
            at(20, FiringKind.ASKED),
            at(0, FiringKind.DEALT),
        )
        val summary = routineHistory(history)
        assertEquals(3, summary.done, "42, 21 and 0; the reset at 30 was undone")
        assertEquals(Duration.ofDays(21), summary.meanGap)
    }

    @Test
    fun `one hecho has no gap, and none has nothing`() {
        assertEquals(RoutineHistory(1, null), routineHistory(listOf(at(0, FiringKind.DEALT))))
        assertEquals(RoutineHistory(0, null), routineHistory(listOf(at(0, FiringKind.ASKED))))
        assertNull(routineHistory(emptyList()).meanGap)
    }
}
