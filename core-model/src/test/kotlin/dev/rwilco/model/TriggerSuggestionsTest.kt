package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The "when"s offered back.
 *
 * Somebody who always sets half an hour should be offered half an hour, not the three answers
 * the app shipped with. What is reusable about a past trigger is its shape — a length, an hour,
 * a place — never the instant it fell on, which is why a date-time comes back re-hung on today
 * or tomorrow and a bare date does not come back at all.
 */
class TriggerSuggestionsTest {

    private val zone = ZoneId.of("Europe/Madrid")
    private val today = now.atZone(zone).toLocalDate()

    private fun used(trigger: Trigger, daysAgo: Long, id: String = trigger.toString() + daysAgo): Reminder {
        val at = now.minus(Duration.ofDays(daysAgo))
        return Reminder(id = id, text = "x", rules = listOf(TriggerRule(trigger)), createdAt = at, updatedAt = at)
    }

    @Test
    fun `the length used most comes first, and comes back as a length`() {
        val past = listOf(
            used(Trigger.Countdown(30, startedAt = now.minus(Duration.ofDays(1))), 1),
            used(Trigger.Countdown(30, startedAt = now.minus(Duration.ofDays(2))), 2),
            used(Trigger.Countdown(10, startedAt = now.minus(Duration.ofDays(3))), 3),
        )
        val offered = suggestedTriggers(past, now, zone)
        assertEquals(Trigger.Countdown(30, startedAt = null), offered.first(), "the half hour was not first, or came back already started")
        assertEquals(listOf(30, 10), offered.filterIsInstance<Trigger.Countdown>().map { it.minutes })
    }

    @Test
    fun `an hour comes back on today while it is still ahead, and on tomorrow once it is past`() {
        val hereNow = now.atZone(zone).toLocalTime()
        val ahead = hereNow.plusHours(2).withSecond(0).withNano(0)
        val past = hereNow.minusHours(2).withSecond(0).withNano(0)
        val history = listOf(
            used(Trigger.AtDateTime(LocalDateTime.of(today.minusDays(9), ahead)), 9),
            used(Trigger.AtDateTime(LocalDateTime.of(today.minusDays(8), past)), 8),
        )
        val offered = suggestedTriggers(history, now, zone).filterIsInstance<Trigger.AtDateTime>()
        assertEquals(LocalDateTime.of(today, ahead), offered.single { it.at.toLocalTime() == ahead }.at)
        assertEquals(LocalDateTime.of(today.plusDays(1), past), offered.single { it.at.toLocalTime() == past }.at)
    }

    @Test
    fun `the same hour on different days is one offer`() {
        val nine = LocalTime.of(9, 0)
        val history = (1L..4L).map { used(Trigger.AtDateTime(LocalDateTime.of(today.minusDays(it), nine)), it) }
        assertEquals(1, suggestedTriggers(history, now, zone).size, "four Mondays at nine offered four times")
    }

    @Test
    fun `a bare date has nothing to offer back`() {
        val history = listOf(used(Trigger.OnDate(today.minusDays(3)), 3))
        assertTrue(suggestedTriggers(history, now, zone).isEmpty())
    }

    @Test
    fun `a place and a chance come back as they were`() {
        val place = Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa")
        val chance = Trigger.Random(2, Period.DAY, LocalTime.of(10, 0), LocalTime.of(20, 0), emptySet())
        val offered = suggestedTriggers(listOf(used(place, 1), used(chance, 3)), now, zone)
        assertEquals(listOf(place, chance), offered)
    }

    @Test
    fun `a repeating time is not offered back, because no tile can open one`() {
        // It is the calendar in "Vuelve" now. What offers one again is the recurrence presets,
        // and a chip that opens nothing is worse than a chip that is not there.
        val weekly = Trigger.AtTime(LocalTime.of(7, 30), setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        val series = Trigger.Repeat(startsOn = today.minusDays(7), time = LocalTime.of(9, 0))
        assertTrue(suggestedTriggers(listOf(used(weekly, 1), used(series, 2)), now, zone).isEmpty())
    }

    @Test
    fun `the same door pinned twice is one place`() {
        val a = Trigger.Location(40.416900, -3.703500, 200, Presence.INSIDE, "Casa")
        val b = Trigger.Location(40.416903, -3.703498, 200, Presence.INSIDE, "Casa")
        assertEquals(1, suggestedTriggers(listOf(used(a, 1), used(b, 2)), now, zone).size)
    }

    @Test
    fun `arriving and leaving the same place are two different whens`() {
        val arrive = Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa")
        assertEquals(2, suggestedTriggers(listOf(used(arrive, 1), used(arrive.copy(presence = Presence.OUTSIDE), 2)), now, zone).size)
    }

    @Test
    fun `five uses last month beat one yesterday`() {
        val old = (30L..34L).map { used(Trigger.Countdown(5), it) }
        val fresh = listOf(used(Trigger.Countdown(45), 1))
        assertEquals(5, (suggestedTriggers(old + fresh, now, zone).first() as Trigger.Countdown).minutes)
    }

    @Test
    fun `nothing used yet offers nothing, and the editor falls back on its own`() {
        assertTrue(suggestedTriggers(emptyList(), now, zone).isEmpty())
    }

    @Test
    fun `the kinds sort themselves by use, and the unused keep their order`() {
        val history = listOf(
            used(Trigger.Location(40.0, -3.0, 200, Presence.INSIDE, "Casa"), 1),
            used(Trigger.Location(41.0, -3.0, 200, Presence.INSIDE, "Gym"), 2),
            used(Trigger.Countdown(30), 3),
        )
        val order = triggerKindsByUse(history, now)
        assertEquals(TriggerKind.PLACE, order[0])
        assertEquals(TriggerKind.COUNTDOWN, order[1])
        assertEquals(OFFERED_KINDS.size, order.size, "a kind went missing")
        assertEquals(
            OFFERED_KINDS.filter { it != TriggerKind.PLACE && it != TriggerKind.COUNTDOWN },
            order.drop(2),
            "the kinds nobody uses lost their usual order",
        )
    }

    @Test
    fun `with no history at all the kinds keep the order they always had`() {
        assertEquals(OFFERED_KINDS, triggerKindsByUse(emptyList(), now))
    }

    @Test
    fun `a favourite saved when the two date tiles were two is offered as the one that is left`() {
        val history = listOf(used(Trigger.AtDateTime(java.time.LocalDateTime.of(2026, 8, 27, 21, 0)), 1))
        val order = triggerKindsByUse(history, now)
        assertEquals(TriggerKind.DATE, order[0])
        assertEquals(OFFERED_KINDS.size, order.size)
        assertEquals(TriggerKind.DATE, TriggerKind.DATE_TIME.offered())
    }

    @Test
    fun `a favourite that is no longer a tile never becomes a second row`() {
        // The sheet puts the favourite first and the rest behind it. A favourite outside the
        // list was added and subtracted from nothing, so it came out as an extra row — and once
        // DATE was renamed "Fecha y hora" that row read word for word like the one under it,
        // badged "el que sueles usar" and opening the same sheet.
        for (stale in listOf(TriggerKind.DATE_TIME, TriggerKind.REPEAT_TIME)) {
            val ordered = kindsOrdered(stale)
            assertEquals(OFFERED_KINDS.size, ordered.size, "$stale added a row")
            assertEquals(ordered.distinct(), ordered, "$stale is in there twice")
            assertEquals(TriggerKind.DATE, ordered.first(), "$stale leads as the tile it became")
        }
        // A real favourite still leads, and nothing is lost behind it.
        val ordered = kindsOrdered(TriggerKind.PLACE)
        assertEquals(TriggerKind.PLACE, ordered.first())
        assertEquals(OFFERED_KINDS.toSet(), ordered.toSet())
        // And no favourite at all is the plain order.
        assertEquals(OFFERED_KINDS, kindsOrdered(null))
    }
}
