package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class WatchLogTest {

    private fun note(kind: NoteKind, at: Instant) = WatchNote(at = at, kind = kind)

    /** [count] looks of [kind], the newest at [now] and the rest evenly spread back over an hour. */
    private fun log(kind: NoteKind, count: Int, over: Duration = Duration.ofMinutes(59)): WatchLog {
        var built = WatchLog()
        for (i in count - 1 downTo 0) {
            built = built.noting(note(kind, now - over.multipliedBy(i.toLong()).dividedBy(count.toLong().coerceAtLeast(1))))
        }
        return built
    }

    @Test
    fun `the newest line is first and the oldest fall off the end`() {
        var built = WatchLog()
        for (i in 0 until WATCH_LOG_KEEP + 50) built = built.noting(note(NoteKind.FIX, now.plusSeconds(i.toLong())))
        assertEquals(WATCH_LOG_KEEP, built.notes.size)
        assertEquals(now.plusSeconds((WATCH_LOG_KEEP + 49).toLong()), built.notes.first().at, "newest first")
        assertEquals(now.plusSeconds(50), built.notes.last().at, "the first fifty are gone")
    }

    @Test
    fun `only the looks that spent radio count as polls`() {
        val spent = listOf(NoteKind.FIX, NoteKind.BLIND)
        val saved = listOf(NoteKind.REST, NoteKind.STIR, NoteKind.FENCE, NoteKind.ECHO)
        assertTrue(spent.all { note(it, now).isPoll })
        assertFalse(saved.any { note(it, now).isPoll }, "a look the watch talked itself out of is not a poll")
    }

    @Test
    fun `an hour is an hour, and what fell out of it does not count`() {
        val old = WatchLog(notes = (1..30).map { note(NoteKind.FIX, now.minusSeconds(3_600L + it * 60)) })
        assertEquals(0, old.notes.pollsSince(now - PlaceWatchPolicy.BUSY_WINDOW))
        assertEquals(30, old.notes.pollsSince(now.minusSeconds(7_200)))
    }

    @Test
    fun `twenty looks an hour is fine and twenty-one is not`() {
        assertFalse(log(NoteKind.FIX, PlaceWatchPolicy.BUSY_POLLS).busyNotice(now), "the threshold itself is allowed")
        assertTrue(log(NoteKind.FIX, PlaceWatchPolicy.BUSY_POLLS + 1).busyNotice(now))
        // Rests are what keeps the number down; a hundred of them say nothing about the radio.
        assertFalse(log(NoteKind.REST, 100).busyNotice(now))
    }

    @Test
    fun `the notice is said once an hour, because the window it is about is an hour`() {
        val busy = log(NoteKind.FIX, PlaceWatchPolicy.BUSY_POLLS + 5)
        assertTrue(busy.busyNotice(now))
        assertFalse(busy.copy(lastNoticeAt = now.minusSeconds(600)).busyNotice(now), "the same hour, twice")
        assertTrue(busy.copy(lastNoticeAt = now - PlaceWatchPolicy.BUSY_WINDOW).busyNotice(now), "a new hour of it")
    }

    @Test
    fun `a log survives a round trip, and a broken one costs nothing but itself`() {
        val written = WatchLog(lastNoticeAt = now).noting(
            WatchNote(
                at = now,
                kind = NoteKind.FIX,
                waitS = 120,
                gapM = 340.5,
                place = "Casa",
                inside = false,
                speedMps = 1.4,
                movedM = 80.0,
                sensed = true,
                stillStreak = 3,
                charge = 41,
                precise = true,
            ),
        )
        assertEquals(written, ReminderCodec.decodeWatchLog(ReminderCodec.encodeWatchLog(written)))
        assertEquals(WatchLog(), ReminderCodec.decodeWatchLog("not json"))
    }
}
