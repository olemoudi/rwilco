package dev.rwilco.ui.editor

import dev.rwilco.data.ReminderEntity
import dev.rwilco.data.toDomain
import dev.rwilco.data.toEntity
import dev.rwilco.model.Reminder
import dev.rwilco.model.ReminderCodec
import dev.rwilco.model.Status
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/**
 * A half-written form, as it is handed to the system when the process may be about to die.
 *
 * The draft lived in a `MutableStateFlow` and nowhere else, so a reminder being written when the
 * phone wanted its memory back — a call, the camera, ten minutes in another app looking up the
 * address — came back as a blank form (0.133.0; noted as open since 0.93.0). A rotation was
 * never the problem: the ViewModel survives that.
 *
 * **The row's own stored shape, and not a new one.** A draft is a reminder minus its bookkeeping,
 * and [ReminderEntity] is already how a reminder is written down — the frozen columns the vault
 * travels on, with every tolerance a read has (a rule this build cannot read costs that rule and
 * nothing else). A second serial form of the same thing would be a second thing to keep frozen.
 * What is not part of a reminder rides beside it: whether the form is writing a preset, and the
 * words that preset's reminders start with.
 */
@Serializable
data class SavedEditor(val row: ReminderEntity, val asPreset: Boolean = false, val presetText: String = "")

/** What comes back out of a [SavedEditor]: the three things a form holds that a row does not decide. */
data class RestoredEditor(val draft: Draft, val asPreset: Boolean, val presetText: String)

/**
 * The form as it stands, for the saved state.
 *
 * **Not through [toReminder]**, which is for a save: it trims the words, starts a countdown and
 * narrows a day left to the day. A form put away and taken out again has to be the form it was —
 * a trailing space still there, a countdown still not ticking.
 */
fun EditorUiState.toSavedJson(draftId: String, now: Instant): String {
    val row = Reminder(
        id = draftId,
        text = draft.text,
        tags = draft.tags,
        rules = draft.rules,
        recurrence = draft.recurrence,
        ruleMatch = draft.ruleMatch,
        actions = draft.actions,
        status = Status.ACTIVE,
        createdAt = now,
        updatedAt = now,
        deadline = draft.deadline,
        contactKind = draft.contactKind,
        contactCloseness = draft.contactCloseness,
        contactCadenceByHand = draft.contactCadenceByHand,
        contactDays = draft.contactDays,
        contactWindow = draft.contactWindow,
    ).toEntity()
    return ReminderCodec.json.encodeToString(SavedEditor.serializer(), SavedEditor(row, asPreset, presetText))
}

/** Null for nothing saved, and for anything that will not read: a blank form beats a crash on the way back in. */
fun savedEditorOf(json: String?, zone: ZoneId): RestoredEditor? {
    if (json.isNullOrBlank()) return null
    val saved = runCatching { ReminderCodec.json.decodeFromString(SavedEditor.serializer(), json) }.getOrNull() ?: return null
    return RestoredEditor(saved.row.toDomain(zone).toDraft(), saved.asPreset, saved.presetText)
}

/**
 * A loaded form with what was being written put back on it.
 *
 * Only the three things somebody types into: [EditorUiState.initial] and its two companions stay
 * what the row (or the preset, or the blank) says, so the form comes back *dirty* — which is the
 * truth, and what makes Back ask before throwing the words away a second time. The keyboard does
 * not open by itself over a form somebody is returning to.
 */
fun EditorUiState.withRestored(restored: RestoredEditor?): EditorUiState =
    if (restored == null) this
    else copy(draft = restored.draft, asPreset = restored.asPreset, presetText = restored.presetText, focusText = false)
