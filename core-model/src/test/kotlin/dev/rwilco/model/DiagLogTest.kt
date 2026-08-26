package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** The ring the app writes its own account into: newest first, bounded twice. */
class DiagLogTest {

    private val now = Instant.parse("2026-08-26T10:00:00Z")

    private fun note(at: Instant, text: String = "something") = DiagNote(at, "fire", text)

    @Test
    fun `the newest line is first`() {
        val log = DiagLog().noting(note(now.minusSeconds(60), "older")).noting(note(now, "newer"))
        assertEquals(listOf("newer", "older"), log.notes.map { it.text })
    }

    @Test
    fun `it keeps a bounded number of lines`() {
        var log = DiagLog()
        for (i in 1..DIAG_LOG_KEEP + 40) log = log.noting(note(now.plusSeconds(i.toLong()), "line $i"))
        assertEquals(DIAG_LOG_KEEP, log.notes.size)
        assertEquals("line ${DIAG_LOG_KEEP + 40}", log.notes.first().text)
    }

    @Test
    fun `and drops what is older than the week it is about`() {
        val old = DiagLog(listOf(note(now.minus(DIAG_LOG_AGE).minusSeconds(1), "last month")))
        val log = old.noting(note(now, "today"))
        assertEquals(listOf("today"), log.notes.map { it.text })
    }

    @Test
    fun `a line is a sentence, not a paragraph`() {
        val log = DiagLog().noting(note(now, "x".repeat(DIAG_NOTE_LENGTH * 2)))
        assertEquals(DIAG_NOTE_LENGTH, log.notes.single().text.length)
    }

    @Test
    fun `it survives the round trip through its own codec`() {
        val log = DiagLog().noting(note(now, "r=0f1e2d3c dropped: nothing armed"))
        assertEquals(log, ReminderCodec.decodeDiagLog(ReminderCodec.encodeDiagLog(log)))
        assertTrue(ReminderCodec.decodeDiagLog("{ not json").notes.isEmpty(), "a log that will not read is a log worth losing")
    }

    @Test
    fun `an hour of a busy phone still fits inside the week`() {
        // Thirty notes an hour is a phone ringing constantly; the cap is what stops a runaway.
        var log = DiagLog()
        for (i in 1..24 * 30) log = log.noting(note(now.plus(Duration.ofMinutes(i * 2L))))
        assertEquals(DIAG_LOG_KEEP, log.notes.size)
    }
}

/** The same sentence twice in a minute is one sentence. */
class DiagLogRepeatTest {

    private val now = Instant.parse("2026-08-26T10:00:00Z")

    @Test
    fun `a repeat within the window replaces the line rather than piling on`() {
        val log = DiagLog()
            .noting(DiagNote(now, "arm", "armed=15 missed=0"))
            .noting(DiagNote(now.plusSeconds(1), "arm", "armed=15 missed=0"))
            .noting(DiagNote(now.plusSeconds(2), "arm", "armed=15 missed=0"))
        assertEquals(1, log.notes.size)
        assertEquals(now.plusSeconds(2), log.notes.single().at, "and keeps the latest time")
    }

    @Test
    fun `the same line later is news again, and a different one always is`() {
        val log = DiagLog()
            .noting(DiagNote(now, "arm", "armed=15 missed=0"))
            .noting(DiagNote(now.plus(DIAG_REPEAT_WINDOW).plusSeconds(1), "arm", "armed=15 missed=0"))
        assertEquals(2, log.notes.size)
        val mixed = log.noting(DiagNote(now.plusSeconds(300), "fire", "armed=15 missed=0"))
        assertEquals(3, mixed.notes.size, "the same words from a different part are a different line")
    }
}
