package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
    fun `a place named by six rules is one thing that happened, not six`() {
        // Six geofences on the same circle, one walk through the door. The log is right to hold
        // six; the screen a person reads is not.
        val crossings = List(6) {
            WatchNote(at = now.minusSeconds(it.toLong()), kind = NoteKind.FENCE, place = "Club", inside = true, lat = 40.43, lng = -3.666, radiusM = 50)
        }
        assertEquals(1, crossings.asEvents().size)
        // The other way through the same door, a minute later, is a second thing that happened.
        val andOut = listOf(crossings.first().copy(at = now.plusSeconds(90), inside = false)) + crossings
        assertEquals(2, andOut.asEvents().size)
        // A different circle crossed in the same second is not the same event.
        val elsewhere = listOf(crossings.first().copy(lat = 41.0, place = "Casa")) + crossings
        assertEquals(2, elsewhere.asEvents().size)
        // And a run of looks is never collapsed, however alike two of them are.
        val looks = List(4) { WatchNote(at = now.minusSeconds(it.toLong()), kind = NoteKind.FIX, place = "Club") }
        assertEquals(4, looks.asEvents().size)
    }

    @Test
    fun `the day's account names only what actually happened`() {
        val day = now - Duration.ofHours(24)
        val notes = listOf(
            WatchNote(at = now, kind = NoteKind.FIX, tier = FixTier.PRECISE, place = "Casa"),
            WatchNote(at = now, kind = NoteKind.FIX, tier = FixTier.BALANCED, place = "Casa"),
            WatchNote(at = now, kind = NoteKind.FIX, tier = FixTier.COARSE, place = "Oficina"),
            WatchNote(at = now, kind = NoteKind.CACHE, tier = FixTier.BALANCED, place = "Casa"),
            WatchNote(at = now, kind = NoteKind.REST, place = "Casa"),
            // Not looks: things that happened to the watch, at nobody's expense.
            WatchNote(at = now, kind = NoteKind.FENCE, place = "Casa"),
            WatchNote(at = now, kind = NoteKind.STIR),
            // And a look from the week before last, which this day knows nothing about.
            WatchNote(at = day - Duration.ofDays(3), kind = NoteKind.FIX, tier = FixTier.PRECISE, place = "Lejos"),
        )
        val tally = notes.tally(day)
        assertEquals(5, tally.looks)
        assertEquals(1, tally.gps)
        assertEquals(1, tally.network)
        assertEquals(1, tally.coarse)
        assertEquals(1, tally.cached)
        assertEquals(1, tally.rested)
        assertEquals(0, tally.blind)
        assertEquals("Casa", tally.pacedBy)
        assertEquals(4, tally.pacedByLooks)
        assertEquals(WatchTally(), emptyList<WatchNote>().tally(day), "a watch that has not looked has nothing to say")
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
        val saved = listOf(NoteKind.CACHE, NoteKind.REST, NoteKind.STIR, NoteKind.FENCE, NoteKind.ECHO)
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
    fun `what came of a folded run is what came of any of it`() {
        // Six geofences on one circle, and their hours do not all agree: one rule's rang, the
        // rest fell on the floor. The line the screen keeps must not say "nothing rang".
        fun fence(seconds: Long, acted: Boolean?) = WatchNote(
            at = now.minusSeconds(seconds), kind = NoteKind.FENCE, place = "Club",
            inside = true, lat = 40.43, lng = -3.666, radiusM = 50, acted = acted,
        )
        val run = listOf(fence(0, false), fence(1, false), fence(2, true), fence(3, false))
        assertEquals(1, run.asEvents().size)
        assertEquals(true, run.asEvents().single().acted)
        // The head keeps its own answer when nothing in the run did anything...
        assertEquals(false, List(3) { fence(it.toLong(), false) }.asEvents().single().acted)
        // ...and a run nobody wrote an outcome for stays unanswered rather than becoming a "no".
        assertNull(List(3) { fence(it.toLong(), null) }.asEvents().single().acted)
        // A run is judged line against the line above it, so a crossing that dribbles in over
        // more than a minute in total is still the one door.
        val dribble = List(4) { fence(it * 50L, false) }
        assertEquals(1, dribble.asEvents().size)
        // And a gap wider than that is a second thing that happened.
        assertEquals(2, listOf(fence(0, false), fence(61, false)).asEvents().size)
    }

    @Test
    fun `the lines fall into the days they were written in, newest day first`() {
        val zone = ZoneId.of("Europe/Madrid")
        val day = LocalDate.of(2026, 8, 30)
        fun at(hour: Int, minute: Int, daysBack: Long = 0) =
            note(NoteKind.FIX, day.minusDays(daysBack).atTime(hour, minute).atZone(zone).toInstant())
        val notes = listOf(at(11, 30), at(6, 44), at(0, 10), at(23, 50, 1), at(6, 44, 1), at(9, 0, 3))
        val days = notes.byDay(zone)
        assertEquals(listOf(day, day.minusDays(1), day.minusDays(3)), days.map { it.first })
        assertEquals(listOf(3, 2, 1), days.map { it.second.size })
        // Two of them sit either side of midnight in Madrid but not in UTC, which is why the zone
        // is asked for rather than assumed.
        assertEquals(listOf(2, 3, 1), notes.byDay(ZoneId.of("UTC")).map { it.second.size })
        assertEquals(emptyList<Pair<LocalDate, List<WatchNote>>>(), emptyList<WatchNote>().byDay(zone))
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
                tier = FixTier.PRECISE,
                reported = true,
                acted = false,
            ),
        )
        assertEquals(written, ReminderCodec.decodeWatchLog(ReminderCodec.encodeWatchLog(written)))
        assertEquals(WatchLog(), ReminderCodec.decodeWatchLog("not json"))
    }
}
