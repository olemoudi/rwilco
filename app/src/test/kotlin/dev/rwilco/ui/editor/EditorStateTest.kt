package dev.rwilco.ui.editor

import dev.rwilco.model.Action
import dev.rwilco.model.DEFAULT_ACTIONS
import dev.rwilco.model.MAX_TEXT_LENGTH
import dev.rwilco.model.keeping
import dev.rwilco.model.Period
import dev.rwilco.model.Presence
import dev.rwilco.model.Recurrence
import dev.rwilco.model.awaitingAnswer
import dev.rwilco.model.nextFire
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.RepeatUnit
import dev.rwilco.model.Condition
import dev.rwilco.model.MovingKind
import dev.rwilco.model.movingOf
import dev.rwilco.model.Understood
import dev.rwilco.model.Reminder
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.Status
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.model.TriggerKind
import dev.rwilco.model.ValidationError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import dev.rwilco.model.Deadline
import dev.rwilco.model.withSpanOf
import dev.rwilco.model.Closeness
import dev.rwilco.model.ContactKind
import dev.rwilco.model.DayWindow

class EditorStateTest {

    private val garage = Trigger.Location(40.4168, -3.7038, 150, Presence.OUTSIDE, "garaje", onCrossing = true)
    private val driving = movingOf(MovingKind.DRIVING)
    private val onFoot = movingOf(MovingKind.ON_FOOT)

    @Test
    fun `the place sheet sets the speed fence, and re-opening it replaces the one it showed`() {
        // The speed used to be reachable only from "y sólo si" — a grey text button under a
        // trigger that had to exist first — so nobody found it. The place sheet asks it now, and
        // what comes back is an ANSWER to the fence it opened showing: it replaces, never joins,
        // or a place edited twice would carry two speeds and mean the faster of them.
        val added = blank.withText("mover el coche").commitTrigger(null, garage, fence = driving)
        assertEquals(listOf<Condition>(driving), added.draft.rules.single().conditions)
        val changed = added.commitTrigger(0, garage, fence = onFoot)
        assertEquals(listOf<Condition>(onFoot), changed.draft.rules.single().conditions)
        val off = changed.commitTrigger(0, garage, fence = null)
        assertEquals(emptyList<Condition>(), off.draft.rules.single().conditions, "the switch turned off takes the fence with it")
    }

    @Test
    fun `the fence leaves every other condition alone, and every other sheet leaves the fence alone`() {
        val hours = Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0))
        val fenced = blank.withText("sacar la basura")
            .commitTrigger(null, garage, fence = driving)
            .commitCondition(0, null, hours)
        assertEquals(listOf(driving, hours), fenced.draft.rules.single().conditions)
        // Re-confirming the place keeps the hours: only the speed is this sheet's to answer.
        assertEquals(listOf(hours, driving), fenced.commitTrigger(0, garage, fence = driving).draft.rules.single().conditions)
        // And a rule that is not a place is never touched by the default: editing the hour of
        // "a las nueve, y sólo si voy en coche" must not quietly drop the speed.
        val clock = blank.withText("pastilla")
            .commitTrigger(null, tonight)
            .commitCondition(0, null, driving)
        assertEquals(listOf<Condition>(driving), clock.commitTrigger(0, weekly).draft.rules.single().conditions)
    }

    @Test
    fun `a place read as a state does not answer the speed question, so it does not rewrite it`() {
        // The sheet only puts the question under the doorway — a speed is about the instant of
        // crossing a line, and "mientras esté en el garaje" has no such instant. What it did not
        // ask it must not answer: a fence somebody wrote from "y sólo si" stays exactly where it
        // is, instead of being thrown away by the next visit to the place sheet.
        val standing = garage.copy(onCrossing = false)
        val fenced = blank.withText("mover el coche")
            .commitTrigger(null, standing)
            .commitCondition(0, null, driving)
        assertEquals(listOf<Condition>(driving), fenced.commitTrigger(0, standing, fence = null).draft.rules.single().conditions)
        // And turning the reading back into a doorway is the sheet asking again: now it answers.
        assertEquals(emptyList<Condition>(), fenced.commitTrigger(0, garage, fence = null).draft.rules.single().conditions)
        assertEquals(listOf<Condition>(onFoot), fenced.commitTrigger(0, garage, fence = onFoot).draft.rules.single().conditions)
    }

    private val tonight = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 30))
    private val weekly = Trigger.AtTime(LocalTime.of(7, 30), setOf(DayOfWeek.MONDAY))
    private val blank = EditorUiState(loaded = true, existingTags = listOf("Compra", "casa"))
    private val zone = ZoneId.of("Europe/Madrid")
    private val now: Instant = LocalDateTime.of(2026, 8, 27, 22, 0).atZone(zone).toInstant()

    @Test
    fun `the first error is the one the snackbar says, the words before a rule`() {
        val zeroCountdown = Trigger.Countdown(0)
        assertEquals(ValidationError.TextBlank, blank.commitTrigger(null, zeroCountdown).errors.first())
        val worded = blank.withText("water").commitTrigger(null, zeroCountdown)
        assertTrue(worded.errors.first() is ValidationError.BadTrigger)
    }

    @Test
    fun `a blank editor is clean, invalid and quiet about it`() {
        assertFalse(blank.dirty)
        assertFalse(blank.canSave)
        assertEquals(listOf(ValidationError.TextBlank), blank.errors)
        assertFalse(blank.showErrors)
    }

    @Test
    fun `a second trigger means "a la vez", unless that would mean silence`() {
        val twoAt = Trigger.TimeOfDay(LocalTime.of(14, 0))
        val fridays = Trigger.Weekday(setOf(DayOfWeek.FRIDAY))
        // The everyday case, and the reason the default moved: somebody writing "a las 14:00"
        // and then "los viernes" means one arrangement with two halves, not two arrangements
        // either of which rings. Under "cualquiera" that was a reminder going off twice.
        val together = blank.commitTrigger(null, twoAt).commitTrigger(null, fridays)
        assertEquals(RuleMatch.TOGETHER, together.draft.ruleMatch)
        // And the exception. Two triggers that are each true at one instant never coincide, so
        // "a la vez" there is a reminder that can never ring — quietly, at the moment somebody
        // was least looking. The editor warns about it; a default that leans on its own warning
        // is a bad default.
        val moments = blank.commitTrigger(null, tonight).commitTrigger(null, weekly)
        assertEquals(RuleMatch.ANY, moments.draft.ruleMatch)
        // A place is a doorway when it is written as one, so it is a moment too.
        val doorway = Trigger.Location(40.4, -3.7, 150, Presence.INSIDE, "Casa", onCrossing = true)
        assertEquals(RuleMatch.ANY, blank.commitTrigger(null, twoAt).commitTrigger(null, doorway).draft.ruleMatch)
        // Whereas "mientras esté en casa" is a state, and joins the hour the way it reads.
        val standing = doorway.copy(onCrossing = false)
        assertEquals(RuleMatch.TOGETHER, blank.commitTrigger(null, twoAt).commitTrigger(null, standing).draft.ruleMatch)
    }

    /**
     * The other half of the guard above, and the one the two-moments test could not reach.
     *
     * "De 09:00 a 11:00" and "de 17:00 a 19:00" are not two moments — they are two stretches —
     * so nothing stopped the second one writing "a la vez", and "a la vez" over two stretches
     * that never overlap is a reminder that can never ring. The editor said so in a small line
     * under the rule; a default that leans on its own warning is a bad default, which is the
     * argument the two-moments guard was already written from.
     *
     * The clock is what makes it askable at all — the walk is [dev.rwilco.model.warnings], the
     * same one that draws the warning — so without one the old answer stands, deliberately.
     */
    @Test
    fun `two stretches that never overlap do not become "a la vez"`() {
        val morning = Trigger.Interval(LocalTime.of(9, 0), LocalTime.of(11, 0))
        val evening = Trigger.Interval(LocalTime.of(17, 0), LocalTime.of(19, 0))
        val disjoint = blank.commitTrigger(null, morning, now = now, zone = zone)
            .commitTrigger(null, evening, now = now, zone = zone)
        assertEquals(RuleMatch.ANY, disjoint.draft.ruleMatch)
        // Overlapping ones still mean what a second "cuándo" almost always means.
        val overlapping = blank.commitTrigger(null, morning, now = now, zone = zone)
            .commitTrigger(null, Trigger.Interval(LocalTime.of(10, 0), LocalTime.of(12, 0)), now = now, zone = zone)
        assertEquals(RuleMatch.TOGETHER, overlapping.draft.ruleMatch)
        // And with no clock to ask with, the answer is the one it always was.
        val unasked = blank.commitTrigger(null, morning).commitTrigger(null, evening)
        assertEquals(RuleMatch.TOGETHER, unasked.draft.ruleMatch)
    }

    @Test
    fun `only the second trigger, and only while nobody has chosen`() {
        val twoAt = Trigger.TimeOfDay(LocalTime.of(14, 0))
        val fridays = Trigger.Weekday(setOf(DayOfWeek.FRIDAY))
        // A third rule leaves the answer alone: by then it is somebody's, whether they picked
        // it or the second rule did.
        val third = blank.commitTrigger(null, twoAt).commitTrigger(null, fridays)
            .setRuleMatch(RuleMatch.ANY)
            .commitTrigger(null, Trigger.DateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
        assertEquals(RuleMatch.ANY, third.draft.ruleMatch)
        // And a choice already made is not overwritten by the rule that arrives after it.
        val chosen = blank.commitTrigger(null, twoAt).setRuleMatch(RuleMatch.ALL).commitTrigger(null, fridays)
        assertEquals(RuleMatch.ALL, chosen.draft.ruleMatch)
        // Editing a rule in place changes nothing either: there is no new rule to read.
        val edited = blank.commitTrigger(null, twoAt).commitTrigger(0, Trigger.TimeOfDay(LocalTime.of(15, 0)))
        assertEquals(RuleMatch.ANY, edited.draft.ruleMatch)
        assertEquals(1, edited.draft.rules.size)
    }

    @Test
    fun `a leftover "a la vez" is not inherited by a pair that could never ring`() {
        val twoAt = Trigger.TimeOfDay(LocalTime.of(14, 0))
        val fridays = Trigger.Weekday(setOf(DayOfWeek.FRIDAY))
        // Two rules make "a la vez"; take both away and the answer is still on the draft, with
        // no control on the screen to have chosen it — this function wrote it. Two moments
        // added back must not inherit it, or the pair is silent by a machine's decision.
        val reused = blank.commitTrigger(null, twoAt).commitTrigger(null, fridays)
            .removeTrigger(1).removeTrigger(0)
            .commitTrigger(null, tonight).commitTrigger(null, weekly)
        assertEquals(RuleMatch.ANY, reused.draft.ruleMatch)
        // "Todos" over two moments means something — both have happened — and is never touched.
        val both = blank.commitTrigger(null, twoAt).setRuleMatch(RuleMatch.ALL)
            .removeTrigger(0)
            .commitTrigger(null, tonight).commitTrigger(null, weekly)
        assertEquals(RuleMatch.ALL, both.draft.ruleMatch)
    }

    @Test
    fun `how the rules combine travels to the reminder and back`() {
        val state = blank.withText("Llamar a Marta")
            .commitTrigger(null, tonight)
            .commitTrigger(null, weekly)
            .setRuleMatch(RuleMatch.ALL)
        assertEquals(RuleMatch.ALL, state.draft.ruleMatch)
        val stamp = Instant.parse("2026-08-27T13:00:00Z")
        val saved = state.draft.toReminder("id", stamp, stamp, Status.ACTIVE, zone = zone)
        assertEquals(RuleMatch.ALL, saved.ruleMatch)
        assertEquals(RuleMatch.ALL, saved.toDraft().ruleMatch)
    }

    @Test
    fun `a deadline is set from its sheet, travels to the reminder, and a timer does not survive "a la vez"`() {
        val home = Trigger.Location(40.4, -3.7, 200, Presence.INSIDE, "Casa")
        val two = blank.withText("Llamar a Marta").commitTrigger(null, tonight).commitTrigger(null, home).setRuleMatch(RuleMatch.ALL)
        val opened = two.openDeadline()
        assertEquals(EditorSheet.ConfigureDeadline(null), opened.sheet)
        val timed = opened.commitDeadline(Deadline.Timer(90))
        assertEquals(EditorSheet.None, timed.sheet)
        assertEquals(Deadline.Timer(90), timed.draft.deadline)
        val saved = timed.draft.toReminder("r", now, now, Status.ACTIVE, zone = zone)
        assertEquals(Deadline.Timer(90), saved.deadline)
        assertEquals(Deadline.Timer(90), saved.toDraft().deadline)
        // "A la vez" has no first trigger: the sheet offers no timer there, and the form keeps none.
        assertNull(timed.setRuleMatch(RuleMatch.TOGETHER).draft.deadline)
        val window = Deadline.Window(LocalTime.of(18, 0), LocalTime.of(22, 0))
        assertEquals(window, timed.commitDeadline(window).setRuleMatch(RuleMatch.TOGETHER).draft.deadline, "a window is fine under either")
        assertNull(timed.clearDeadline().draft.deadline)
    }

    @Test
    fun `words alone are enough to save`() {
        val note = blank.withText("Pilas AA, papel de horno y café").addTag("lista de la compra")
        assertTrue(note.canSave, "a reminder kept under a tag needs neither a trigger nor an action")
    }

    @Test
    fun `typing and adding a trigger makes it dirty and saveable`() {
        val state = blank.withText("Water the plants").commitTrigger(null, tonight)
        assertTrue(state.dirty)
        assertTrue(state.canSave)
        assertEquals(EditorSheet.None, state.sheet)
        assertEquals(listOf(tonight), state.draft.rules.map { it.trigger })
    }

    @Test
    fun `text is capped at the maximum length`() {
        assertEquals(MAX_TEXT_LENGTH, blank.withText("x".repeat(MAX_TEXT_LENGTH + 50)).draft.text.length)
    }

    @Test
    fun `tags toggle case-insensitively and new tags reuse an existing spelling`() {
        var state = blank.toggleTag("casa")
        assertEquals(listOf("casa"), state.draft.tags)
        state = state.toggleTag("CASA")
        assertTrue(state.draft.tags.isEmpty())
        state = state.addTag("  compra ")
        assertEquals(listOf("Compra"), state.draft.tags, "the tag as it already exists elsewhere")
        state = state.addTag("compra").addTag("   ")
        assertEquals(listOf("Compra"), state.draft.tags)
        state = state.addTag("salud")
        assertEquals(listOf("Compra", "salud"), state.draft.tags)
    }

    @Test
    fun `actions toggle, and none of them is a moment that passes quietly`() {
        val none = blank.withText("Regar")
            .toggleAction(Action.NOTIFICATION).toggleAction(Action.SOUND).toggleAction(Action.VIBRATE)
        assertTrue(none.draft.actions.isEmpty())
        assertTrue(none.canSave)
        // The two sound tiles are one choice: asking for the insistent one puts the plain one away.
        assertEquals(
            DEFAULT_ACTIONS - Action.SOUND + Action.SOUND_UNTIL_ANSWERED,
            blank.toggleAction(Action.SOUND_UNTIL_ANSWERED).draft.actions,
        )
    }

    @Test
    fun `editing a trigger replaces it in place and removing drops it`() {
        var state = blank.commitTrigger(null, tonight).commitTrigger(null, weekly)
        state = state.editTrigger(1)
        assertEquals(EditorSheet.Configure(TriggerKind.REPEAT_TIME, 1, weekly), state.sheet)
        val changed = weekly.copy(time = LocalTime.of(8, 0))
        state = state.commitTrigger(1, changed)
        assertEquals(listOf(tonight, changed), state.draft.rules.map { it.trigger })
        state = state.removeTrigger(0)
        assertEquals(listOf(changed), state.draft.rules.map { it.trigger })
        assertEquals(state, state.editTrigger(5), "editing a row that is not there is a no-op")
    }

    @Test
    fun `a removed rule goes back where it was, with the recurrence it took with it`() {
        // The bin sits one icon away from the pencil, and a place with its radius dragged is
        // several minutes of work: the app's rule is an undo wherever there is an inverse.
        val window = Trigger.Random(3, Period.DAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
        var state = blank.commitTrigger(null, tonight).commitTrigger(null, window)
        state = state.copy(draft = state.draft.copy(recurrence = Recurrence.ByTrigger))
        val removed = state.draft.rules[1]

        // Taking the last random window out clears the answer it was: that has to come back too,
        // or the undo hands back a different arrangement from the one that was removed.
        val after = state.removeTrigger(1)
        assertEquals(Recurrence.None, after.draft.recurrence)

        val back = after.restoreTrigger(1, removed, Recurrence.ByTrigger)
        assertEquals(listOf(tonight, window), back.draft.rules.map { it.trigger })
        assertEquals(Recurrence.ByTrigger, back.draft.recurrence)
        assertEquals(state.draft, back.draft, "the arrangement is the one that was removed")

        // The first rule, put back at the front rather than at the end.
        val front = state.removeTrigger(0).restoreTrigger(0, state.draft.rules[0], Recurrence.ByTrigger)
        assertEquals(listOf(tonight, window), front.draft.rules.map { it.trigger })
    }

    @Test
    fun `saving keeps a snooze, unless the edit re-decided when it rings`() {
        // Reported from a phone: a reminder put off until tomorrow night, then edited, lost the
        // snooze — so it left the section the snooze put it in for the bottom of Home, and it
        // read as rung-and-ignored again, which woke the safety net at ten to seven about an
        // alert that had been answered the night before.
        val rang = now.minusSeconds(3600)
        val until = now.plusSeconds(24 * 3600)
        val saved = blank.withText("Licencia teclado").commitTrigger(null, tonight)

        // A word changed and nothing else: the answer stands.
        val typo = saved.withText("Licencia del teclado")
        val kept = typo.draft.toReminder(
            id = "r", createdAt = now, now = now, status = Status.ACTIVE,
            lastFiredAt = rang, snoozedUntil = until, zone = zone,
        )
        assertEquals(until, kept.snoozedUntil)
        assertFalse(kept.awaitingAnswer(now), "an answered ring is not owed another")

        // The rules themselves: "en diez minutos" from the old shape really is meaningless.
        val rerules = saved.commitTrigger(0, weekly)
        val dropped = rerules.draft.toReminder(
            id = "r", createdAt = now, now = now, status = Status.ACTIVE,
            lastFiredAt = rang, snoozedUntil = null, zone = zone,
        )
        assertNull(dropped.snoozedUntil)
    }

    @Test
    fun `saving keeps the moment a recurrence counts from`() {
        // A save replaces the whole row, so anything the draft does not carry is gone. The
        // snooze, the last ring and the armed moment are dropped on purpose — editing re-decides
        // when it rings. lastDealtAt is not one of those: it is the anchor "cada 6 h" is measured
        // from, and losing it to a typo either stops the reminder dead (with triggers, there is
        // nothing to count from until it is dealt with again) or hurls its next moment back to
        // the day it was written (without them).
        val dealt = Instant.parse("2026-08-27T13:00:00Z")
        val draft = Draft(text = "Pastillas", recurrence = Recurrence.After(6, RecurrenceUnit.HOURS))
        val saved = draft.toReminder("r1", dealt.minusSeconds(86_400), dealt.plusSeconds(60), Status.ACTIVE, lastDealtAt = dealt, zone = zone)

        assertEquals(dealt, saved.lastDealtAt)
        assertEquals(null, saved.snoozedUntil, "a remind-me-later belonged to the old shape")
        assertEquals(null, saved.armedFor, "the scheduler writes this again the instant it is saved")
        assertEquals(null, draft.toReminder("r1", dealt, dealt, Status.ACTIVE, zone = zone).lastDealtAt, "and a new reminder has no anchor yet")
    }

    @Test
    fun `saving does not put a recurrence that has already rung back on the clock`() {
        // "Cada 1 h", written at 14:14, rang at 15:14, and nobody has dealt with it — so it is
        // overdue and waiting for a person. Opening it, changing nothing and saving must leave
        // it exactly that. Dropping the last ring un-spends 15:14, which puts it back on Home as
        // "lo siguiente" three quarters of an hour in the PAST — and arms an alarm for a moment
        // already gone, which arrives at once.
        val written = Instant.parse("2026-08-25T12:14:00Z")
        val rang = written.plus(Duration.ofHours(1))
        val before = Reminder(
            id = "pills",
            text = "Tomar la pastilla",
            recurrence = Recurrence.After(1, RecurrenceUnit.HOURS),
            createdAt = written,
            updatedAt = written,
            lastFiredAt = rang,
        )

        val saved = before.toDraft().toReminder(
            id = before.id,
            createdAt = before.createdAt,
            now = rang.plus(Duration.ofMinutes(47)),
            status = Status.ACTIVE,
            lastDealtAt = before.lastDealtAt,
            lastFiredAt = before.lastFiredAt,
            zone = zone,
        )

        assertEquals(rang, saved.lastFiredAt)
        assertNull(
            nextFire(saved, rang.plus(Duration.ofMinutes(47)), ZoneId.of("Europe/Madrid"), LocalTime.of(9, 0)),
            "a moment that has rung stays spent across an edit",
        )
    }

    @Test
    fun `the kind picker leads to a configurator for that kind`() {
        val state = blank.openKindPicker()
        assertEquals(EditorSheet.PickKind, state.sheet)
        assertEquals(EditorSheet.Configure(TriggerKind.COUNTDOWN, null, null), state.pickKind(TriggerKind.COUNTDOWN).sheet)
        assertEquals(EditorSheet.None, state.closeSheet().sheet)
    }

    @Test
    fun `cancelling a configurator opened from the picker returns to the picker, editing a rule closes outright`() {
        // "Añadir → Fecha → no, I meant Lugar" is one step back, not a cancel and a fresh
        // "Añadir"; a rule that already exists has no picker behind it.
        val fresh = blank.openKindPicker().pickKind(TriggerKind.COUNTDOWN)
        assertEquals(EditorSheet.PickKind, fresh.closeSheet().sheet)
        assertEquals(EditorSheet.None, fresh.closeSheet().closeSheet().sheet)
        val editing = blank.commitTrigger(null, Trigger.Countdown(5)).editTrigger(0)
        assertTrue(editing.sheet is EditorSheet.Configure)
        assertEquals(EditorSheet.None, editing.closeSheet().sheet)
    }

    @Test
    fun `a reminder round-trips through the draft, and a saved draft is clean again`() {
        val reminder = Reminder(
            id = "r1", text = "Water the plants", tags = listOf("casa"), rules = listOf(TriggerRule(weekly)),
            actions = setOf(Action.FULL_SCREEN), status = Status.PAUSED,
            createdAt = Instant.ofEpochSecond(1), updatedAt = Instant.ofEpochSecond(2),
        )
        val draft = reminder.toDraft()
        val loaded = EditorUiState(loaded = true, isNew = false, draft = draft, initial = draft)
        assertFalse(loaded.dirty)
        val saved = draft.toReminder("r1", reminder.createdAt, Instant.ofEpochSecond(3), Status.PAUSED, zone = zone)
        assertEquals(reminder.copy(updatedAt = Instant.ofEpochSecond(3)), saved)
        assertEquals("trimmed", draft.copy(text = "  trimmed  ").toReminder("x", Instant.EPOCH, Instant.EPOCH, Status.ACTIVE, zone = zone).text)
        assertEquals(TriggerKind.DATE, loaded.commitTrigger(null, Trigger.OnDate(LocalDate.of(2026, 9, 1))).editTrigger(1).let { (it.sheet as EditorSheet.Configure).kind })
    }

    // ---- the calendar in "Vuelve" -------------------------------------------------------

    private val mondays = Trigger.Repeat(
        startsOn = LocalDate.of(2026, 8, 24),
        time = LocalTime.of(9, 0),
        days = setOf(DayOfWeek.MONDAY),
    )

    @Test
    fun `the calendar sheet opens on what is set and writes it back`() {
        val blank = EditorUiState()
        assertEquals(EditorSheet.ConfigureCalendar(null), blank.openCalendar().sheet)

        val set = blank.commitCalendar(mondays)
        assertEquals(Recurrence.Calendar(mondays), set.draft.recurrence)
        assertEquals(EditorSheet.None, set.sheet)
        assertEquals(EditorSheet.ConfigureCalendar(mondays), set.openCalendar().sheet)
    }

    @Test
    fun `a legacy monthly weekday opens as the calendar it always was`() {
        val legacy = EditorUiState().setRecurrence(Recurrence.MonthlyWeekday(1, DayOfWeek.WEDNESDAY))
        val sheet = legacy.openCalendar().sheet as EditorSheet.ConfigureCalendar
        assertEquals(dev.rwilco.model.MonthlyOn.Nth(1, DayOfWeek.WEDNESDAY), sheet.initial?.monthly)
    }

    @Test
    fun `the calendar keeps its fences when the shape is edited`() {
        val window = dev.rwilco.model.Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0))
        val fenced = EditorUiState()
            .commitCalendar(mondays)
            .commitRecurrenceCondition(null, window)
        assertEquals(listOf(window), (fenced.draft.recurrence as Recurrence.Calendar).conditions)

        val reshaped = fenced.commitCalendar(mondays.copy(every = 2))
        assertEquals(Recurrence.Calendar(mondays.copy(every = 2), listOf(window)), reshaped.draft.recurrence)

        assertEquals(Recurrence.Calendar(mondays.copy(every = 2)), reshaped.removeRecurrenceCondition(0).draft.recurrence)
    }

    @Test
    fun `a calendar that ends before it starts blocks the save`() {
        val backwards = mondays.copy(ends = dev.rwilco.model.RepeatEnd.On(LocalDate.of(2026, 8, 1)))
        val state = EditorUiState().withText("Basura").commitCalendar(backwards)
        assertFalse(state.canSave)
        assertTrue(state.errors.any { it is ValidationError.BadRecurrence })
        assertTrue(EditorUiState().withText("Basura").commitCalendar(mondays).canSave)
    }

    @Test
    fun `a random trigger still says it comes back, and nothing else does`() {
        val chance = Trigger.Random(2, dev.rwilco.model.Period.DAY, LocalTime.of(10, 0), LocalTime.of(20, 0))
        assertEquals(Recurrence.ByTrigger, EditorUiState().commitTrigger(null, chance).draft.recurrence)
        val date = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 9, 0))
        assertEquals(Recurrence.None, EditorUiState().commitTrigger(null, date).draft.recurrence)
    }

    @Test
    fun `editing a reminder preset leaves it where it was too`() {
        // The same rule as the recurrence presets', and the same reason: order is what the
        // popularity sort falls back on when two are tied, and Home's quick buttons are the
        // first few of it.
        val born = Instant.parse("2026-08-20T09:00:00Z")
        val presets = (1..3).map { dev.rwilco.model.Preset(id = "p$it", name = "Preset $it", createdAt = born) }
        val renamed = presets[0].copy(name = "La compra")
        assertEquals(listOf("p1", "p2", "p3"), presets.keeping(renamed).map { it.id })
        assertEquals("La compra", presets.keeping(renamed).first().name)
        assertEquals(
            listOf("p1", "p2", "p3", "p4"),
            presets.keeping(dev.rwilco.model.Preset(id = "p4", name = "Nuevo", createdAt = born)).map { it.id },
        )
    }


    @Test
    fun `a day left to the day, saved while it is under way, is drawn from what is left of it`() {
        // "Hoy, a cualquier hora" saved at 17:03: the draw is seeded by an id the save mints,
        // so the words could not be checked against it — and one time in two the minute drawn
        // from the whole day had already gone. The save narrows the day to what is left.
        val today = LocalDate.of(2026, 8, 27)
        val at = LocalDateTime.of(today, LocalTime.of(17, 3, 20)).atZone(zone).toInstant()
        val draft = Draft(text = "Sacar la basura", rules = listOf(TriggerRule(Trigger.DayRandom(today))))
        val saved = draft.toReminder("r1", at, at, Status.ACTIVE, zone = zone)
        val day = saved.rules.single().trigger as Trigger.DayRandom
        assertEquals(LocalTime.of(17, 4), day.window?.from)
        assertEquals(LocalTime.of(23, 30), day.window?.to, "to the default bedtime")
        val next = nextFire(saved, at, zone, LocalTime.of(9, 0))
        assertTrue(next != null && next is dev.rwilco.model.NextFire.Scheduled && next.at > at, "rings tonight: $next")
        // Tomorrow's is left exactly as written.
        val tomorrow = draft.copy(rules = listOf(TriggerRule(Trigger.DayRandom(today.plusDays(1)))))
        assertNull((tomorrow.toReminder("r2", at, at, Status.ACTIVE, zone = zone).rules.single().trigger as Trigger.DayRandom).window)
    }

    @Test
    fun `saving keeps the round under way and the net's word, when asked to`() {
        // "Al llegar a casa, y a las 21:00" under ALL: the arrival ticked off, a typo fixed in
        // the words. A save that rebuilt the row from the draft alone put the arrival back on
        // the list, forgot which rule the last ring was, and let the net say its word twice.
        val at = Instant.parse("2026-08-27T13:00:00Z")
        val draft = Draft(text = "Llamar a Marta", ruleMatch = RuleMatch.ALL)
        val saved = draft.toReminder("r1", at, at, Status.ACTIVE, firedRules = setOf(0), lastFiredRule = 1, nudgedAt = at, zone = zone)
        assertEquals(setOf(0), saved.firedRules)
        assertEquals(1, saved.lastFiredRule)
        assertEquals(at, saved.nudgedAt)
        val fresh = draft.toReminder("r1", at, at, Status.ACTIVE, zone = zone)
        assertEquals(emptySet<Int>(), fresh.firedRules)
        assertEquals(null, fresh.lastFiredRule)
        assertEquals(null, fresh.nudgedAt)
    }

    @Test
    fun `what the words say is offered until the form answers it`() {
        val read = Understood.Once(Trigger.Countdown(20))
        val state = blank.withText("sacar el pan en 20 min").copy(understood = read)
        assertEquals(read, state.understoodOffer())
        val taken = state.commitUnderstood(read)
        assertEquals(listOf(TriggerRule(Trigger.Countdown(20))), taken.draft.rules)
        assertNull(taken.understoodOffer(), "a moment already on the form is not offered twice")
        // A rule added any other way answers the question just the same.
        assertNull(state.commitTrigger(null, tonight).understoodOffer())

        val comes = Understood.Comes(Recurrence.After(6, RecurrenceUnit.HOURS))
        val repeating = blank.withText("pastillas cada 6 horas").copy(understood = comes)
        assertEquals(comes, repeating.understoodOffer())
        val kept = repeating.commitUnderstood(comes)
        assertEquals(Recurrence.After(6, RecurrenceUnit.HOURS), kept.draft.recurrence)
        assertNull(kept.understoodOffer(), "a repeat already in Vuelve is not offered twice")
        assertEquals(comes, repeating.commitTrigger(null, tonight).understoodOffer(), "a rule does not answer a repeat")
    }

    @Test
    fun `a calendar read from the words keeps the fences already on the draft`() {
        val fence = Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0))
        val today = LocalDate.of(2026, 8, 27)
        val old = Trigger.Repeat(today, 1, RepeatUnit.DAY)
        val fenced = blank.setRecurrence(Recurrence.Calendar(old, listOf(fence)))
        val weekly = Trigger.Repeat(today, 1, RepeatUnit.WEEK, LocalTime.of(8, 0), setOf(DayOfWeek.TUESDAY))
        val taken = fenced.commitUnderstood(Understood.Comes(Recurrence.Calendar(weekly)))
        assertEquals(Recurrence.Calendar(weekly, listOf(fence)), taken.draft.recurrence)
    }

    @Test
    fun `a random window answers Vuelve once, and takes the answer with it when it goes`() {
        val window = Trigger.Random(2, dev.rwilco.model.Period.DAY, LocalTime.of(10, 0), LocalTime.of(20, 0))
        val chosen = blank.withText("beber agua").commitTrigger(null, window)
        assertEquals(Recurrence.ByTrigger, chosen.draft.recurrence)
        // Answered "no repetir" on purpose, then the window widened: the answer stands.
        val declined = chosen.setRecurrence(Recurrence.None).commitTrigger(0, window.copy(timesPer = 3))
        assertEquals(Recurrence.None, declined.draft.recurrence)
        // The window removed takes its own answer with it; a date added next starts clean.
        val gone = chosen.removeTrigger(0)
        assertEquals(Recurrence.None, gone.draft.recurrence)
        assertEquals(Recurrence.None, gone.commitTrigger(null, tonight).draft.recurrence)
        // A repeat chosen by hand is not the window's, and stays when the window goes.
        val byHand = chosen.setRecurrence(Recurrence.After(6, RecurrenceUnit.HOURS)).removeTrigger(0)
        assertEquals(Recurrence.After(6, RecurrenceUnit.HOURS), byHand.draft.recurrence)
    }

    @Test
    fun `a routine reads its rules as questions asked one at a time, and keeps a place as it was written`() {
        // "Desde la última vez" picked on a form with a set and a state place: the set goes back
        // to "cualquiera" and its deadline goes. The place is left exactly as it was written —
        // "mientras esté fuera del garaje" is the reading for a phone that is never seen
        // crossing the line, and coercing it to "al salir" lost that question for good.
        val garage = Trigger.Location(40.4, -3.7, 150, Presence.OUTSIDE, "Garaje")
        val nine = Trigger.TimeOfDay(LocalTime.of(9, 0))
        val set = blank.withText("Mover el coche").commitTrigger(null, nine).commitTrigger(null, garage)
            .setRuleMatch(RuleMatch.ALL).commitDeadline(Deadline.Window(LocalTime.of(18, 0), LocalTime.of(22, 0)))
        assertEquals(RuleMatch.ALL, set.draft.ruleMatch)
        val routine = set.setRecurrence(Recurrence.Since(21, RecurrenceUnit.DAYS))
        assertEquals(RuleMatch.ANY, routine.draft.ruleMatch)
        assertNull(routine.draft.deadline)
        assertFalse((routine.draft.rules[1].trigger as Trigger.Location).onCrossing, "a state stays a state")
        assertEquals(nine, routine.draft.rules[0].trigger, "a clock rule is left as it was")
        // The reading cannot be changed under a routine, and a rule added later does not flip it.
        assertEquals(RuleMatch.ANY, routine.setRuleMatch(RuleMatch.TOGETHER).draft.ruleMatch)
        val door = routine.removeTrigger(1).commitTrigger(null, garage.copy(onCrossing = true))
        assertEquals(RuleMatch.ANY, door.draft.ruleMatch)
        assertTrue((door.draft.rules[1].trigger as Trigger.Location).onCrossing, "and a doorway stays a doorway")
        // And a span picked from the buttons keeps it a routine (withSpanOf).
        assertEquals(Recurrence.Since(1, RecurrenceUnit.WEEKS), routine.draft.recurrence.let { it.withSpanOf(Recurrence.After(1, RecurrenceUnit.WEEKS)) })
    }

    @Test
    fun `a rule that is not a question is named under a routine, and a preset from one keeps no start`() {
        val garage = Trigger.Location(40.4, -3.7, 150, Presence.OUTSIDE, "Garaje")
        val nine = Trigger.TimeOfDay(LocalTime.of(9, 0))
        val set = blank.withText("Mover el coche").commitTrigger(null, tonight).commitTrigger(null, nine).commitTrigger(null, garage)
        assertTrue(set.draft.rulesNotQuestions().isEmpty(), "on a reminder every rule rings")
        val routine = set.setRecurrence(Recurrence.Since(21, RecurrenceUnit.DAYS, startsAt = now))
        assertEquals(listOf(0), routine.draft.rulesNotQuestions(), "the date; the hour and the place are questions")
        val preset = routine.toPreset("p", now, null, emptyList())
        assertEquals(Recurrence.Since(21, RecurrenceUnit.DAYS), preset.recurrence, "a shape holds how often, not when one began")
    }

    @Test
    fun `turning a reminder into a routine is the one edit that sheds the old ring`() {
        val rang = Reminder(id = "r", text = "Regar", createdAt = now.minusSeconds(86_400), updatedAt = now, lastFiredAt = now.minusSeconds(3600))
        val routine = blank.withText("Regar").setRecurrence(Recurrence.Since(21, RecurrenceUnit.DAYS)).draft
        assertTrue(becomesRoutine(rang, routine))
        assertFalse(becomesRoutine(rang, blank.withText("Regar").draft), "an ordinary edit carries the ring")
        assertFalse(becomesRoutine(rang.copy(recurrence = Recurrence.Since(7, RecurrenceUnit.DAYS)), routine), "a routine edited stays one, ring and all")
        assertFalse(becomesRoutine(null, routine), "a new routine has no ring to shed")
    }

    @Test
    fun `a routine's count starts where it is told to, and the span it is given does not move it`() {
        val start = Instant.parse("2026-10-01T07:00:00Z")
        val routine = blank.withText("Cambiar el filtro").setRecurrence(Recurrence.Since(3, RecurrenceUnit.MONTHS))
        assertNull((routine.draft.recurrence as Recurrence.Since).startsAt, "written today, by default")
        val later = routine.setRoutineStart(start)
        assertEquals(start, (later.draft.recurrence as Recurrence.Since).startsAt)
        // The plazo is the other card's question: picking a different one keeps the start.
        val shorter = later.setRecurrence(later.draft.recurrence.withSpanOf(Recurrence.After(1, RecurrenceUnit.MONTHS)))
        assertEquals(Recurrence.Since(1, RecurrenceUnit.MONTHS, startsAt = start), shorter.draft.recurrence)
        assertNull((shorter.setRoutineStart(null).draft.recurrence as Recurrence.Since).startsAt, "and back to «ahora mismo»")
        // Nothing to answer on anything that is not a routine.
        assertEquals(Recurrence.None, blank.setRoutineStart(start).draft.recurrence)
    }

    @Test
    fun `what a routine's place does rides beside it on the way in`() {
        val garage = Trigger.Location(40.4, -3.7, 150, Presence.OUTSIDE, "Garaje")
        val routine = blank.withText("Mover el coche").setRecurrence(Recurrence.Since(21, RecurrenceUnit.DAYS))
        val counts = routine.commitTrigger(null, garage, resets = true)
        assertTrue(counts.draft.rules.single().resets, "counts as done")
        assertFalse((counts.draft.rules.single().trigger as Trigger.Location).onCrossing, "written as a state, kept as one")
        // Edited without a word about the role, the rule keeps its answer; with one, it changes.
        val moved = counts.commitTrigger(0, garage.copy(radiusM = 300))
        assertTrue(moved.draft.rules.single().resets)
        val asks = moved.commitTrigger(0, garage, resets = false)
        assertFalse(asks.draft.rules.single().resets)
        // A place added with no role at all asks, which is the reading every rule has by default.
        assertFalse(routine.commitTrigger(null, garage).draft.rules.single().resets)
    }

    /** A close work contact's form as the chooser opens it: Settings' three months, nothing set by hand. */
    private fun contactForm() = EditorUiState(
        loaded = true,
        draft = Draft(
            actions = emptySet(),
            recurrence = Recurrence.Since(3, RecurrenceUnit.MONTHS),
            contactKind = ContactKind.WORK,
            contactCloseness = Closeness.CLOSE,
        ),
    )

    @Test
    fun `a contact's cadence follows Settings through a change of kind or closeness, until it is set by hand`() {
        val form = contactForm()
        assertEquals(Recurrence.Since(5, RecurrenceUnit.MONTHS), form.setContactCloseness(Closeness.DISTANT).draft.recurrence)
        assertEquals(Recurrence.Since(2, RecurrenceUnit.MONTHS), form.setContactKind(ContactKind.PERSONAL).draft.recurrence)
        // Picked by hand: Settings stop reaching it, and closeness no longer moves it.
        val byHand = form.setRecurrence(Recurrence.Since(6, RecurrenceUnit.WEEKS))
        assertTrue(byHand.draft.contactCadenceByHand)
        val sporadic = byHand.setContactCloseness(Closeness.DISTANT)
        assertEquals(Recurrence.Since(6, RecurrenceUnit.WEEKS), sporadic.draft.recurrence)
        // "Volver a Ajustes": following them again, at what they say for it now.
        val reset = sporadic.resetContactCadence()
        assertFalse(reset.draft.contactCadenceByHand)
        assertEquals(Recurrence.Since(5, RecurrenceUnit.MONTHS), reset.draft.recurrence)
    }

    @Test
    fun `where a contact's count starts is not its cadence set by hand, and a routine is never a contact`() {
        val form = contactForm()
        assertFalse(form.setRoutineStart(Instant.parse("2026-10-01T07:00:00Z")).draft.contactCadenceByHand)
        assertFalse(form.setRecurrence(Recurrence.Since(3, RecurrenceUnit.MONTHS)).draft.contactCadenceByHand, "the same span again changes nothing")
        val routine = blank.setRecurrence(Recurrence.Since(1, RecurrenceUnit.WEEKS))
        assertFalse(routine.setRecurrence(Recurrence.Since(2, RecurrenceUnit.WEEKS)).draft.contactCadenceByHand)
        assertEquals(routine, routine.setContactCloseness(Closeness.DISTANT), "nothing to set on a routine")
    }

    @Test
    fun `a contact's days and window become its own once changed, never no day, and go back to Settings`() {
        val form = contactForm()
        val monday = form.toggleContactDay(DayOfWeek.MONDAY)
        assertEquals(setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY), monday.draft.contactDays, "starting from Settings' Wednesday")
        assertEquals(form, form.toggleContactDay(DayOfWeek.WEDNESDAY), "the last day stays on")
        val evening = DayWindow(LocalTime.of(18, 0), LocalTime.of(20, 0))
        val later = monday.setContactWindow(evening)
        assertEquals(evening, later.draft.contactWindow)
        assertEquals(later, later.setContactWindow(DayWindow(LocalTime.of(19, 0), LocalTime.of(19, 0))), "a window with no length is refused")
        val reset = later.resetContactWhen()
        assertNull(reset.draft.contactDays)
        assertNull(reset.draft.contactWindow)
    }

    @Test
    fun `what only a contact has is saved with it, and goes when the count does`() {
        val zone = ZoneId.of("Europe/Madrid")
        val at = Instant.parse("2026-08-31T07:00:00Z")
        val draft = contactForm().toggleContactDay(DayOfWeek.MONDAY).setRecurrence(Recurrence.Since(4, RecurrenceUnit.MONTHS)).withText("Ana").draft
        val saved = draft.toReminder(id = "c1", createdAt = at, now = at, status = Status.ACTIVE, zone = zone)
        assertEquals(ContactKind.WORK, saved.contactKind)
        assertEquals(Closeness.CLOSE, saved.contactCloseness)
        assertTrue(saved.contactCadenceByHand)
        assertEquals(setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY), saved.contactDays)
        assertEquals(draft, saved.toDraft())
        val plain = draft.copy(recurrence = Recurrence.None).toReminder(id = "c1", createdAt = at, now = at, status = Status.ACTIVE, zone = zone)
        assertNull(plain.contactKind)
        assertNull(plain.contactCloseness)
        assertFalse(plain.contactCadenceByHand)
        assertNull(plain.contactDays)
    }
}
