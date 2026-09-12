package dev.rwilco.model

import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * A contact — "keep in touch": somebody you would drift away from if nobody kept count.
 *
 * The count is a routine's. What is this feature's own is how the telling lands: a moment drawn
 * inside a window, one of each kind a day at most, the close before the sporadic and the one told
 * about longest ago first — and Settings that reach every contact except what was changed by hand.
 */
class ContactsTest {

    private val dayStart: LocalTime = LocalTime.of(9, 0)

    /** A Monday, so the week's windows are all still ahead. 2026-08-31 09:00 Madrid. */
    private val monday: Instant = local(2026, 8, 31, 9, 0)
    private val wednesday: LocalDate = LocalDate.of(2026, 9, 2)
    private val friday: LocalDate = LocalDate.of(2026, 9, 4)
    private val saturday: LocalDate = LocalDate.of(2026, 9, 5)

    private val net = SafetyNetSettings()

    private fun schedules(
        work: ContactSchedule = DEFAULT_WORK_CONTACTS,
        personal: ContactSchedule = DEFAULT_PERSONAL_CONTACTS,
    ): (ContactKind) -> ContactSchedule = { kind -> if (kind == ContactKind.WORK) work else personal }

    private fun contact(
        id: String,
        kind: ContactKind = ContactKind.WORK,
        closeness: Closeness = Closeness.CLOSE,
        months: Int = 3,
        lastDealtAt: Instant? = null,
        createdAt: Instant = monday.minus(200, ChronoUnit.DAYS),
        status: Status = Status.ACTIVE,
        pausedAt: Instant? = null,
        snoozedUntil: Instant? = null,
        snoozedToPlace: Trigger.Location? = null,
        lastFiredAt: Instant? = null,
        nudgedAt: Instant? = null,
        days: Set<DayOfWeek>? = null,
        window: DayWindow? = null,
        byHand: Boolean = false,
    ) = Reminder(
        id = id,
        text = id,
        recurrence = Recurrence.Since(months, RecurrenceUnit.MONTHS),
        contactKind = kind,
        contactCloseness = closeness,
        contactCadenceByHand = byHand,
        contactDays = days,
        contactWindow = window,
        status = status,
        createdAt = createdAt,
        updatedAt = createdAt,
        lastDealtAt = lastDealtAt,
        pausedAt = pausedAt,
        snoozedUntil = snoozedUntil,
        snoozedToPlace = snoozedToPlace,
        lastFiredAt = lastFiredAt,
        nudgedAt = nudgedAt,
    )

    private fun queue(
        vararg contacts: Reminder,
        now: Instant = monday,
        schedules: (ContactKind) -> ContactSchedule = schedules(),
    ) = contactQueue(contacts.toList(), now, zone, schedules, dayStart)

    private fun dayOf(at: Instant): LocalDate = at.atZone(zone).toLocalDate()

    private fun timeOf(at: Instant): LocalTime = at.atZone(zone).toLocalTime()

    private fun window(from: Int, to: Int) = DayWindow(LocalTime.of(from, 0), LocalTime.of(to, 0))

    private fun inWindow(at: Instant, window: DayWindow) = timeOf(at) >= window.from && timeOf(at) < window.to

    private fun Reminder.netDue(now: Instant) = netDue(now, zone, dayStart, net, dayStart)

    @Test
    fun `a contact is a routine that belongs to somebody`() {
        val ana = contact("ana")
        assertTrue(ana.isRoutine, "a contact counts time like any routine")
        assertTrue(ana.isContact)
        assertFalse(ana.copy(contactKind = null).isContact)
        // A plain reminder wearing a kind is still not one: the count is what a contact is.
        assertFalse(ana.copy(recurrence = Recurrence.None).isContact)
        // Written before closeness was asked (0.117.0): read as close.
        assertEquals(Closeness.CLOSE, ana.copy(contactCloseness = null).closeness)
    }

    @Test
    fun `the defaults are the owner's numbers`() {
        assertEquals(setOf(DayOfWeek.WEDNESDAY), DEFAULT_WORK_CONTACTS.days)
        assertEquals(window(9, 12), DEFAULT_WORK_CONTACTS.window)
        assertEquals(3, DEFAULT_WORK_CONTACTS.monthsFor(Closeness.CLOSE))
        assertEquals(5, DEFAULT_WORK_CONTACTS.monthsFor(Closeness.DISTANT))
        assertEquals(setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), DEFAULT_PERSONAL_CONTACTS.days)
        assertEquals(window(17, 19), DEFAULT_PERSONAL_CONTACTS.window)
        assertEquals(2, DEFAULT_PERSONAL_CONTACTS.monthsFor(Closeness.CLOSE))
        assertEquals(5, DEFAULT_PERSONAL_CONTACTS.monthsFor(Closeness.DISTANT))
        assertEquals(Recurrence.Since(2, RecurrenceUnit.MONTHS), DEFAULT_PERSONAL_CONTACTS.cadenceFor(Closeness.CLOSE))
    }

    @Test
    fun `a work contact is told on the wednesday, inside nine to twelve`() {
        val turn = queue(contact("ana")).getValue("ana")
        assertEquals(wednesday, dayOf(turn))
        assertTrue(inWindow(turn, window(9, 12)), "at ${timeOf(turn)}")
    }

    @Test
    fun `a personal contact is told on the friday, inside five to seven`() {
        val turn = queue(contact("mama", kind = ContactKind.PERSONAL)).getValue("mama")
        assertEquals(friday, dayOf(turn))
        assertTrue(inWindow(turn, window(17, 19)), "at ${timeOf(turn)}")
    }

    @Test
    fun `the moment is drawn, and holds still while nothing changes`() {
        val first = queue(contact("ana")).getValue("ana")
        // Asked again an hour on, the same moment: a re-arm must not walk the alarm about.
        assertEquals(first, queue(contact("ana"), now = monday.plus(1, ChronoUnit.HOURS)).getValue("ana"))
        // Drawn, not fixed: over two months of Wednesdays it does not keep to one minute.
        val minutes = (0L..8L).map { week ->
            timeOf(queue(contact("ana"), now = monday.plus(week * 7, ChronoUnit.DAYS)).getValue("ana"))
        }.toSet()
        assertTrue(minutes.size > 1, "always $minutes")
    }

    @Test
    fun `no more than one of each kind a day`() {
        // Two at work, both due, and one Wednesday a week: this one and the next.
        val work = queue(contact("ana"), contact("beto"))
        assertEquals(setOf(wednesday, wednesday.plusWeeks(1)), work.values.map(::dayOf).toSet())
        // Two friends: Friday and Saturday are two days, so both fit this week.
        val friends = queue(contact("mama", kind = ContactKind.PERSONAL), contact("papa", kind = ContactKind.PERSONAL))
        assertEquals(setOf(friday, saturday), friends.values.map(::dayOf).toSet())
    }

    @Test
    fun `but one of each kind can share a day`() {
        val fridays = schedules(work = DEFAULT_WORK_CONTACTS.copy(days = setOf(DayOfWeek.FRIDAY)))
        val turns = queue(contact("ana"), contact("mama", kind = ContactKind.PERSONAL), schedules = fridays)
        assertEquals(friday, dayOf(turns.getValue("ana")))
        assertEquals(friday, dayOf(turns.getValue("mama")))
    }

    @Test
    fun `the close go before the sporadic`() {
        // Beto has waited far longer, but he is somebody to keep up with now and then; Ana is close.
        val beto = contact("beto", closeness = Closeness.DISTANT, months = 5, createdAt = monday.minus(400, ChronoUnit.DAYS))
        val turns = queue(contact("ana"), beto)
        assertEquals(wednesday, dayOf(turns.getValue("ana")))
        assertEquals(wednesday.plusWeeks(1), dayOf(turns.getValue("beto")))
    }

    @Test
    fun `between two as close, the one told about longest ago goes first`() {
        val ana = contact("ana", lastFiredAt = monday.minus(100, ChronoUnit.DAYS), lastDealtAt = monday.minus(99, ChronoUnit.DAYS))
        val beto = contact("beto", lastFiredAt = monday.minus(150, ChronoUnit.DAYS), lastDealtAt = monday.minus(149, ChronoUnit.DAYS))
        assertTrue(queue(ana, beto).let { it.getValue("beto") < it.getValue("ana") })
        // Never told about at all, it counts from where its count began.
        val carlos = contact("carlos", lastDealtAt = monday.minus(120, ChronoUnit.DAYS))
        assertEquals(listOf("beto", "carlos", "ana"), queue(ana, beto, carlos).entries.sortedBy { it.value }.map { it.key })
    }

    @Test
    fun `a tie is settled by chance, the same way every time`() {
        fun winners() = (1..24).map { i -> queue(contact("a$i"), contact("b$i")).minBy { it.value }.key.first() }
        val drawn = winners()
        assertTrue('a' in drawn && 'b' in drawn, "chance, not the alphabet: $drawn")
        assertEquals(drawn, winners())
    }

    @Test
    fun `coinciding windows give one of each, the first moment to whoever ranks first`() {
        val shared = schedules(work = DEFAULT_WORK_CONTACTS.copy(days = setOf(DayOfWeek.FRIDAY), window = window(17, 19)))
        val colleague = contact("colleague")
        // A close colleague and a sporadic friend: the close one goes first, whatever the kinds.
        val sporadic = contact("friend", kind = ContactKind.PERSONAL, closeness = Closeness.DISTANT, months = 5)
        val first = queue(colleague, sporadic, schedules = shared)
        assertEquals(setOf(friday), first.values.map(::dayOf).toSet(), "one of each, the same day")
        assertTrue(first.values.all { inWindow(it, window(17, 19)) })
        assertTrue(first.getValue("colleague") <= first.getValue("friend"))
        // Both close: the friend goes first.
        val close = contact("close", kind = ContactKind.PERSONAL)
        val second = queue(colleague, close, schedules = shared)
        assertEquals(setOf(friday), second.values.map(::dayOf).toSet())
        assertTrue(second.getValue("close") <= second.getValue("colleague"))
    }

    @Test
    fun `a contact told about and ignored sits the week out`() {
        // Told on the Friday and nobody said anything. Not Saturday's window — it had its turn —
        // but the Friday after.
        val mama = contact("mama", kind = ContactKind.PERSONAL, lastFiredAt = local(2026, 9, 4, 17, 40))
        val saturdayMorning = local(2026, 9, 5, 8, 0)
        assertTrue(mama.contactOwed(saturdayMorning), "it rang and nobody answered")
        assertEquals(friday.plusWeeks(1), dayOf(queue(mama, now = saturdayMorning).getValue("mama")))
    }

    @Test
    fun `and back from it, it goes behind the others as close as it`() {
        val ana = contact("ana", lastFiredAt = local(2026, 9, 2, 10, 15))
        val beto = contact("beto")
        val turns = queue(ana, beto, now = local(2026, 9, 7, 9, 0))
        assertEquals(wednesday.plusWeeks(1), dayOf(turns.getValue("beto")))
        assertEquals(wednesday.plusWeeks(2), dayOf(turns.getValue("ana")))
    }

    @Test
    fun `a kind already told about today has no turn left today`() {
        // The bug this guards: Ana told this morning and answered at once, and Beto — due too —
        // inherits what is left of the window. Two colleagues in a day built for one.
        val moment = queue(contact("beto"), now = local(2026, 9, 1, 9, 0)).getValue("beto")
        val now = moment.minus(1, ChronoUnit.MINUTES)
        val ana = contact("ana", lastFiredAt = now.minus(1, ChronoUnit.MINUTES), lastDealtAt = now)
        val turns = queue(ana, contact("beto"), contact("mama", kind = ContactKind.PERSONAL), now = now)
        assertEquals(wednesday.plusWeeks(1), dayOf(turns.getValue("beto")), "not the rest of this morning")
        assertEquals(friday, dayOf(turns.getValue("mama")), "and the other kind is untouched")
    }

    @Test
    fun `a moment already gone is nobody's`() {
        val moment = queue(contact("ana")).getValue("ana")
        val after = moment.plus(1, ChronoUnit.MINUTES)
        assertEquals(wednesday.plusWeeks(1), dayOf(queue(contact("ana"), now = after).getValue("ana")))
        assertTrue(queue(contact("ana"), contact("beto"), now = after).values.all { it > after })
    }

    @Test
    fun `a contact is only drawn once it is due by the moment`() {
        // Spoken to on a Thursday in June: three months, even shaken a tenth early, run out after
        // this Wednesday's window.
        val ana = contact("ana", lastDealtAt = local(2026, 6, 18, 10, 0))
        val due = ana.contactDeadline(zone, dayStart)!!
        val turn = queue(ana).getValue("ana")
        assertTrue(dayOf(turn) > wednesday, "this Wednesday is too early")
        assertTrue(turn >= due, "not before it is due: $turn, due $due")
        assertTrue(dayOf(turn) <= dayOf(due).plusWeeks(1), "and at the first window after")
    }

    @Test
    fun `contacts written in one go come due across their first cadence, not all on one day`() {
        // Forty colleagues typed in on this Monday, all close, all on three months. Counted from
        // that day they would all run out on the last day of November, and be told about a
        // Wednesday at a time from December well into the next summer.
        val burst = (1..40).map { contact("c$it", createdAt = monday) }
        val plain = burst.first().routineDeadline(zone, dayStart)!!
        val span = Duration.between(monday, plain)
        val dues = burst.map { it.contactDeadline(zone, dayStart)!! }
        assertTrue(dues.all { it >= monday && it < plain }, "inside the first cadence")
        val thirds = dues.groupingBy { Duration.between(monday, it).seconds * 3 / span.seconds }.eachCount()
        assertEquals(setOf(0L, 1L, 2L), thirds.keys, "every third of it gets some: $thirds")
        assertTrue(thirds.values.all { it < 24 }, "and none gets most of them: $thirds")
        assertEquals(dues, burst.map { it.contactDeadline(zone, dayStart) }, "drawn once, and held")
        // So the draw starts on them within the month, not in December.
        assertTrue(queue(*burst.toTypedArray()).values.min() < monday.plus(28, ChronoUnit.DAYS))
        // A start named by hand is where that first cadence begins: nobody is told before it.
        val later = monday.plus(30, ChronoUnit.DAYS)
        val named = contact("ana", createdAt = monday).copy(recurrence = Recurrence.Since(3, RecurrenceUnit.MONTHS, startsAt = later))
        assertTrue(named.contactDeadline(zone, dayStart)!! >= later)
    }

    @Test
    fun `once spoken to, the cadence is shaken a tenth either way, and drawn afresh each round`() {
        // Forty people spoken to the same morning.
        val spoken = (1..40).map { contact("c$it", lastDealtAt = monday) }
        val plain = spoken.first().routineDeadline(zone, dayStart)!!
        val tenth = Duration.between(monday, plain).multipliedBy(CONTACT_JITTER_PERCENT.toLong()).dividedBy(100)
        val offsets = spoken.map { Duration.between(plain, it.contactDeadline(zone, dayStart)!!) }
        assertTrue(offsets.all { it.abs() <= tenth }, "within a tenth of the cadence: $offsets")
        assertTrue(offsets.any { it.isNegative } && offsets.any { it > Duration.ZERO }, "either way")
        assertTrue(offsets.map { dayOf(plain.plus(it)) }.toSet().size > 10, "not all back on one day")
        // The next "hablado" draws again: the shake is the round's, not the contact's for life.
        val weekOn = monday.plus(7, ChronoUnit.DAYS)
        val again = spoken.map { it.copy(lastDealtAt = weekOn) }
        val againPlain = again.first().routineDeadline(zone, dayStart)!!
        assertEquals(Duration.between(monday, plain), Duration.between(weekOn, againPlain), "the same cadence, to the second")
        val redrawn = again.map { Duration.between(againPlain, it.contactDeadline(zone, dayStart)!!) }
        assertTrue(offsets.zip(redrawn).count { (before, after) -> before != after } > 30, "drawn again")
    }

    @Test
    fun `resting, or waiting at a place, takes no turn`() {
        assertNull(queue(contact("ana", status = Status.PAUSED, pausedAt = monday.minusSeconds(3600)))["ana"])
        val home = Trigger.Location(40.4168, -3.7038, 150, Presence.INSIDE, "casa", onCrossing = true)
        assertNull(queue(contact("ana", snoozedToPlace = home))["ana"])
    }

    @Test
    fun `put off a week is back for that weekday's window`() {
        val ana = contact("ana", lastFiredAt = local(2026, 9, 2, 10, 15))
        val until = ana.contactPutOffUntil(local(2026, 9, 2, 10, 20), zone)
        assertEquals(local(2026, 9, 9, 0, 0), until)
        // From the net's card the day after, still a week from the telling rather than from the tap.
        assertEquals(until, ana.contactPutOffUntil(local(2026, 9, 3, 10, 16), zone))
        assertEquals(wednesday.plusWeeks(1), dayOf(queue(ana.copy(snoozedUntil = until), now = local(2026, 9, 3, 11, 0)).getValue("ana")))
        // Nothing to answer — put off from the list, days later — is a week from today.
        assertEquals(local(2026, 9, 14, 0, 0), contact("beto").contactPutOffUntil(local(2026, 9, 7, 12, 0), zone))
    }

    @Test
    fun `the net says it the next day at the same time, once`() {
        val told = local(2026, 9, 2, 10, 15)
        val ana = contact("ana", lastFiredAt = told)
        val later = local(2026, 9, 2, 12, 0)
        val due = ana.netDue(later)!!
        assertEquals(local(2026, 9, 3, 10, 15), due.at)
        assertEquals(told, due.about)
        assertEquals(NetWord.LET_GO, due.word)
        assertNull(ana.copy(nudgedAt = local(2026, 9, 3, 10, 15)).netDue(later), "once")
        assertNull(ana.copy(lastDealtAt = local(2026, 9, 2, 11, 0)).netDue(later), "answered")
        val putOff = ana.copy(snoozedUntil = local(2026, 9, 9, 0, 0))
        assertNull(putOff.netDue(later), "put off is an answer")
        assertNull(putOff.netDue(local(2026, 9, 10, 12, 0)), "and still one once its week is up")
        // Across the clocks going back: the same time on the clock, not twenty-four hours.
        val autumn = contact("ana", lastFiredAt = local(2026, 10, 24, 18, 0))
        assertEquals(local(2026, 10, 25, 18, 0), autumn.netDue(local(2026, 10, 24, 19, 0))!!.at)
    }

    @Test
    fun `a contact never told about is owed no word by the net`() {
        // 0.117.0 put a "never rang" card in the shade the day after every contact was written:
        // no next moment of its own reads, to the net, as a moment that came and went.
        val fresh = contact("ana", createdAt = monday)
        assertNull(fresh.netDue(monday.plus(3, ChronoUnit.DAYS)))
        assertNull(contact("beto").netDue(monday), "nor one long due and waiting its turn")
    }

    @Test
    fun `Settings changed reach every contact that follows them, and none changed by hand`() {
        val start = local(2026, 10, 1, 0, 0)
        val follows = contact("ana").copy(recurrence = Recurrence.Since(3, RecurrenceUnit.MONTHS, startsAt = start))
        val byHand = contact("beto", byHand = true).copy(recurrence = Recurrence.Since(6, RecurrenceUnit.WEEKS))
        val inStep = contact("carlos", closeness = Closeness.DISTANT, months = 5)
        val nowSporadic = contact("mama", kind = ContactKind.PERSONAL, closeness = Closeness.DISTANT, months = 2)
        val plants = Reminder(id = "plants", text = "Regar", recurrence = Recurrence.Since(3, RecurrenceUnit.DAYS), createdAt = monday, updatedAt = monday)
        val rewritten = contactsOutOfStep(
            listOf(follows, byHand, inStep, nowSporadic, plants),
            schedules(work = DEFAULT_WORK_CONTACTS.copy(closeMonths = 4)),
        ).associateBy { it.id }
        assertEquals(setOf("ana", "mama"), rewritten.keys)
        assertEquals(Recurrence.Since(4, RecurrenceUnit.MONTHS, startsAt = start), rewritten.getValue("ana").recurrence, "where it counts from stays")
        assertEquals(Recurrence.Since(5, RecurrenceUnit.MONTHS), rewritten.getValue("mama").recurrence)
    }

    @Test
    fun `days and a window changed by hand are the contact's own`() {
        val ana = contact("ana", days = setOf(DayOfWeek.MONDAY), window = window(18, 20))
        val turns = queue(ana, contact("beto"))
        assertEquals(LocalDate.of(2026, 8, 31), dayOf(turns.getValue("ana")), "this very Monday evening")
        assertTrue(inWindow(turns.getValue("ana"), window(18, 20)))
        assertEquals(wednesday, dayOf(turns.getValue("beto")), "the other still follows Settings")
        // Settings moving does not move what was set by hand.
        val moved = queue(ana, contact("beto"), schedules = schedules(work = DEFAULT_WORK_CONTACTS.copy(days = setOf(DayOfWeek.THURSDAY))))
        assertEquals(LocalDate.of(2026, 8, 31), dayOf(moved.getValue("ana")))
        assertEquals(LocalDate.of(2026, 9, 3), dayOf(moved.getValue("beto")))
    }

    @Test
    fun `a kind with no days is never told about`() {
        val none = schedules(work = DEFAULT_WORK_CONTACTS.copy(days = emptySet()))
        val turns = queue(contact("ana"), contact("mama", kind = ContactKind.PERSONAL), schedules = none)
        assertNull(turns["ana"], "no day, no telling")
        assertTrue(turns.containsKey("mama"))
    }

    @Test
    fun `more contacts than a year of Wednesdays ends, and leaves the rest unnamed`() {
        val many = (1..80).map { contact("c$it") }
        val turns = contactQueue(many, monday, zone, schedules(), dayStart)
        assertTrue(turns.size in 52..53, "a year of Wednesdays, and no more: ${turns.size}")
        assertTrue(turns.values.all { it < monday.plus(CONTACT_HORIZON_DAYS + 1L, ChronoUnit.DAYS) })
    }

    @Test
    fun `a turn still ahead is not something to be red about`() {
        val ana = contact("ana")
        assertFalse(ana.contactOwed(monday), "waiting its turn is not being overdue")
        assertTrue(overdueContacts(listOf(ana), monday).isEmpty())
        // Told about, and nobody said anything: that, and only that, is what Home shows.
        val rung = ana.copy(lastFiredAt = monday.plus(2, ChronoUnit.DAYS))
        val after = monday.plus(3, ChronoUnit.DAYS)
        assertTrue(rung.contactOwed(after))
        assertEquals(listOf(rung), overdueContacts(listOf(rung), after))
        assertFalse(rung.copy(lastDealtAt = after).contactOwed(after), "answered, it drops off")
        // Put off is an answer, and stays one once its week is up: Home waits for the next telling.
        val putOff = rung.copy(snoozedUntil = monday.plus(9, ChronoUnit.DAYS))
        assertFalse(putOff.contactOwed(after))
        assertFalse(putOff.contactOwed(monday.plus(10, ChronoUnit.DAYS)))
    }

    @Test
    fun `the list is ordered by the turn each row shows, not by the plazo behind it`() {
        // Beto is somebody to keep up with now and then and his cadence ran out long ago; Ana is
        // close and hers ran out later. The draw gives Ana the first Wednesday — the close before
        // the sporadic — and the plazos say the exact opposite, which is the order the list used
        // to take while every row on it showed the other one.
        val ana = contact("ana")
        val beto = contact("beto", closeness = Closeness.DISTANT, months = 5, createdAt = monday.minus(400, ChronoUnit.DAYS))
        val turns = queue(ana, beto)
        assertTrue(turns.getValue("ana") < turns.getValue("beto"), "the draw puts the close one first")
        assertTrue(beto.routineDeadline(zone, dayStart)!! < ana.routineDeadline(zone, dayStart)!!, "and the plazos say the opposite")
        val rows = routinesFor(listOf(beto, ana), RoutineFilter.All, monday, zone, dayStart, turns = turns)
        assertEquals(listOf("ana", "beto"), rows.map { it.id })
    }

    @Test
    fun `a contact with no turn sorts behind the ones that have one`() {
        val ana = contact("ana")
        val nobody = contact("beto", days = emptySet())
        val turns = queue(ana, nobody)
        assertNull(turns["beto"], "no day, no turn")
        val rows = routinesFor(listOf(nobody, ana), RoutineFilter.All, monday, zone, dayStart, turns = turns)
        assertEquals(listOf("ana", "beto"), rows.map { it.id }, "no turn is the back of the queue, not the front of it")
    }

    @Test
    fun `the overdue chip is about being told and unanswered, never about the plazo`() {
        // What made the screen disagree with itself: the chip offered a list of contacts whose
        // own cards all read "Sí".
        val waiting = contact("ana")
        assertTrue(waiting.routineOwed(monday, zone, dayStart), "as a plain routine it would be owed")
        assertFalse(waiting.listedAsOwed(monday, zone, dayStart))
        assertFalse(RoutineFilter.Overdue in routineFilters(listOf(waiting), monday, zone, dayStart))
        assertTrue(routinesFor(listOf(waiting), RoutineFilter.Overdue, monday, zone, dayStart).isEmpty())
        val told = waiting.copy(lastFiredAt = monday.minus(1, ChronoUnit.DAYS))
        assertTrue(told.listedAsOwed(monday, zone, dayStart), "told about, and nobody answered")
        assertTrue(RoutineFilter.Overdue in routineFilters(listOf(told), monday, zone, dayStart))
        assertEquals(listOf("ana"), routinesFor(listOf(told), RoutineFilter.Overdue, monday, zone, dayStart).map { it.id })
    }

    @Test
    fun `an ordinary routine is owed by its plazo, as it always was`() {
        val plants = Reminder(
            id = "plants", text = "Regar",
            recurrence = Recurrence.Since(3, RecurrenceUnit.DAYS),
            createdAt = monday.minus(30, ChronoUnit.DAYS), updatedAt = monday.minus(30, ChronoUnit.DAYS),
        )
        assertTrue(plants.listedAsOwed(monday, zone, dayStart))
        assertTrue(RoutineFilter.Overdue in routineFilters(listOf(plants), monday, zone, dayStart))
    }

    @Test
    fun `one day a week is fifty-two turns a year, and a close contact asks for four`() {
        val load = contactLoad(listOf(contact("ana")), ContactKind.WORK, DEFAULT_WORK_CONTACTS, zone, dayStart)
        assertEquals(52.0, load.capacity, 0.001)
        assertEquals(4.0, load.demand, 0.2, "every three months")
        assertEquals(1, load.people)
        assertFalse(load.over)
    }

    @Test
    fun `twenty people at three months on one day a week is more than it can carry`() {
        val many = (1..20).map { contact("c$it") }
        val load = contactLoad(many, ContactKind.WORK, DEFAULT_WORK_CONTACTS, zone, dayStart)
        assertTrue(load.over, "eighty turns a year asked of fifty-two")
        // And the way out, in the two terms somebody can act on rather than as a ratio.
        assertEquals(2, load.daysNeeded, "two days a week would carry them")
        assertEquals(5, load.monthsNeeded, "or every five months on the day they already have")
        assertEquals(ContactWarning.OVER_CAPACITY, contactWarning(load, DEFAULT_WORK_CONTACTS.days))
    }

    @Test
    fun `a resting contact asks for nothing`() {
        val paused = contact("ana", status = Status.PAUSED, pausedAt = monday.minusSeconds(3600))
        val load = contactLoad(listOf(paused), ContactKind.WORK, DEFAULT_WORK_CONTACTS, zone, dayStart)
        assertEquals(0, load.people)
        assertEquals(0.0, load.demand, 0.001)
    }

    @Test
    fun `a cadence set by hand counts as what it is`() {
        val fortnightly = contact("ana").copy(recurrence = Recurrence.Since(2, RecurrenceUnit.WEEKS), contactCadenceByHand = true)
        val load = contactLoad(listOf(fortnightly), ContactKind.WORK, DEFAULT_WORK_CONTACTS, zone, dayStart)
        assertEquals(26.0, load.demand, 0.5, "a fortnight is twenty-six turns a year")
        assertFalse(load.over, "which one day a week carries easily")
    }

    @Test
    fun `a kind with no day is a warning only while somebody is of that kind`() {
        val none = DEFAULT_WORK_CONTACTS.copy(days = emptySet())
        val nobody = contactLoad(emptyList(), ContactKind.WORK, none, zone, dayStart)
        assertEquals(ContactWarning.NONE, contactWarning(nobody, none.days), "no day and nobody to silence")
        val somebody = contactLoad(listOf(contact("ana")), ContactKind.WORK, none, zone, dayStart)
        assertEquals(ContactWarning.NEVER, contactWarning(somebody, none.days))
    }

    @Test
    fun `the other kind's people are somebody else's load`() {
        val load = contactLoad(listOf(contact("mama", kind = ContactKind.PERSONAL)), ContactKind.WORK, DEFAULT_WORK_CONTACTS, zone, dayStart)
        assertEquals(0, load.people)
        assertEquals(ContactWarning.NONE, contactWarning(load, DEFAULT_WORK_CONTACTS.days))
    }

    @Test
    fun `a contact never reaches the routines own line on Home`() {
        // Its cadence running out is not red; the routines' surfaces must not claim it.
        val ana = contact("ana", lastDealtAt = monday.minus(200, ChronoUnit.DAYS))
        assertTrue(ana.routineOwed(monday, zone, dayStart), "as a routine it would be owed")
        assertTrue(overdueRoutines(listOf(ana), monday, zone, dayStart).isEmpty())
        assertNull(nextDueRoutine(listOf(contact("beto")), monday, zone, dayStart))
    }

    @Test
    fun `an ordinary routine is left out of the draw entirely`() {
        val plants = Reminder(
            id = "plants", text = "Regar",
            recurrence = Recurrence.Since(3, RecurrenceUnit.DAYS),
            createdAt = monday.minus(30, ChronoUnit.DAYS), updatedAt = monday.minus(30, ChronoUnit.DAYS),
        )
        assertTrue(queue(plants).isEmpty())
        assertTrue(contactsOutOfStep(listOf(plants), schedules()).isEmpty())
    }
}
