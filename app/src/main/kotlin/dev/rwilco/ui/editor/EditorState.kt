package dev.rwilco.ui.editor

import dev.rwilco.model.Action
import dev.rwilco.model.Condition
import dev.rwilco.model.DEFAULT_ACTIONS
import dev.rwilco.model.MAX_TEXT_LENGTH
import dev.rwilco.model.Reminder
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.SavedPlace
import dev.rwilco.model.Status
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerKind
import dev.rwilco.model.TriggerRule
import dev.rwilco.model.ValidationError
import dev.rwilco.model.kind
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
    val actions: Set<Action> = DEFAULT_ACTIONS,
)

fun Reminder.toDraft() = Draft(text = text, tags = tags, rules = rules, ruleMatch = ruleMatch, actions = actions)

/**
 * Note what is NOT carried over: a snooze, the last ring, the armed moment. Editing a reminder
 * re-decides when it rings, so a "remind me in ten minutes" from the old shape has no meaning,
 * and the scheduler writes the armed moment again the instant this is saved.
 */
fun Draft.toReminder(id: String, createdAt: Instant, now: Instant, status: Status): Reminder = Reminder(
    id = id,
    text = text.trim(),
    tags = tags,
    rules = rules,
    ruleMatch = ruleMatch,
    actions = actions,
    status = status,
    createdAt = createdAt,
    updatedAt = now,
)

/** What is open on top of the editor. State, not navigation: it must survive rotation. */
sealed interface EditorSheet {
    data object None : EditorSheet
    data object PickKind : EditorSheet

    /** Configuring a trigger; [index] is null when adding, otherwise the row being edited. */
    data class Configure(val kind: TriggerKind, val index: Int?, val initial: Trigger?) : EditorSheet

    /** A restriction on the rule at [ruleIndex]; [conditionIndex] is null when adding one. */
    data class ConfigureCondition(val ruleIndex: Int, val conditionIndex: Int?, val initial: Condition?) : EditorSheet
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
    /** The kind the picker offers first, from the settings; null when there is no favourite. */
    val defaultKind: TriggerKind? = null,
    /** The places kept by name in Settings, offered whole in the place sheet. */
    val savedPlaces: List<SavedPlace> = emptyList(),
) {
    val dirty: Boolean get() = draft != initial
    val errors: List<ValidationError> get() = validate(draft.text, draft.rules)
    val canSave: Boolean get() = errors.isEmpty()
}

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

/**
 * Changing how the rules combine starts the round over: what had already happened under ALL was
 * an answer to a different question, and carrying it into the new shape would ring something
 * half-satisfied by history.
 */
fun EditorUiState.setRuleMatch(match: RuleMatch): EditorUiState =
    copy(draft = draft.copy(ruleMatch = match))

fun EditorUiState.toggleAction(action: Action): EditorUiState =
    copy(draft = draft.copy(actions = if (action in draft.actions) draft.actions - action else draft.actions + action))

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
    return copy(draft = draft.copy(rules = rules), sheet = EditorSheet.None)
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
