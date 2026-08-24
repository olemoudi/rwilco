package dev.rwilco.ui.editor

import dev.rwilco.model.Action
import dev.rwilco.model.DEFAULT_ACTIONS
import dev.rwilco.model.MAX_TEXT_LENGTH
import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerKind
import dev.rwilco.model.ValidationError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class EditorStateTest {

    private val tonight = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 30))
    private val weekly = Trigger.AtTime(LocalTime.of(7, 30), setOf(DayOfWeek.MONDAY))
    private val blank = EditorUiState(loaded = true, existingTags = listOf("Compra", "casa"))

    @Test
    fun `a blank editor is clean, invalid and quiet about it`() {
        assertFalse(blank.dirty)
        assertFalse(blank.canSave)
        assertEquals(listOf(ValidationError.TextBlank, ValidationError.NoTrigger), blank.errors)
        assertFalse(blank.showErrors)
    }

    @Test
    fun `typing and adding a trigger makes it dirty and saveable`() {
        val state = blank.withText("Water the plants").commitTrigger(null, tonight)
        assertTrue(state.dirty)
        assertTrue(state.canSave)
        assertEquals(EditorSheet.None, state.sheet)
        assertEquals(listOf(tonight), state.draft.triggers)
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
    fun `actions toggle and losing the last one is an error`() {
        val none = blank.toggleAction(Action.NOTIFICATION).toggleAction(Action.VIBRATE)
        assertTrue(none.draft.actions.isEmpty())
        assertTrue(ValidationError.NoAction in none.errors)
        assertEquals(DEFAULT_ACTIONS + Action.SOUND, blank.toggleAction(Action.SOUND).draft.actions)
    }

    @Test
    fun `editing a trigger replaces it in place and removing drops it`() {
        var state = blank.commitTrigger(null, tonight).commitTrigger(null, weekly)
        state = state.editTrigger(1)
        assertEquals(EditorSheet.Configure(TriggerKind.REPEAT_TIME, 1, weekly), state.sheet)
        val changed = weekly.copy(time = LocalTime.of(8, 0))
        state = state.commitTrigger(1, changed)
        assertEquals(listOf(tonight, changed), state.draft.triggers)
        state = state.removeTrigger(0)
        assertEquals(listOf(changed), state.draft.triggers)
        assertEquals(state, state.editTrigger(5), "editing a row that is not there is a no-op")
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
            id = "r1", text = "Water the plants", tags = listOf("casa"), triggers = listOf(weekly),
            actions = setOf(Action.FULL_SCREEN), status = Status.PAUSED,
            createdAt = Instant.ofEpochSecond(1), updatedAt = Instant.ofEpochSecond(2),
        )
        val draft = reminder.toDraft()
        val loaded = EditorUiState(loaded = true, isNew = false, draft = draft, initial = draft)
        assertFalse(loaded.dirty)
        val saved = draft.toReminder("r1", reminder.createdAt, Instant.ofEpochSecond(3), Status.PAUSED)
        assertEquals(reminder.copy(updatedAt = Instant.ofEpochSecond(3)), saved)
        assertEquals("trimmed", draft.copy(text = "  trimmed  ").toReminder("x", Instant.EPOCH, Instant.EPOCH, Status.ACTIVE).text)
        assertEquals(TriggerKind.DATE, loaded.commitTrigger(null, Trigger.OnDate(LocalDate.of(2026, 9, 1))).editTrigger(1).let { (it.sheet as EditorSheet.Configure).kind })
    }
}
