package dev.rwilco.ui.editor

import dev.rwilco.model.Action
import dev.rwilco.model.DEFAULT_ACTIONS
import dev.rwilco.model.MAX_TEXT_LENGTH
import dev.rwilco.model.keeping
import dev.rwilco.model.Recurrence
import dev.rwilco.model.nextFire
import dev.rwilco.model.RecurrenceUnit
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

class EditorStateTest {

    private val tonight = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 30))
    private val weekly = Trigger.AtTime(LocalTime.of(7, 30), setOf(DayOfWeek.MONDAY))
    private val blank = EditorUiState(loaded = true, existingTags = listOf("Compra", "casa"))
    private val zone = ZoneId.of("Europe/Madrid")

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
        val none = blank.withText("Regar").toggleAction(Action.NOTIFICATION).toggleAction(Action.VIBRATE)
        assertTrue(none.draft.actions.isEmpty())
        assertTrue(none.canSave)
        assertEquals(DEFAULT_ACTIONS + Action.SOUND, blank.toggleAction(Action.SOUND).draft.actions)
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
}
