package dev.rwilco.ui.editor

import dev.rwilco.model.Action
import dev.rwilco.model.Closeness
import dev.rwilco.model.Condition
import dev.rwilco.model.ContactKind
import dev.rwilco.model.DayWindow
import dev.rwilco.model.Deadline
import dev.rwilco.model.Presence
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.ReminderCodec
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * A form put away when the process dies and taken out again has to be the form it was.
 *
 * The seam to the system (`SavedStateHandle`) is proved on a device, by `EditorDraftRestoreTest`;
 * this is the half that needs no phone: what goes into the saved state comes back out whole.
 */
class SavedEditorTest {

    private val zone = ZoneId.of("Europe/Madrid")
    private val now: Instant = LocalDateTime.of(2026, 9, 17, 18, 0).atZone(zone).toInstant()
    private val home = Trigger.Location(40.4168, -3.7038, 150, Presence.INSIDE, "casa", onCrossing = true)
    private val evenings = Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0))
    private val mondays = Trigger.Repeat(startsOn = LocalDate.of(2026, 9, 21), time = LocalTime.of(9, 0), days = setOf(DayOfWeek.MONDAY))

    private fun form(draft: Draft, asPreset: Boolean = false, presetText: String = "") =
        EditorUiState(loaded = true, draft = draft, asPreset = asPreset, presetText = presetText)

    private fun roundTrip(state: EditorUiState) = savedEditorOf(state.toSavedJson("draft-id", now), zone)

    @Test
    fun `a draft survives the saved form whatever it holds`() {
        val draft = Draft(
            // The trailing space is somebody in the middle of a word: a save would trim it, a
            // form being put away must not.
            text = "Llamar a Marta ",
            tags = listOf("casa", "Familia"),
            rules = listOf(
                TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 9, 18, 21, 30))),
                TriggerRule(home, conditions = listOf(evenings)),
            ),
            ruleMatch = RuleMatch.ALL,
            recurrence = Recurrence.Calendar(mondays, listOf(evenings)),
            actions = setOf(Action.FULL_SCREEN, Action.SOUND_UNTIL_ANSWERED),
            deadline = Deadline.Timer(90),
        )
        val back = roundTrip(form(draft))!!
        assertEquals(draft, back.draft)
        assertFalse(back.asPreset)
        assertEquals("", back.presetText)
    }

    @Test
    fun `a contact and a preset being written come back as what they were`() {
        val contact = Draft(
            text = "Ana",
            recurrence = Recurrence.Since(3, RecurrenceUnit.MONTHS),
            actions = emptySet(),
            contactKind = ContactKind.WORK,
            contactCloseness = Closeness.DISTANT,
            contactCadenceByHand = true,
            contactDays = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
            contactWindow = DayWindow(LocalTime.of(10, 0), LocalTime.of(13, 0)),
        )
        assertEquals(contact, roundTrip(form(contact))!!.draft)

        val preset = roundTrip(form(Draft(text = "La compra del sábado"), asPreset = true, presetText = "pan, café y pilas"))!!
        assertTrue(preset.asPreset)
        assertEquals("pan, café y pilas", preset.presetText)
    }

    @Test
    fun `a countdown that has not started comes back still not started`() {
        // A save stamps it (startCountdowns). Putting the form away is not a save: half an hour
        // asked for at six must not come back as "six thirty" when the form is opened at eight.
        val draft = Draft(text = "Sacar la pizza", rules = listOf(TriggerRule(Trigger.Countdown(30))))
        val back = roundTrip(form(draft))!!.draft
        assertNull((back.rules.single().trigger as Trigger.Countdown).startedAt)
        assertEquals(draft, back)
    }

    @Test
    fun `a rule this build cannot read costs that rule, and the rest comes back`() {
        val draft = Draft(text = "Regar", rules = listOf(TriggerRule(home)))
        val saved = ReminderCodec.json.decodeFromString(SavedEditor.serializer(), form(draft).toSavedJson("draft-id", now))
        val fromTheFuture = """{"trigger":{"type":"from_a_newer_build","x":1}}"""
        val spliced = saved.copy(row = saved.row.copy(triggers = "[" + fromTheFuture + "," + saved.row.triggers.removePrefix("[")))
        val back = savedEditorOf(ReminderCodec.json.encodeToString(SavedEditor.serializer(), spliced), zone)!!
        assertEquals(draft, back.draft)
    }

    @Test
    fun `garbage is nothing saved, never a crash on the way back in`() {
        assertNull(savedEditorOf(null, zone))
        assertNull(savedEditorOf("", zone))
        assertNull(savedEditorOf("not json at all", zone))
        assertNull(savedEditorOf("{}", zone), "no row is no form")
    }

    @Test
    fun `what was being written goes back on a form that knows it has unsaved changes`() {
        val row = Draft(text = "Comprar pan", tags = listOf("compra"))
        val loaded = EditorUiState(loaded = true, isNew = false, draft = row, initial = row, focusText = true)
        assertFalse(loaded.dirty)

        val restored = loaded.withRestored(RestoredEditor(row.copy(text = "Comprar pan y leche"), asPreset = false, presetText = ""))
        assertEquals("Comprar pan y leche", restored.draft.text)
        assertEquals(row, restored.initial, "the yardstick is still what the row says")
        assertTrue(restored.dirty, "so Back asks before the words are thrown away a second time")
        assertFalse(restored.focusText, "and the keyboard does not open over a form somebody is coming back to")
        // Nothing saved is nothing changed.
        assertEquals(loaded, loaded.withRestored(null))
    }
}
