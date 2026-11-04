package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The trigger-contract round (0.200.0–0.204.0, 2026-11-04) held to its edges: the time, snooze
 * and recurrence core across midnight, the weekend's later hours, both clock changes, the ends of
 * months and a leap year, legacy rows, routines and paused rows.
 *
 * Calendar: 2026-08-27 is a Thursday, 08-29 a Saturday, 08-31 a Monday. Madrid falls back on
 * 2026-10-25 (03:00 → 02:00) and springs forward on 2027-03-28 (02:00 → 03:00); 2028 is a leap
 * year. The default day: up at eight, to bed at half eleven; the weekend (Friday 20:30 to Sunday
 * 22:00) up at ten and to bed at half one.
 */
class TimeReliabilityTest {

    private fun t(hour: Int, minute: Int = 0): LocalTime = LocalTime.of(hour, minute)

    private val shape = DayShape.DEFAULT
    private val terms = SnoozeTerms(shape, DayParts(), DEFAULT_SNOOZE_MINUTES)
    private val home = Trigger.Location(40.4169, -3.7035, radiusM = 200, presence = Presence.INSIDE, label = "Casa")

    private fun row(vararg rules: TriggerRule, recurrence: Recurrence = Recurrence.None, match: RuleMatch = RuleMatch.ANY, createdAt: Instant) =
        Reminder(id = "r1", text = "x", rules = rules.toList(), recurrence = recurrence, ruleMatch = match, createdAt = createdAt, updatedAt = createdAt)

    private fun Simulation.Ring.wall(): LocalDateTime = local(zone)

    // --- The waking day ("mañana") ---

    @Test
    fun `sunday night past midnight is still sunday, and monday starts at the weekday's hours`() {
        val monday = LocalDate.of(2026, 8, 31)
        // The weekend ended at ten on Sunday, so Sunday's bedtime is the week's half eleven and the
        // middle of the night is a quarter to four.
        assertEquals(monday.minusDays(1), shape.wakingDate(monday.atTime(0, 30)))
        assertEquals(monday.minusDays(1), shape.wakingDate(monday.atTime(3, 40)))
        assertEquals(monday, shape.wakingDate(monday.atTime(3, 50)))
        assertEquals(local(2026, 8, 31, 9, 0), Snooze.TOMORROW_MORNING.until(local(2026, 8, 31, 0, 30), zone, terms))
        assertEquals(local(2026, 8, 31, 9, 0), Snooze.TOMORROW_MORNING.until(local(2026, 8, 30, 23, 0), zone, terms))
    }

    @Test
    fun `a friday night out still wakes up to saturday's late morning`() {
        // One in the morning on Saturday is Friday's night: "mañana por la mañana" is Saturday,
        // and the weekend gets up at ten.
        assertEquals(local(2026, 8, 29, 10, 0), Snooze.TOMORROW_MORNING.until(local(2026, 8, 29, 1, 0), zone, terms))
        // Past the middle of that night (a quarter to six) it is Saturday already, and tomorrow is Sunday.
        assertEquals(local(2026, 8, 30, 10, 0), Snooze.TOMORROW_MORNING.until(local(2026, 8, 29, 6, 0), zone, terms))
    }

    @Test
    fun `tomorrow morning across both clock changes is the weekend's ten o'clock`() {
        // Saturday night into the fall-back Sunday: still Saturday at half past midnight.
        val fallBack = Snooze.TOMORROW_MORNING.until(local(2026, 10, 25, 0, 30), zone, terms)
        assertEquals(local(2026, 10, 25, 10, 0), fallBack)
        assertEquals(LocalDateTime.of(2026, 10, 25, 10, 0), fallBack.atZone(zone).toLocalDateTime())
        // And into the spring-forward Sunday, an hour before the clocks jump.
        val springForward = Snooze.TOMORROW_MORNING.until(local(2027, 3, 28, 1, 0), zone, terms)
        assertEquals(LocalDateTime.of(2027, 3, 28, 10, 0), springForward.atZone(zone).toLocalDateTime())
        assertFalse(hushedByTheHour(springForward, springForward, zone, shape))
        assertEquals(LocalDateTime.of(2027, 3, 28, 10, 0), shape.awakeHourOn(LocalDate.of(2027, 3, 28), t(9)))
    }

    @Test
    fun `a weekday named at a quarter past midnight is the morning about to come`() {
        // Said at 00:15 on a Tuesday, still in Monday's evening: "el martes por la mañana" is in a
        // few hours, not a week away.
        val smallHours = local(2026, 9, 1, 0, 15)
        val tuesdayMorning = SnoozeSpec.On(SnoozeDay.Weekday(DayOfWeek.TUESDAY), SnoozeHour.Part(SnoozePart.MORNING))
        assertEquals(local(2026, 9, 1, 9, 0), tuesdayMorning.until(smallHours, zone, terms))
        // At ten on that Tuesday it is the next one.
        assertEquals(local(2026, 9, 8, 9, 0), tuesdayMorning.until(local(2026, 9, 1, 10, 0), zone, terms))
        // A weekend morning, asked on Friday night past midnight, is that Saturday's at ten.
        val weekendMorning = SnoozeSpec.On(SnoozeDay.Weekend, SnoozeHour.Part(SnoozePart.MORNING))
        assertEquals(local(2026, 8, 29, 10, 0), weekendMorning.until(local(2026, 8, 29, 0, 30), zone, terms))
    }

    @Test
    fun `manana written past midnight in a leap year lands on the 29th of february`() {
        val tomorrow = TriggerRule(Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.DAYS), time = t(9)))
        fun settled(at: Instant) = (settleRelativeDates(listOf(tomorrow), at, zone, shape).single().trigger as Trigger.AtDateTime).at
        assertEquals(LocalDateTime.of(2028, 2, 29, 9, 0), settled(local(2028, 2, 28, 18, 0)))
        assertEquals(LocalDateTime.of(2028, 2, 29, 9, 0), settled(local(2028, 2, 29, 0, 30)))
        assertEquals(LocalDateTime.of(2028, 3, 1, 9, 0), settled(local(2028, 3, 1, 0, 30)))
        // The chip says the same thing the words do.
        val leapDay = LocalDate.of(2028, 2, 29)
        assertEquals(leapDay, DateShortcut.TOMORROW.on(leapDay, shape.wakingDate(leapDay.atTime(0, 30))))
        // And "hoy" stays the calendar's.
        val today = TriggerRule(Trigger.RelativeDate(RelativeDay.In(0, RelativeUnit.DAYS), time = t(9)))
        assertEquals(LocalDateTime.of(2028, 3, 1, 9, 0), (settleRelativeDates(listOf(today), local(2028, 3, 1, 0, 30), zone, shape).single().trigger as Trigger.AtDateTime).at)
    }

    @Test
    fun `manana typed on a friday night out is saturday, and pasado manana is sunday`() {
        val smallHours = local(2026, 8, 29, 0, 30)
        fun dateOf(words: String): LocalDate {
            val once = readWhen(words, smallHours, zone, shape = shape)!!.understood as Understood.Once
            return (settleRelativeDates(listOf(TriggerRule(once.trigger)), smallHours, zone, shape).single().trigger as Trigger.AtDateTime).at.toLocalDate()
        }
        assertEquals(LocalDate.of(2026, 8, 29), dateOf("llamar a mamá mañana a las 12"))
        assertEquals(LocalDate.of(2026, 8, 30), dateOf("llamar a mamá pasado mañana a las 12"))
    }

    @Test
    fun `somebody up at night has their small hours read as the night before`() {
        val nights = DayShape(hours = AwakeHours(wake = t(20), sleep = t(12), weekendWake = t(20), weekendSleep = t(12)))
        val tuesday = LocalDate.of(2026, 9, 1)
        // Up from eight in the evening to noon: at ten in the morning it is still Monday's night,
        // and halfway between noon and eight it is Tuesday.
        assertEquals(tuesday.minusDays(1), nights.wakingDate(tuesday.atTime(10, 0)))
        assertEquals(tuesday, nights.wakingDate(tuesday.atTime(17, 0)))
        // Nine in the morning is inside their night's waking hours, so it is not moved.
        assertEquals(tuesday.atTime(9, 0), nights.awakeHourOn(tuesday, t(9)))
    }

    // --- Moments the app chooses: never asleep ---

    @Test
    fun `a stretch of dates over a weekend rings at each day's waking hour`() {
        val created = local(2026, 8, 27, 15, 0)
        val weekend = Simulation(row(TriggerRule(Trigger.DateRange(LocalDate.of(2026, 8, 29), LocalDate.of(2026, 8, 31))), createdAt = created), created)
        assertEquals(
            listOf(LocalDateTime.of(2026, 8, 29, 10, 0), LocalDateTime.of(2026, 8, 30, 10, 0), LocalDateTime.of(2026, 8, 31, 9, 0)),
            weekend.run(local(2026, 9, 2, 0, 0)).map { it.wall() },
        )
        // Across the end of a leap February: the 29th is a day like any other.
        val leap = local(2028, 2, 20, 12, 0)
        val february = Simulation(row(TriggerRule(Trigger.DateRange(LocalDate.of(2028, 2, 28), LocalDate.of(2028, 3, 1))), createdAt = leap), leap)
        assertEquals(
            listOf(LocalDateTime.of(2028, 2, 28, 9, 0), LocalDateTime.of(2028, 2, 29, 9, 0), LocalDateTime.of(2028, 3, 1, 9, 0)),
            february.run(local(2028, 3, 3, 0, 0)).map { it.wall() },
        )
    }

    @Test
    fun `a date with no hour on a clock-change sunday opens at the weekend's waking hour`() {
        val day = nextFireOf(Trigger.OnDate(LocalDate.of(2026, 10, 25)), "r1", local(2026, 10, 20, 12, 0), zone, defaultTime) as NextFire.Scheduled
        assertEquals(LocalDateTime.of(2026, 10, 25, 10, 0), day.at.atZone(zone).toLocalDateTime())
        val spring = nextFireOf(Trigger.OnDate(LocalDate.of(2027, 3, 28)), "r1", local(2027, 3, 20, 12, 0), zone, defaultTime) as NextFire.Scheduled
        assertEquals(LocalDateTime.of(2027, 3, 28, 10, 0), spring.at.atZone(zone).toLocalDateTime())
    }

    // --- A stretch open when it is written ---

    @Test
    fun `a stretch written after bedtime waits for the next one, and on a friday night it rings now`() {
        val lateStretch = TriggerRule(Trigger.Interval(t(23), t(1)))
        // Thursday at twenty to midnight is past the week's bedtime: nothing at once.
        val thursday = local(2026, 8, 27, 23, 40)
        assertEquals(Wake(local(2026, 8, 28, 23, 0), 0), nextWake(row(lateStretch, createdAt = thursday), thursday, zone, defaultTime))
        // Friday is the weekend's night, up until half one: the minute after it is written.
        val friday = local(2026, 8, 28, 23, 40)
        assertEquals(Wake(local(2026, 8, 28, 23, 41), 0), nextWake(row(lateStretch, createdAt = friday), friday, zone, defaultTime))
    }

    @Test
    fun `a weekend range written on its last evening has the rest of it, and after bedtime nothing`() {
        val range = listOf(TriggerRule(Trigger.DateRange(LocalDate.of(2026, 8, 29), LocalDate.of(2026, 8, 30))))
        // Sunday at eleven: the weekend ended at ten, but bedtime is half eleven.
        val sunday = local(2026, 8, 30, 23, 0)
        assertEquals(Wake(local(2026, 8, 30, 23, 1), 0), nextWake(row(*range.toTypedArray(), createdAt = sunday), sunday, zone, defaultTime))
        assertTrue(warnings(range, sunday, zone, defaultTime).none { it is ValidationWarning.InPast })
        // A quarter to midnight is past it: the range has nothing left, and the editor says so.
        val late = local(2026, 8, 30, 23, 45)
        assertNull(nextWake(row(*range.toTypedArray(), createdAt = late), late, zone, defaultTime))
        assertTrue(warnings(range, late, zone, defaultTime).any { it is ValidationWarning.InPast })
    }

    // --- «Desde que suena»: the rule's own hour ---

    @Test
    fun `desde que suena after a catch-up counts from the hour it was for, not from the late ring`() {
        // "A las 9, cada 24 h desde que suena", the phone off from the evening until eleven: the
        // ring is late, and the next one is still tomorrow at nine.
        val created = local(2026, 8, 27, 15, 0)
        val phone = Simulation(row(TriggerRule(Trigger.TimeOfDay(t(9))), recurrence = Recurrence.After(24, RecurrenceUnit.HOURS, RecurrenceFrom.RANG), createdAt = created), created)
        phone.now = local(2026, 8, 27, 20, 0)
        val late = phone.sleepUntil(local(2026, 8, 28, 11, 0)).single()
        assertEquals(local(2026, 8, 28, 9, 0), late.late)
        assertEquals(Wake(local(2026, 8, 29, 9, 0), 0), phone.arm())
    }

    @Test
    fun `desde que suena every 24 hours rings once a day across the fall-back night`() {
        val created = local(2026, 10, 23, 12, 0)
        val phone = Simulation(row(TriggerRule(Trigger.TimeOfDay(t(9))), recurrence = Recurrence.After(24, RecurrenceUnit.HOURS, RecurrenceFrom.RANG), createdAt = created), created)
        assertEquals(
            (24..27).map { LocalDateTime.of(2026, 10, it, 9, 0) },
            phone.run(local(2026, 10, 27, 12, 0)).map { it.wall() },
        )
    }

    @Test
    fun `under todos and desde que suena a set made again every day rings every day at its hour`() {
        val created = local(2026, 8, 27, 12, 0)
        val r = row(TriggerRule(home), TriggerRule(Trigger.TimeOfDay(t(21))), recurrence = Recurrence.After(1, RecurrenceUnit.DAYS, RecurrenceFrom.RANG), match = RuleMatch.ALL, createdAt = created)
        val phone = Simulation(r, created)
        for (day in 27..29) {
            phone.now = local(2026, 8, day, 20, 0)
            assertNull(phone.arrive(0), "being home is a note on the $day")
            assertEquals(local(2026, 8, day, 21, 0), phone.step()!!.at, "the $day")
            assertEquals(emptySet<Int>(), phone.reminder.firedRules)
        }
    }

    // --- «Desde el hecho» ---

    @Test
    fun `a hecho at a quarter past midnight on a bedtime reminder answers the night it rang`() {
        val created = local(2026, 8, 27, 12, 0)
        val phone = Simulation(row(TriggerRule(Trigger.TimeOfDay(t(23, 15))), recurrence = Recurrence.After(1, RecurrenceUnit.DAYS), createdAt = created), created)
        assertEquals(local(2026, 8, 27, 23, 15), phone.step()!!.at)
        phone.now = local(2026, 8, 28, 0, 15)
        phone.deal(Simulation.Deal.Done)
        assertEquals(Wake(local(2026, 8, 28, 23, 15), 0), phone.arm())
        // And on the fall-back night, answered in the hour that happens twice.
        val autumn = local(2026, 10, 24, 12, 0)
        val dst = Simulation(row(TriggerRule(Trigger.TimeOfDay(t(23, 15))), recurrence = Recurrence.After(1, RecurrenceUnit.DAYS), createdAt = autumn), autumn)
        assertEquals(local(2026, 10, 24, 23, 15), dst.step()!!.at)
        dst.now = local(2026, 10, 25, 2, 30)
        dst.deal(Simulation.Deal.Done)
        assertEquals(LocalDateTime.of(2026, 10, 25, 23, 15), dst.arm()!!.at.atZone(zone).toLocalDateTime())
    }

    @Test
    fun `a monthly come-back from the 31st steps through the short months while nobody answers`() {
        // "El 31 a las 20:00, y vuelve cada mes desde el hecho", never answered: a month after each
        // ring, clamped to the month's last day the way a calendar month is, and across the
        // spring-forward Sunday on the 28th of March.
        val created = local(2027, 1, 20, 12, 0)
        val phone = Simulation(row(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2027, 1, 31, 20, 0))), recurrence = Recurrence.After(1, RecurrenceUnit.MONTHS), createdAt = created), created)
        val rings = phone.run(local(2027, 4, 30, 12, 0))
        assertEquals(
            listOf(
                LocalDateTime.of(2027, 1, 31, 20, 0),
                LocalDateTime.of(2027, 2, 28, 9, 0),
                LocalDateTime.of(2027, 3, 28, 9, 0),
                LocalDateTime.of(2027, 4, 28, 9, 0),
            ),
            rings.map { it.wall() },
        )
        assertEquals(listOf(0, null, null, null), rings.map { it.ruleIndex }, "the come-back is the reminder itself, not its spent rule")
        assertNull(nextFire(phone.reminder, phone.now, zone, defaultTime), "Home keeps it with the ring that waits")
    }

    @Test
    fun `a yearly come-back from the 29th of february lands on the 28th`() {
        val r = row(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2028, 2, 29, 10, 0))), recurrence = Recurrence.After(1, RecurrenceUnit.YEARS), createdAt = local(2028, 2, 1, 12, 0))
            .copy(lastFiredAt = local(2028, 2, 29, 10, 0), lastFiredRule = 0)
        assertEquals(Wake(local(2029, 2, 28, 9, 0), null), nextWake(r, local(2028, 2, 29, 11, 0), zone, defaultTime))
    }

    @Test
    fun `the come-back and the safety net speak at different moments, once each per ring`() {
        val created = local(2026, 8, 27, 12, 0)
        val phone = Simulation(row(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 0))), recurrence = Recurrence.After(1, RecurrenceUnit.DAYS), createdAt = created), created)
        assertEquals(local(2026, 8, 27, 21, 0), phone.step()!!.at)
        val net = phone.reminder.netDue(phone.now, zone, defaultTime, SafetyNetSettings())!!
        assertEquals(NetWord.LET_GO, net.word)
        assertEquals(local(2026, 8, 27, 21, 0), net.about)
        val back = phone.arm()!!
        assertEquals(Wake(local(2026, 8, 28, 9, 0), null), back)
        assertTrue(net.at < back.at, "the net (${net.at}) comes before the come-back (${back.at})")
        // Once the net has spoken it says nothing more about that ring.
        assertNull(phone.reminder.copy(nudgedAt = net.at).netDue(net.at, zone, defaultTime, SafetyNetSettings()))
        // The come-back is a ring of its own, and the net has a word about it in turn.
        val second = phone.step()!!
        assertEquals(local(2026, 8, 28, 9, 0), second.at)
        assertEquals(second.at, phone.reminder.copy(nudgedAt = net.at).netDue(phone.now, zone, defaultTime, SafetyNetSettings())!!.about)
    }

    @Test
    fun `a come-back the phone slept through rings once late and is not held for ever`() {
        val created = local(2026, 8, 27, 12, 0)
        val phone = Simulation(row(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 0))), recurrence = Recurrence.After(1, RecurrenceUnit.DAYS), createdAt = created), created)
        phone.step()
        assertEquals(Wake(local(2026, 8, 28, 9, 0), null), phone.arm())
        val late = phone.sleepUntil(local(2026, 8, 28, 12, 0)).single()
        assertEquals(local(2026, 8, 28, 9, 0), late.late)
        assertNull(missedFire(phone.reminder, phone.now), "rung, so no longer owed")
        assertEquals(Wake(local(2026, 8, 29, 9, 0), null), phone.arm())
    }

    @Test
    fun `a hecho ends the come-back, and the span counts from it`() {
        val created = local(2026, 8, 27, 12, 0)
        val phone = Simulation(row(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 0))), recurrence = Recurrence.After(2, RecurrenceUnit.DAYS), createdAt = created), created)
        phone.step()
        assertEquals(Wake(local(2026, 8, 29, 9, 0), null), phone.arm())
        assertEquals(local(2026, 8, 29, 9, 0), phone.step()!!.at)
        phone.now = local(2026, 8, 29, 9, 30)
        phone.deal(Simulation.Deal.Done)
        assertFalse(phone.reminder.unansweredComesBack)
        assertEquals(Wake(local(2026, 8, 31, 9, 0), null), phone.arm())
    }

    @Test
    fun `paused rows and routines keep their own paths past an unanswered ring`() {
        val rang = row(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 0))), recurrence = Recurrence.After(1, RecurrenceUnit.DAYS), createdAt = local(2026, 8, 20, 12, 0))
            .copy(lastFiredAt = local(2026, 8, 27, 21, 0), lastFiredRule = 0)
        assertNull(nextWake(rang.copy(status = Status.PAUSED), local(2026, 8, 28, 10, 0), zone, defaultTime))
        // A routine's ring is its deadline, rung or not; nothing about it comes back on a span.
        val routine = rang.copy(rules = listOf(TriggerRule(Trigger.TimeOfDay(t(18)))), recurrence = Recurrence.Since(3, RecurrenceUnit.DAYS))
        assertFalse(routine.unansweredComesBack)
        assertNull(routine.unansweredRest(zone, DEFAULT_DAY_START, shape))
        assertEquals(routine.recurrenceMoment(local(2026, 8, 28, 10, 0), zone, DEFAULT_DAY_START, shape)?.let { Wake(it, null) }, nextWake(routine, local(2026, 8, 28, 10, 0), zone, defaultTime))
        // «Desde que suena» was never waiting for an answer: its rest is the ring's, as it was.
        val rangFrom = rang.copy(recurrence = Recurrence.After(1, RecurrenceUnit.DAYS, RecurrenceFrom.RANG))
        assertFalse(rangFrom.unansweredComesBack)
    }

    @Test
    fun `a span adopted from a dated rule's hour comes back at that hour after the hecho`() {
        val rules = listOf(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 11, 26, 20, 0))))
        val monthly = Recurrence.After(1, RecurrenceUnit.MONTHS).adoptingHourOf(rules)
        val created = local(2026, 11, 20, 12, 0)
        val phone = Simulation(Reminder(id = "r1", text = "x", rules = rules, recurrence = monthly, createdAt = created, updatedAt = created), created)
        assertEquals(local(2026, 11, 26, 20, 0), phone.step()!!.at)
        phone.now = local(2026, 11, 26, 20, 5)
        phone.deal(Simulation.Deal.Done)
        assertEquals(Wake(local(2026, 12, 26, 20, 0), null), phone.arm())
    }

    // --- Snoozes that cost rings ---

    @Test
    fun `next week from a daily nine o'clock swallows seven mornings across the clock change`() {
        val daily = row(TriggerRule(Trigger.TimeOfDay(t(9))), createdAt = local(2026, 10, 1, 12, 0))
            .copy(lastFiredAt = local(2026, 10, 21, 9, 0), lastFiredRule = 0)
        val asked = local(2026, 10, 21, 9, 1)
        val week = Snooze.NEXT_WEEK.until(asked, zone, terms)
        assertEquals(LocalDateTime.of(2026, 10, 28, 9, 1), week.atZone(zone).toLocalDateTime())
        assertEquals(7, daily.ringsSkippedUntil(week, asked, zone, defaultTime))
    }

    @Test
    fun `a snoozed ring of a legacy row keeps no rule, and its state place reads as it always did`() {
        // Written before lastFiredRule existed: the ring may have been the place's, so it is.
        val legacy = row(TriggerRule(home), TriggerRule(Trigger.TimeOfDay(t(21))), createdAt = local(2026, 8, 1, 12, 0))
            .copy(lastFiredAt = local(2026, 8, 27, 21, 0), lastFiredRule = null)
        assertTrue(legacy.presenceAlreadyRang(home, 0))
        assertNull(legacy.copy(snoozedUntil = local(2026, 8, 27, 21, 10)).ruleRungBy(null, viaSnoozePlace = false))
        // A hecho, or bringing it back from a pause, starts the next round as ever.
        assertFalse(legacy.copy(lastDealtAt = local(2026, 8, 27, 22, 0)).presenceAlreadyRang(home, 0))
        assertFalse(legacy.copy(resumedAt = local(2026, 8, 27, 22, 0)).presenceAlreadyRang(home, 0))
    }

    // --- Legacy folds ---

    @Test
    fun `a legacy monday nine o'clock or arriving home rings every monday, across the clock change`() {
        val created = local(2026, 8, 20, 12, 0)
        val door = home.copy(onCrossing = true)
        val legacy = row(TriggerRule(Trigger.AtTime(t(9), setOf(DayOfWeek.MONDAY))), TriggerRule(door), createdAt = created).foldRepeats(zone)
        assertEquals(listOf(TriggerRule(Trigger.TimeOfDay(t(9), setOf(DayOfWeek.MONDAY))), TriggerRule(door)), legacy.rules)
        assertTrue(legacy.recurrence is Recurrence.Calendar)
        assertEquals(legacy, legacy.foldRepeats(zone), "folding again changes nothing")
        val phone = Simulation(legacy, local(2026, 10, 15, 12, 0))
        assertEquals(
            listOf(LocalDateTime.of(2026, 10, 19, 9, 0), LocalDateTime.of(2026, 10, 26, 9, 0), LocalDateTime.of(2026, 11, 2, 9, 0)),
            phone.run(local(2026, 11, 3, 12, 0)).map { it.wall() },
        )
    }

    @Test
    fun `a legacy row with two repeats under cualquiera keeps both hours and its indices`() {
        val created = local(2026, 8, 20, 12, 0)
        val legacy = row(
            TriggerRule(Trigger.AtTime(t(9), setOf(DayOfWeek.MONDAY))),
            TriggerRule(Trigger.Repeat(startsOn = LocalDate.of(2026, 8, 1), unit = RepeatUnit.DAY, time = t(21))),
            TriggerRule(home),
            createdAt = created,
        ).copy(armedFor = local(2026, 8, 27, 21, 0), armedRule = 1, lastFiredRule = 2, lastFiredAt = local(2026, 8, 26, 19, 0))
        val folded = legacy.foldRepeats(zone)
        assertEquals(listOf(Trigger.TimeOfDay(t(9), setOf(DayOfWeek.MONDAY)), Trigger.TimeOfDay(t(21)), home), folded.triggers)
        assertEquals(1, folded.armedRule)
        assertEquals(local(2026, 8, 27, 21, 0), folded.armedFor)
        assertEquals(2, folded.lastFiredRule)
        assertEquals(folded, folded.foldRepeats(zone))
    }

    // --- Sets under "todos" re-read at completion ---

    @Test
    fun `todos with a stretch across midnight counts the evening's tick in the small hours`() {
        val created = local(2026, 8, 27, 15, 0)
        val r = row(TriggerRule(Trigger.Interval(t(22), t(2))), TriggerRule(Trigger.TimeOfDay(t(1))), match = RuleMatch.ALL, createdAt = created)
        val phone = Simulation(r, created)
        assertEquals(listOf(LocalDateTime.of(2026, 8, 28, 1, 0)), phone.run(local(2026, 8, 29, 12, 0)).map { it.wall() })
    }

    @Test
    fun `todos with an hour that never falls inside its stretch never completes`() {
        val created = local(2026, 8, 27, 15, 0)
        val r = row(TriggerRule(Trigger.Interval(t(22), t(2))), TriggerRule(Trigger.TimeOfDay(t(21))), match = RuleMatch.ALL, createdAt = created)
        val phone = Simulation(r, created)
        assertTrue(phone.run(local(2026, 8, 31, 12, 0)).isEmpty())
        assertTrue(phone.noted.size >= 6, "both still asked every day: ${phone.noted}")
    }

    @Test
    fun `todos with the weekend and an hour, written on friday, rings on saturday at the hour`() {
        val friday = local(2026, 8, 28, 15, 0)
        val r = row(TriggerRule(Trigger.Weekday(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))), TriggerRule(Trigger.TimeOfDay(t(20))), match = RuleMatch.ALL, createdAt = friday)
        val phone = Simulation(r, friday)
        assertEquals(listOf(LocalDateTime.of(2026, 8, 29, 20, 0)), phone.run(local(2026, 8, 30, 23, 0)).map { it.wall() })
    }

    @Test
    fun `a set's ticks are only re-read when the moment is given, and only under todos`() {
        val r = row(TriggerRule(Trigger.Weekday(setOf(DayOfWeek.FRIDAY))), TriggerRule(Trigger.TimeOfDay(t(20))), match = RuleMatch.ALL, createdAt = local(2026, 8, 20, 12, 0))
            .copy(firedRules = setOf(1))
        // Asked without the moment (an older caller), a tick is a tick, as it always was.
        assertEquals(FiringOutcome.Ring, outcomeOfFiring(r, 0))
        // With the moment, Saturday's 20:00 does not complete Friday's opening.
        assertEquals(FiringOutcome.Wait(setOf(0)), outcomeOfFiring(r, 0, local(2026, 8, 28, 8, 0), zone))
        // Nothing accumulates anywhere else, and the ring writes nothing there.
        assertEquals(FiringOutcome.Ring, outcomeOfFiring(r.copy(ruleMatch = RuleMatch.ANY), 0, local(2026, 8, 28, 8, 0), zone))
        assertNull(r.copy(ruleMatch = RuleMatch.ANY).ticksOnRing)
        assertNull(r.copy(ruleMatch = RuleMatch.TOGETHER).ticksOnRing)
        assertNull(r.copy(rules = r.rules.take(1)).ticksOnRing, "one rule is one rule")
        assertEquals(setOf(0, 1), r.ticksOnRing)
    }

    // --- A draw on the days its rule allows ---

    @Test
    fun `a daily draw fenced to the 31st rings on each month's last day instead`() {
        // "El 31" is the month's last day where it has fewer: the 30th of September and November.
        val created = local(2026, 8, 20, 12, 0)
        val rule = TriggerRule(Trigger.Random(1, Period.DAY, t(10), t(20)), listOf(Condition.OnMonthDays(setOf(31))))
        val phone = Simulation(row(rule, createdAt = created), created)
        val rings = phone.run(local(2027, 1, 1, 0, 0))
        assertEquals(
            listOf(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 31)),
            rings.map { it.wall().toLocalDate() },
        )
        assertTrue(rings.all { it.wall().toLocalTime() >= t(10) && it.wall().toLocalTime() < t(20) })
    }

    @Test
    fun `a weekly draw fenced to one week makes both its draws inside it`() {
        val created = local(2026, 8, 27, 12, 0)
        val rule = TriggerRule(Trigger.Random(2, Period.WEEK, t(10), t(20)), listOf(Condition.DateRange(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20))))
        val phone = Simulation(row(rule, createdAt = created), created)
        val days = phone.run(local(2026, 10, 15, 0, 0)).map { it.wall().toLocalDate() }
        assertEquals(2, days.size, "$days")
        assertTrue(days.all { it in LocalDate.of(2026, 9, 14)..LocalDate.of(2026, 9, 20) }, "$days")
    }

    // --- Resuming ---

    @Test
    fun `resumed after its window closed in the fall-back week, the round's close is ahead of the clock`() {
        val r = row(TriggerRule(Trigger.TimeOfDay(t(19))), TriggerRule(home), match = RuleMatch.ALL, createdAt = local(2026, 10, 20, 12, 0))
            .copy(deadline = Deadline.Window(t(18), t(22)), expiresAt = local(2026, 10, 23, 22, 0), status = Status.PAUSED, firedRules = setOf(1))
        val back = local(2026, 10, 26, 10, 0)
        val round = r.roundOnResume(back, zone, defaultTime)!!
        assertEquals(emptySet<Int>(), round.firedRules)
        assertEquals(local(2026, 10, 26, 22, 0), round.expiresAt)
        assertEquals(Status.ACTIVE, round.status)
    }

    @Test
    fun `a question the routine can always put is not said to be never asked`() {
        // A four-day span has a window of a whole day: every daily hour falls in it.
        val routine = row(TriggerRule(Trigger.TimeOfDay(t(6))), recurrence = Recurrence.Since(4, RecurrenceUnit.DAYS), createdAt = local(2026, 8, 20, 12, 0))
            .copy(lastDealtAt = local(2026, 8, 27, 9, 30))
        assertEquals(emptyList<Int>(), routine.questionsNeverAsked(zone, defaultTime))
        assertNotNull(routine.routineDeadline(zone))
    }
}
