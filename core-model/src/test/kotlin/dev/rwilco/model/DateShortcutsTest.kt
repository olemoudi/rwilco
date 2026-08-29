package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class DateShortcutsTest {

    private val thursday = LocalDate.of(2026, 8, 27)

    @Test
    fun `from a thursday the four shortcuts land where a person would point`() {
        assertEquals(thursday, DateShortcut.TODAY.on(thursday))
        assertEquals(LocalDate.of(2026, 8, 28), DateShortcut.TOMORROW.on(thursday))
        assertEquals(LocalDate.of(2026, 8, 31), DateShortcut.NEXT_MONDAY.on(thursday))
        assertEquals(LocalDate.of(2026, 8, 29), DateShortcut.WEEKEND.on(thursday))
    }

    @Test
    fun `next monday on a monday is a week away`() {
        val monday = LocalDate.of(2026, 8, 31)
        assertEquals(LocalDate.of(2026, 9, 7), DateShortcut.NEXT_MONDAY.on(monday))
    }

    @Test
    fun `the weekend is today when today is already the weekend`() {
        val saturday = LocalDate.of(2026, 8, 29)
        val sunday = LocalDate.of(2026, 8, 30)
        assertEquals(saturday, DateShortcut.WEEKEND.on(saturday))
        assertEquals(sunday, DateShortcut.WEEKEND.on(sunday))
    }
}
