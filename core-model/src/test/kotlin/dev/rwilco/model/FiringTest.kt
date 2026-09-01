package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.reminder
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class FiringTest {

    private val tonight = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 30))
    private val past = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 8, 0))
    private val weekly = Trigger.AtTime(LocalTime.of(7, 30), setOf(DayOfWeek.MONDAY))
    private val place = Trigger.Location(40.4, -3.7, 200, Presence.INSIDE, "Casa")

    @Test
    fun `a snooze outranks the triggers and is marked as such`() {
        val snoozed = reminder(tonight).copy(snoozedUntil = local(2026, 8, 27, 15, 10))
        val next = nextFire(snoozed, now, zone, defaultTime) as NextFire.Scheduled
        assertEquals(local(2026, 8, 27, 15, 10), next.at)
        assertTrue(next.snoozed)
        assertEquals(tonight, next.trigger, "the row keeps the icon it is recognised by")
    }

    @Test
    fun `a snooze that has passed goes back to the triggers`() {
        val stale = reminder(tonight).copy(snoozedUntil = local(2026, 8, 27, 14, 0))
        val next = nextFire(stale, now, zone, defaultTime) as NextFire.Scheduled
        assertEquals(local(2026, 8, 27, 21, 30), next.at)
        assertFalse(next.snoozed)
    }

    @Test
    fun `dismissing finishes it, whatever the trigger could still do`() {
        val at = local(2026, 8, 27, 21, 31)
        assertEquals(Status.DONE, statusAfterDismissal(reminder(tonight), at, zone, defaultTime))
        assertEquals(Status.DONE, statusAfterDismissal(reminder(past), now, zone, defaultTime))
        // The ones that CAN come round again, and are not asked to: a place is the reason this
        // rule exists. "Al llegar a casa, saca la basura", dealt with, rang again that evening
        // on the way back through the same door.
        assertEquals(Status.DONE, statusAfterDismissal(reminder(weekly), at, zone, defaultTime))
        assertEquals(Status.DONE, statusAfterDismissal(reminder(place), at, zone, defaultTime))
    }

    @Test
    fun `asked to keep going, it stays for as long as something can ring`() {
        val at = local(2026, 8, 27, 21, 31)
        assertEquals(Status.ACTIVE, statusAfterDismissal(reminder(weekly).copy(recurrence = Recurrence.ByTrigger), at, zone, defaultTime))
        assertEquals(Status.ACTIVE, statusAfterDismissal(reminder(place).copy(recurrence = Recurrence.ByTrigger), at, zone, defaultTime))
        assertEquals(
            Status.ACTIVE,
            statusAfterDismissal(reminder(past, weekly).copy(recurrence = Recurrence.ByTrigger), now, zone, defaultTime),
            "one dead trigger does not end a reminder that still repeats",
        )
        // Repeating with nothing left to repeat is still finished.
        assertEquals(Status.DONE, statusAfterDismissal(reminder(past).copy(recurrence = Recurrence.ByTrigger), now, zone, defaultTime))
    }

    @Test
    fun `dismissing ignores a snooze that is still running`() {
        val snoozed = reminder(past).copy(recurrence = Recurrence.ByTrigger, snoozedUntil = now.plusSeconds(600))
        assertEquals(Status.DONE, statusAfterDismissal(snoozed, now, zone, defaultTime))
    }

    @Test
    fun `a missed firing is an armed moment with no ring to match it`() {
        val base = reminder(tonight)
        assertNull(missedFire(base, now), "nothing armed, nothing missed")
        assertNull(missedFire(base.copy(armedFor = now.plusSeconds(60)), now), "still to come")
        assertEquals(
            local(2026, 8, 27, 14, 0),
            missedFire(base.copy(armedFor = local(2026, 8, 27, 14, 0)), now),
            "the phone slept through it",
        )
        assertNull(
            missedFire(base.copy(armedFor = local(2026, 8, 27, 14, 0), lastFiredAt = local(2026, 8, 27, 14, 0)), now),
            "it rang; being ignored is not being missed",
        )
        assertEquals(
            local(2026, 8, 27, 14, 0),
            missedFire(base.copy(armedFor = local(2026, 8, 27, 14, 0), lastFiredAt = local(2026, 8, 20, 7, 30)), now),
            "the last ring was an older occurrence",
        )
        assertNull(missedFire(base.copy(armedFor = local(2026, 8, 27, 14, 0), status = Status.PAUSED), now))
    }

    @Test
    fun `a place ringing does not spend an appointment that has not happened`() {
        // "Al llegar a casa, o mañana a las nueve." The alarm armed is tomorrow's nine; the
        // arrival is today. Recording the ring against the armed moment would mark tomorrow
        // spent, and tomorrow would pass in silence.
        val arrived = local(2026, 8, 27, 18, 0)
        val appointment = local(2026, 8, 28, 9, 0)

        assertEquals(
            arrived,
            momentRungFor(arrived, armedFor = appointment, late = null, eventDriven = true),
            "a place happens when it happens, and speaks only for itself",
        )
        assertEquals(
            appointment,
            momentRungFor(appointment.minusMillis(400), armedFor = appointment, late = null, eventDriven = false),
            "an alarm a breath early still rang for its own moment",
        )
    }

    @Test
    fun `a firing the phone slept through is spent now, not when it should have been`() {
        // Three days off with a daily reminder: recording the ring against the missed moment
        // would leave the two days in between unspent, and it would ring its way back up.
        val missed = local(2026, 8, 24, 9, 0)
        val backOn = local(2026, 8, 27, 15, 0)

        assertEquals(backOn, momentRungFor(backOn, armedFor = missed, late = missed, eventDriven = false))
        assertEquals(backOn, momentRungFor(backOn, armedFor = missed, late = missed, eventDriven = true))
    }

    @Test
    fun `a catch-up does not spend the moment the re-arm has already moved on to`() {
        // The re-arm that noticed the missed nine o'clock has already written tomorrow's into
        // the row by the time the catch-up rings. Recording the ring against THAT would leave
        // tomorrow spent before it came, and a daily reminder would skip a day after every
        // night the phone was off.
        val missed = local(2026, 8, 27, 9, 0)
        val backOn = local(2026, 8, 27, 15, 0)
        val rearmedFor = local(2026, 8, 28, 9, 0)

        assertEquals(backOn, momentRungFor(backOn, armedFor = rearmedFor, late = missed, eventDriven = false))
        assertEquals(
            rearmedFor,
            momentRungFor(rearmedFor.minusMillis(400), armedFor = rearmedFor, late = null, eventDriven = false),
            "the ordinary alarm, arriving a breath early, still rings for its own moment",
        )
    }

    @Test
    fun `a sound or a buzz is carried by a notification whether or not one was ticked`() {
        // The channel is how the phone makes either of them, so unticking "notificación" and
        // keeping "sonido" is still a notification — a silent moment that only turns overdue
        // on Home is what it used to be.
        assertTrue(firingPlan(setOf(Action.SOUND)).notification)
        assertTrue(firingPlan(setOf(Action.VIBRATE)).notification)
        assertFalse(firingPlan(emptySet()).notification, "asked for nothing, nothing happens")
    }

    @Test
    fun `under all, the moments that passed after the missed one are still owed`() {
        // Off from eight in the evening until ten the next morning, with "todos" waiting on
        // 21:00 and then 09:00. Only the 21:00 was armed; the 09:00 would have been armed in
        // turn. Both are owed, in order, and a repeating rule is not — its next is still ahead.
        val first = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 0))
        val second = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 9, 0))
        val both = reminder(first, second, weekly).copy(ruleMatch = RuleMatch.ALL)
        val backOn = local(2026, 8, 28, 10, 0)
        assertEquals(
            listOf(Wake(local(2026, 8, 28, 9, 0), 1)),
            owedUnderAll(both, missed = local(2026, 8, 27, 21, 0), now = backOn, zone = zone, defaultTime = defaultTime),
        )
        assertEquals(emptyList<Wake>(), owedUnderAll(both.copy(firedRules = setOf(1)), local(2026, 8, 27, 21, 0), backOn, zone, defaultTime), "written down already")
        assertEquals(emptyList<Wake>(), owedUnderAll(both.copy(ruleMatch = RuleMatch.ANY), local(2026, 8, 27, 21, 0), backOn, zone, defaultTime), "under any, nothing accumulates")
        assertEquals(emptyList<Wake>(), owedUnderAll(both, local(2026, 8, 27, 21, 0), local(2026, 8, 28, 8, 0), zone, defaultTime), "not owed until it has passed")
    }

    @Test
    fun `under all, a stretch of the calendar that opened while the phone was off is owed too`() {
        // 21:00 armed, then a stretch from the 28th: its first morning came and went with the
        // phone off, and a set waiting on a day already gone would never complete.
        val first = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 0))
        val stretch = Trigger.DateRange(LocalDate.of(2026, 8, 28), LocalDate.of(2026, 8, 30))
        val both = reminder(first, stretch).copy(ruleMatch = RuleMatch.ALL)
        assertEquals(
            listOf(Wake(local(2026, 8, 28, 9, 0), 1)),
            owedUnderAll(both, missed = local(2026, 8, 27, 21, 0), now = local(2026, 8, 28, 10, 0), zone = zone, defaultTime = defaultTime),
        )
    }

    @Test
    fun `a full-screen alert keeps its notification but hands the noise to the screen`() {
        val plan = firingPlan(setOf(Action.FULL_SCREEN, Action.SOUND, Action.VIBRATE))
        assertTrue(plan.fullScreen)
        assertTrue(plan.notification, "the notification is what is left when the takeover is refused")
        assertTrue(plan.sound)
        assertFalse(plan.notificationSound, "the alert screen rings; the notification must not double it")
        assertFalse(plan.notificationVibrate)
    }

    @Test
    fun `without a full screen the notification carries the sound and the buzz`() {
        val plan = firingPlan(setOf(Action.NOTIFICATION, Action.SOUND, Action.VIBRATE))
        assertFalse(plan.fullScreen)
        assertTrue(plan.notificationSound)
        assertTrue(plan.notificationVibrate)
        val quiet = firingPlan(setOf(Action.NOTIFICATION))
        assertFalse(quiet.notificationSound)
        assertFalse(quiet.notificationVibrate)
    }


    // ---- a place, said once or said at every doorway --------------------------------------

    private val casa = Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa")

    @Test
    fun `a state has its say once a round, and a doorway at every doorway`() {
        val rang = local(2026, 8, 27, 19, 0)
        val fresh = reminder(casa)
        assertFalse(fresh.presenceAlreadyRang(casa, 0), "it has never rung")

        val ignored = fresh.copy(lastFiredAt = rang, lastFiredRule = 0)
        assertTrue(ignored.presenceAlreadyRang(casa, 0), "still at home is not a second thing happening")
        // A doorway is never spent this way: leaving and coming back is a second arrival.
        val doorway = casa.copy(onCrossing = true)
        assertFalse(ignored.presenceAlreadyRang(doorway, 0))

        // Dealing with it starts the next round for both.
        val dealt = ignored.copy(lastDealtAt = rang.plusSeconds(60))
        assertFalse(dealt.presenceAlreadyRang(casa, 0))
        assertFalse(dealt.presenceAlreadyRang(doorway, 0))
    }

    @Test
    fun `a sibling clock ringing does not spend a state place under any`() {
        // "En casa, o a las nueve." Nine rings and is swiped away without a hecho; at six the
        // person walks in. The ring being held against the place was a different rule's, and
        // under "cualquiera" nothing accumulates: the place has not had its say.
        val nine = local(2026, 8, 27, 9, 0)
        val either = reminder(casa, Trigger.TimeOfDay(LocalTime.of(9, 0))).copy(lastFiredAt = nine, lastFiredRule = 1)
        assertFalse(either.presenceAlreadyRang(casa, 0), "the clock rang, not the place")
        assertTrue(either.copy(lastFiredRule = 0).presenceAlreadyRang(casa, 0), "whereas its own ring is its say")
        // A row from before the column knows nothing, and is read as it always was.
        assertTrue(either.copy(lastFiredRule = null).presenceAlreadyRang(casa, 0))
    }

    @Test
    fun `the screen goes round and round only for the ones that asked to be insisted at`() {
        // The two sound tiles are one choice — once, or again until somebody answers — and on a
        // full-screen alert they used to be the same thing, because the screen looped whatever
        // it was given. "Sonido" is a promise about how many times you hear it.
        val once = firingPlan(setOf(Action.FULL_SCREEN, Action.NOTIFICATION, Action.SOUND))
        val insisting = firingPlan(setOf(Action.FULL_SCREEN, Action.NOTIFICATION, Action.SOUND_UNTIL_ANSWERED))
        assertTrue(once.sound, "it still makes a noise")
        assertFalse(loopsOnScreen(listOf(once)), "but it says it once")
        assertTrue(loopsOnScreen(listOf(insisting)))
        // A screen can be carrying several at once: one that insists is enough to keep it going,
        // the same way it takes the insistent tone if any of them wants it.
        assertTrue(loopsOnScreen(listOf(once, insisting)))
        assertFalse(loopsOnScreen(listOf(once, firingPlan(setOf(Action.VIBRATE)))))
        assertFalse(loopsOnScreen(emptyList()))
    }

    @Test
    fun `only a noise that keeps going asks to be silenced first`() {
        // The red button holds "hecho" out of reach to protect somebody half awake from
        // answering a noise instead of a reminder. That argument only holds while there is
        // still a noise when the thumb arrives.
        val once = firingPlan(setOf(Action.FULL_SCREEN, Action.SOUND))
        val insisting = firingPlan(setOf(Action.FULL_SCREEN, Action.SOUND_UNTIL_ANSWERED))
        val buzz = firingPlan(setOf(Action.FULL_SCREEN, Action.VIBRATE))
        val silent = firingPlan(setOf(Action.FULL_SCREEN, Action.NOTIFICATION))
        // A single tone is over in a second or two; the screen went on wearing the red button
        // for the rest of the minute.
        assertTrue(once.sound, "it does make a noise")
        assertFalse(asksToBeSilenced(listOf(once)), "but not one that is still going")
        // The buzz is built as a whole minute of waveform, whatever its rhythm, and the
        // insistent tone loops for as long.
        assertTrue(asksToBeSilenced(listOf(buzz)))
        assertTrue(asksToBeSilenced(listOf(insisting)))
        // One tone once, beside a buzz: there is a noise to answer, and it is the buzz.
        assertTrue(asksToBeSilenced(listOf(once, buzz)))
        assertFalse(asksToBeSilenced(listOf(silent)))
        assertFalse(asksToBeSilenced(emptyList()))
    }

    @Test
    fun `the hours somebody is asleep take the noise out of a firing`() {
        // Up from 08:00 to 23:30 on a weekday, which is the default.
        val shape = DayShape.DEFAULT
        fun at(h: Int, m: Int = 0) = LocalDateTime.of(2026, 8, 27, h, m).atZone(zone).toInstant()

        // Rung at four in the morning, seen at four in the morning: silent.
        assertTrue(hushedByTheHour(at(4), at(4), zone, shape))
        // Rung at four, and the phone came back at nine: the moment is four o'clock's, and
        // ringing it over breakfast is the three-in-the-morning alarm arriving late.
        assertTrue(hushedByTheHour(at(4), at(9), zone, shape), "a night moment stays quiet when it is seen")
        // A moment from the middle of the day, shown in the middle of the night: also silent —
        // being awake is asked of the hour it arrives as well.
        assertTrue(hushedByTheHour(at(12), at(3), zone, shape))
        // The ordinary case, and the only one that keeps its noise.
        assertFalse(hushedByTheHour(at(12), at(12), zone, shape))

        // What "silent" is: the card, the screen and every answer stay; the noise goes, and so
        // does the coming back for more of it.
        val loud = firingPlan(setOf(Action.FULL_SCREEN, Action.NOTIFICATION, Action.SOUND_UNTIL_ANSWERED, Action.VIBRATE))
        val quiet = loud.hushed()
        assertTrue(quiet.fullScreen && quiet.notification, "it is still shown")
        assertFalse(quiet.sound || quiet.vibrate || quiet.insistent)
        assertFalse(asksToBeSilenced(listOf(quiet)), "and there is nothing left to silence")
    }

    @Test
    fun `an arrival minutes after a sibling rule's ring is not an echo of it`() {
        // "Al llegar a casa, o a las 21:00": the nine o'clock rang, and the doorway three
        // minutes later is a different thing that happened, not the same arrival seen twice.
        assertFalse(isPlaceEcho(lastFiredAt = now.minusSeconds(180), lastFiredRule = 1, ruleIndex = 0, now = now))
    }

    @Test
    fun `the same arrival seen by the second eye is an echo, and only for a while`() {
        // The geofence rang this circle; the watch's own look reports the same doorway.
        assertTrue(isPlaceEcho(now.minusSeconds(299), lastFiredRule = 0, ruleIndex = 0, now = now))
        assertFalse(isPlaceEcho(now.minusSeconds(300), lastFiredRule = 0, ruleIndex = 0, now = now), "five minutes on, it may ring again")
        assertFalse(isPlaceEcho(null, lastFiredRule = null, ruleIndex = 0, now = now), "never rang, nothing to echo")
    }

    @Test
    fun `a ring with no rule behind it holds every circle of its own pin briefly`() {
        // A snooze's crossing rings with no rule behind it (the row records null); the same
        // pin registered as a rule circle hands over its own ENTER seconds later — one
        // doorway, one ring.
        assertTrue(isPlaceEcho(now.minusSeconds(5), lastFiredRule = null, ruleIndex = 0, now = now))
    }
}
