package dev.rwilco.ui.editor

import dev.rwilco.model.OFFERED_KINDS
import dev.rwilco.model.Action
import dev.rwilco.model.toggling
import dev.rwilco.model.Condition
import dev.rwilco.model.DayShape
import dev.rwilco.model.DEFAULT_ACTIONS
import dev.rwilco.model.MAX_PRESET_NAME
import dev.rwilco.model.MAX_TEXT_LENGTH
import dev.rwilco.model.Preset
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrencePreset
import dev.rwilco.model.Reminder
import dev.rwilco.model.nextPresetColor
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.SavedPlace
import dev.rwilco.model.Status
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerKind
import dev.rwilco.model.TriggerRule
import dev.rwilco.model.ValidationError
import dev.rwilco.model.asRepeat
import dev.rwilco.model.clearCountdowns
import dev.rwilco.model.conditions
import dev.rwilco.model.withConditions
import dev.rwilco.model.kind
import dev.rwilco.model.startCountdowns
import dev.rwilco.model.normalizeTag
import dev.rwilco.model.validate
import java.time.Instant
import java.time.LocalTime

/** What the editor is editing: the reminder minus its identity and bookkeeping. */
data class Draft(
    val text: String = "",
    val tags: List<String> = emptyList(),
    val rules: List<TriggerRule> = emptyList(),
    /** Only means anything with more than one rule; the editor hides the choice until then. */
    val ruleMatch: RuleMatch = RuleMatch.ANY,
    /** How it comes back after being dealt with. Asked for, never assumed. */
    val recurrence: Recurrence = Recurrence.None,
    val actions: Set<Action> = DEFAULT_ACTIONS,
)

fun Reminder.toDraft() = Draft(text = text, tags = tags, rules = rules, ruleMatch = ruleMatch, actions = actions, recurrence = recurrence)

/**
 * Note what is NOT carried over: a snooze and the armed moment. Editing a reminder re-decides
 * when it rings, so a "remind me in ten minutes" from the old shape has no meaning, and the
 * scheduler writes the armed moment again the instant this is saved.
 *
 * Two things are carried, and both have to be passed in because a save replaces the whole row.
 * [lastDealtAt] is not a firing's leftovers but the anchor every recurrence is measured from,
 * and dropping it to fix a typo either stops the reminder dead — with triggers there is nothing
 * left to count from until it is dealt with again — or throws its next moment back to the day it
 * was written. [lastFiredAt] is what makes a moment SPENT, and for an anchored recurrence it is
 * the only thing that does: its moment comes from an anchor that does not move until somebody
 * deals with the firing, so without the last ring an edit hands back a moment already gone —
 * on Home as "lo siguiente" in the past, and armed for an alarm that arrives at once.
 */
fun Draft.toReminder(
    id: String,
    createdAt: Instant,
    now: Instant,
    status: Status,
    lastDealtAt: Instant? = null,
    lastFiredAt: Instant? = null,
): Reminder = Reminder(
    id = id,
    text = text.trim(),
    tags = tags,
    // A countdown that has not started begins now; one already ticking keeps its own start, so
    // fixing a typo does not put half an hour back on the clock.
    rules = startCountdowns(rules, now),
    ruleMatch = ruleMatch,
    actions = actions,
    recurrence = recurrence,
    status = status,
    createdAt = createdAt,
    updatedAt = now,
    lastDealtAt = lastDealtAt,
    lastFiredAt = lastFiredAt,
)

/** Which of the two lists the editor offers back is being mended. */
enum class CurateKind { TEXTS, TAGS }

/** What is open on top of the editor. State, not navigation: it must survive rotation. */
sealed interface EditorSheet {
    data object None : EditorSheet
    data object PickKind : EditorSheet

    /** Configuring a trigger; [index] is null when adding, otherwise the row being edited. */
    data class Configure(val kind: TriggerKind, val index: Int?, val initial: Trigger?) : EditorSheet

    /** A restriction on the rule at [ruleIndex]; [conditionIndex] is null when adding one. */
    data class ConfigureCondition(val ruleIndex: Int, val conditionIndex: Int?, val initial: Condition?) : EditorSheet

    /** The calendar in "Vuelve"; [initial] is the one set, or null when there is none yet. */
    data class ConfigureCalendar(val initial: Trigger.Repeat?) : EditorSheet

    /** A fence on that calendar — the same "y sólo si" a rule has; [index] null when adding. */
    data class ConfigureRecurrenceCondition(val index: Int?, val initial: Condition?) : EditorSheet
}

data class EditorUiState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val draft: Draft = Draft(),
    /** What was loaded (or the blank draft): the yardstick for "unsaved changes". */
    val initial: Draft = Draft(),
    /** Tags already in use, most-used-recently first. */
    val existingTags: List<String> = emptyList(),
    /** Reminder texts written before, most-used-recently first: the offer instead of a keyboard. */
    val suggestedTexts: List<String> = emptyList(),
    val sheet: EditorSheet = EditorSheet.None,
    val previewing: Boolean = false,
    /** Errors are only shown once a save was attempted; before that the form stays quiet. */
    val showErrors: Boolean = false,
    /** The "discard changes?" dialog; state so a rotation does not lose it. */
    val confirmingDiscard: Boolean = false,
    val defaultTime: LocalTime = LocalTime.of(9, 0),
    /** The hours this person is up: what "at random during the day" is drawn from. */
    val dayShape: DayShape = DayShape.DEFAULT,
    /** The kind the picker offers first, from the settings; null when there is no favourite. */
    val defaultKind: TriggerKind? = null,
    /** The order the six tiles come up in: their usual one, or what gets used most. */
    val kindOrder: List<TriggerKind> = OFFERED_KINDS,
    /** The "when"s used before, best first, already re-hung on now. */
    val suggestedTriggers: List<Trigger> = emptyList(),
    /** The places kept by name in Settings, offered whole in the place sheet. */
    val savedPlaces: List<SavedPlace> = emptyList(),
    /**
     * Whether what is being written is a preset rather than a reminder: same form, same four
     * parts, but nothing waiting to ring — and the words become the preset's name.
     */
    val asPreset: Boolean = false,
    /** What it was when the screen opened, so flipping the toggle counts as an unsaved change. */
    val initialAsPreset: Boolean = false,
    /** The preset being edited, when this screen was opened on one. */
    val editingPreset: Preset? = null,
    /** Everything ever written, for the panel that mends the offers; the chips show fewer. */
    val allTexts: List<String> = emptyList(),
    /** Which list of offers is being mended, if any. */
    val curating: CurateKind? = null,
    /** The recurrences kept under a name, most used first. */
    val recurrencePresets: List<RecurrencePreset> = emptyList(),
    /** The preset this reminder was started from, when it was: named on the screen. */
    val fromPresetName: String? = null,
    /**
     * A preset's default words, while one is being written. Empty means the reminders made from
     * it start blank with the keyboard up. Kept out of [draft] because a draft is a reminder in
     * waiting and this is not part of one.
     */
    val presetText: String = "",
    /** What it was on arrival, so typing into it counts as an unsaved change. */
    val initialPresetText: String = "",
    /**
     * Whether to put the cursor in the words and open the keyboard on arrival. True only when
     * a preset answered everything else: the offers below are not the fast path any more, and
     * the one thing left to do is type.
     */
    val focusText: Boolean = false,
) {
    val dirty: Boolean get() = draft != initial || asPreset != initialAsPreset || presetText != initialPresetText
    val errors: List<ValidationError> get() = validate(draft.text, draft.rules, draft.recurrence)
    val canSave: Boolean get() = errors.isEmpty()
}

/** The toggle. Nothing else about the draft changes: a preset keeps all four parts. */
fun EditorUiState.setAsPreset(asPreset: Boolean): EditorUiState = copy(asPreset = asPreset)

/** The words reminders made from this preset start with; empty for "ask me every time". */
fun EditorUiState.setPresetText(text: String): EditorUiState = copy(presetText = text.take(MAX_TEXT_LENGTH))

/** The draft as a preset — new when [existing] is null, otherwise that one rewritten. */
fun EditorUiState.toPreset(id: String, now: Instant, existing: Preset?, others: List<Preset>): Preset = Preset(
    id = id,
    name = draft.text.trim().take(MAX_PRESET_NAME),
    text = presetText.trim(),
    tags = draft.tags,
    // A shape holds the length of a countdown, never the moment it once landed on.
    rules = clearCountdowns(draft.rules),
    ruleMatch = draft.ruleMatch,
    actions = draft.actions,
    recurrence = draft.recurrence,
    // A preset keeps the colour it was given: it is how it is recognised, and a colour that
    // moves is worse than no colour at all.
    colorIndex = existing?.colorIndex ?: nextPresetColor(others),
    uses = existing?.uses ?: 0,
    lastUsedAt = existing?.lastUsedAt,
    createdAt = existing?.createdAt ?: now,
)

fun EditorUiState.withText(text: String): EditorUiState =
    copy(draft = draft.copy(text = text.take(MAX_TEXT_LENGTH)))

/** A tag on the draft comes off; one off the draft (or brand new) goes on. */
fun EditorUiState.toggleTag(tag: String): EditorUiState {
    val present = draft.tags.firstOrNull { it.equals(tag, ignoreCase = true) }
    val tags = if (present != null) draft.tags - present else draft.tags + tag
    return copy(draft = draft.copy(tags = tags))
}

/** Free text from the "new tag" field: normalised, and a no-op when blank or already on. */
fun EditorUiState.addTag(raw: String): EditorUiState {
    val tag = normalizeTag(raw) ?: return this
    if (draft.tags.any { it.equals(tag, ignoreCase = true) }) return this
    // Reuse the existing spelling so "compra" and "Compra" stay one tag across reminders.
    val spelling = existingTags.firstOrNull { it.equals(tag, ignoreCase = true) } ?: tag
    return copy(draft = draft.copy(tags = draft.tags + spelling))
}

/** What "hecho" means for this one. */
fun EditorUiState.setRecurrence(recurrence: Recurrence): EditorUiState = copy(draft = draft.copy(recurrence = recurrence))

/**
 * The calendar sheet, opened on whatever is already set — including a legacy "el cuarto
 * miércoles", which opens as the calendar it always was and is written back as one.
 */
fun EditorUiState.openCalendar(): EditorUiState =
    copy(sheet = EditorSheet.ConfigureCalendar(draft.recurrence.asRepeat()))

/** The sheet's result. The fences already on the calendar stay on it. */
fun EditorUiState.commitCalendar(repeat: Trigger.Repeat): EditorUiState = copy(
    draft = draft.copy(recurrence = Recurrence.Calendar(repeat, draft.recurrence.conditions)),
    sheet = EditorSheet.None,
)

fun EditorUiState.addRecurrenceCondition(): EditorUiState =
    copy(sheet = EditorSheet.ConfigureRecurrenceCondition(null, null))

fun EditorUiState.editRecurrenceCondition(index: Int): EditorUiState {
    val condition = draft.recurrence.conditions.getOrNull(index) ?: return this
    return copy(sheet = EditorSheet.ConfigureRecurrenceCondition(index, condition))
}

fun EditorUiState.removeRecurrenceCondition(index: Int): EditorUiState =
    withRecurrenceConditions { it.filterIndexed { at, _ -> at != index } }

fun EditorUiState.commitRecurrenceCondition(index: Int?, condition: Condition): EditorUiState =
    withRecurrenceConditions { conditions ->
        if (index != null && index in conditions.indices) {
            conditions.mapIndexed { at, existing -> if (at == index) condition else existing }
        } else {
            conditions + condition
        }
    }.copy(sheet = EditorSheet.None)

private fun EditorUiState.withRecurrenceConditions(
    transform: (List<Condition>) -> List<Condition>,
): EditorUiState =
    copy(draft = draft.copy(recurrence = draft.recurrence.withConditions(transform(draft.recurrence.conditions))))

/**
 * Changing how the rules combine starts the round over: what had already happened under ALL was
 * an answer to a different question, and carrying it into the new shape would ring something
 * half-satisfied by history.
 */
fun EditorUiState.setRuleMatch(match: RuleMatch): EditorUiState =
    copy(draft = draft.copy(ruleMatch = match))

/**
 * A tile on or off — except that the two sound tiles are one choice. Asking for a sound once
 * and also for it until somebody answers is asking for two contradictory things, so turning on
 * either puts the other away rather than leaving the reminder to be interpreted later.
 */
fun EditorUiState.toggleAction(action: Action): EditorUiState =
    copy(draft = draft.copy(actions = draft.actions.toggling(action)))

fun EditorUiState.openKindPicker(): EditorUiState = copy(sheet = EditorSheet.PickKind)

fun EditorUiState.pickKind(kind: TriggerKind): EditorUiState =
    copy(sheet = EditorSheet.Configure(kind, index = null, initial = null))

fun EditorUiState.editTrigger(index: Int): EditorUiState {
    val trigger = draft.rules.getOrNull(index)?.trigger ?: return this
    return copy(sheet = EditorSheet.Configure(trigger.kind, index, trigger))
}

fun EditorUiState.removeTrigger(index: Int): EditorUiState =
    copy(draft = draft.copy(rules = draft.rules.filterIndexed { i, _ -> i != index }))

/**
 * The configurator's result: replaces the trigger of the rule being edited — keeping whatever
 * conditions were put on it — or appends a rule with no conditions. Closes the sheet.
 */
fun EditorUiState.commitTrigger(index: Int?, trigger: Trigger): EditorUiState {
    val rules = if (index != null && index in draft.rules.indices) {
        draft.rules.mapIndexed { i, rule -> if (i == index) rule.copy(trigger = trigger) else rule }
    } else {
        draft.rules + TriggerRule(trigger)
    }
    // Choosing "at random" IS choosing a recurrence — "tres veces al día" says so outright — so
    // it says so in plain sight, right under the row, and changeable. Every other kind leaves
    // the answer alone: a place or a date is one-shot until somebody says otherwise, and a
    // calendar is asked for in "Vuelve" rather than arrived at from here.
    val recurrence = when {
        draft.recurrence != Recurrence.None -> draft.recurrence
        trigger is Trigger.Random -> Recurrence.ByTrigger
        else -> draft.recurrence
    }
    return copy(draft = draft.copy(rules = rules, recurrence = recurrence), sheet = EditorSheet.None)
}

fun EditorUiState.addCondition(ruleIndex: Int): EditorUiState =
    if (ruleIndex !in draft.rules.indices) this
    else copy(sheet = EditorSheet.ConfigureCondition(ruleIndex, null, null))

fun EditorUiState.editCondition(ruleIndex: Int, conditionIndex: Int): EditorUiState {
    val condition = draft.rules.getOrNull(ruleIndex)?.conditions?.getOrNull(conditionIndex) ?: return this
    return copy(sheet = EditorSheet.ConfigureCondition(ruleIndex, conditionIndex, condition))
}

fun EditorUiState.removeCondition(ruleIndex: Int, conditionIndex: Int): EditorUiState =
    mapRule(ruleIndex) { rule -> rule.copy(conditions = rule.conditions.filterIndexed { i, _ -> i != conditionIndex }) }

fun EditorUiState.commitCondition(ruleIndex: Int, conditionIndex: Int?, condition: Condition): EditorUiState =
    mapRule(ruleIndex) { rule ->
        val conditions = if (conditionIndex != null && conditionIndex in rule.conditions.indices) {
            rule.conditions.mapIndexed { i, existing -> if (i == conditionIndex) condition else existing }
        } else {
            rule.conditions + condition
        }
        rule.copy(conditions = conditions)
    }.copy(sheet = EditorSheet.None)

private fun EditorUiState.mapRule(index: Int, transform: (TriggerRule) -> TriggerRule): EditorUiState {
    if (index !in draft.rules.indices) return this
    return copy(draft = draft.copy(rules = draft.rules.mapIndexed { i, rule -> if (i == index) transform(rule) else rule }))
}

fun EditorUiState.closeSheet(): EditorUiState = copy(sheet = EditorSheet.None)
