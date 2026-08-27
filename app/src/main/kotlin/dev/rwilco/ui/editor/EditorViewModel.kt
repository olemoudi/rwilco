package dev.rwilco.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rwilco.RwilcoApplication
import dev.rwilco.data.ReminderRepository
import dev.rwilco.data.SettingsStore
import dev.rwilco.model.OFFERED_KINDS
import dev.rwilco.model.dayShape
import dev.rwilco.model.Action
import dev.rwilco.model.AppSettings
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrencePreset
import dev.rwilco.model.withSpanOf
import dev.rwilco.model.keeping
import dev.rwilco.model.recurrencePresetsByPopularity
import dev.rwilco.model.used
import dev.rwilco.model.Reminder
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.Status
import dev.rwilco.model.Condition
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerKind
import dev.rwilco.model.normalizeTag
import dev.rwilco.model.normalizeTags
import dev.rwilco.model.removeTagIn
import dev.rwilco.model.renameTagIn
import dev.rwilco.model.renameTextIn
import dev.rwilco.model.suggestedTags
import dev.rwilco.model.suggestedTriggers
import dev.rwilco.model.triggerKindsByUse
import dev.rwilco.model.suggestedTexts
import dev.rwilco.model.visibleTexts
import dev.rwilco.model.withHiddenText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.UUID

sealed interface EditorEvent {
    data object Saved : EditorEvent
    data class Deleted(val reminder: Reminder) : EditorEvent
    data object Close : EditorEvent
}

/**
 * Holds the draft and applies the pure reducers in EditorState.kt; the only I/O is the initial
 * load and the save/delete at the end.
 */
class EditorViewModel(
    private val reminderId: String?,
    private val fromPresetId: String?,
    private val editPresetId: String?,
    private val newPreset: Boolean,
    private val repository: ReminderRepository,
    private val store: SettingsStore,
    private val settings: Flow<AppSettings?>,
    /**
     * Called after a save, to set the alarm again. A save writes a row with **no armed moment**
     * — editing re-decides when a reminder rings — and the collector that normally re-arms only
     * wakes for changes to what scheduling depends on (`schedulingKey`), which deliberately
     * leaves out the words. So editing only the text left a reminder with an alarm still set
     * and a row saying nothing was armed, and `ReminderFiring` drops a firing it has no armed
     * moment for: the reminder went quiet until the next re-arm from somewhere else.
     */
    private val rearm: suspend () -> Unit,
    val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state

    private val events = Channel<EditorEvent>(Channel.BUFFERED)
    val eventFlow: Flow<EditorEvent> = events.receiveAsFlow()

    private var existing: Reminder? = null

    init {
        viewModelScope.launch {
            val current = settings.filterNotNull().first()
            val loaded = reminderId?.let { repository.get(it) }
            existing = loaded
            // One of four openings: an existing reminder, a preset being edited, a new
            // reminder wearing a preset's shape, or a blank one.
            val editedPreset = editPresetId?.let { id -> current.presets.firstOrNull { it.id == id } }
            val source = editedPreset ?: fromPresetId?.let { id -> current.presets.firstOrNull { it.id == id } }
            val draft = when {
                loaded != null -> loaded.toDraft()
                // Editing the preset itself starts from its name; STARTING one from it does
                // not — the name labels the shape, and the words are what is still missing.
                editedPreset != null -> Draft(text = editedPreset.name, tags = editedPreset.tags, rules = editedPreset.rules, ruleMatch = editedPreset.ruleMatch, actions = editedPreset.actions, recurrence = editedPreset.recurrence)
                source != null -> Draft(text = source.text, tags = source.tags, rules = source.rules, ruleMatch = source.ruleMatch, actions = source.actions, recurrence = source.recurrence)
                else -> Draft(actions = current.defaultActions)
            }
            // Everything ever written, done included: the point is to hand back what has been
            // said before rather than ask for it again.
            val past = repository.allNow()
            val now = clock.instant()
            _state.value = EditorUiState(
                loaded = true,
                isNew = loaded == null,
                draft = draft,
                initial = draft,
                existingTags = suggestedTags(past, now),
                suggestedTexts = visibleTexts(suggestedTexts(past, now, limit = 8, exclude = draft.text), current.hiddenTexts),
                allTexts = visibleTexts(suggestedTexts(past, now, limit = 100), current.hiddenTexts),
                defaultTime = current.defaultTime,
                dayShape = current.dayShape,
                defaultKind = if (current.popularTriggersFirst) null else current.defaultTriggerKind,
                // The "when"s used before, ready to be used again, and the order the tiles
                // come up in when Settings asks for the popular ones first.
                suggestedTriggers = suggestedTriggers(past, now, clock.zone),
                kindOrder = if (current.popularTriggersFirst) triggerKindsByUse(past, now) else OFFERED_KINDS,
                savedPlaces = current.savedPlaces,
                recurrencePresets = recurrencePresetsByPopularity(current.recurrencePresets),
                asPreset = editedPreset != null || newPreset,
                initialAsPreset = editedPreset != null || newPreset,
                editingPreset = editedPreset,
                // Which shape this started from, so the screen says so, and the cue to open
                // the keyboard: everything but the words is already answered.
                fromPresetName = if (editedPreset == null) source?.name else null,
                presetText = editedPreset?.text.orEmpty(),
                initialPresetText = editedPreset?.text.orEmpty(),
                // Only when the preset left the words open: with default wording there is
                // nothing to type, and a keyboard would be covering a finished form.
                focusText = editedPreset == null && source != null && source.text.isBlank(),
            )
        }
    }

    fun setText(text: String) = _state.update { it.withText(text) }
    fun toggleTag(tag: String) = _state.update { it.toggleTag(tag) }
    fun addTag(raw: String) = _state.update { it.addTag(raw) }
    fun toggleAction(action: Action) = _state.update { it.toggleAction(action) }
    fun setRuleMatch(match: RuleMatch) = _state.update { it.setRuleMatch(match) }
    fun setRecurrence(recurrence: Recurrence) = _state.update { it.setRecurrence(recurrence) }

    fun openCalendar() = _state.update { it.openCalendar() }

    fun commitCalendar(repeat: Trigger.Repeat) = _state.update { it.commitCalendar(repeat) }

    fun addRecurrenceCondition() = _state.update { it.addRecurrenceCondition() }

    fun editRecurrenceCondition(index: Int) = _state.update { it.editRecurrenceCondition(index) }

    fun removeRecurrenceCondition(index: Int) = _state.update { it.removeRecurrenceCondition(index) }

    fun commitRecurrenceCondition(index: Int?, condition: Condition) =
        _state.update { it.commitRecurrenceCondition(index, condition) }

    /** Picking one off the row counts as a use, which is what keeps the row in a useful order. */
    fun pickRecurrencePreset(preset: RecurrencePreset) {
        // The preset says the span; the anchor already chosen on the card says which moment it
        // counts from, and picking a different span is no reason to forget it.
        _state.update { state -> state.setRecurrence(state.draft.recurrence.withSpanOf(preset.recurrence)) }
        viewModelScope.launch {
            val now = clock.instant()
            store.update { settings ->
                settings.copy(recurrencePresets = settings.recurrencePresets.map { if (it.id == preset.id) it.used(now) else it })
            }
        }
    }

    /** Keeping one under a name: a new one when [id] is null, otherwise that one rewritten. */
    fun saveRecurrencePreset(id: String?, name: String, recurrence: Recurrence) {
        viewModelScope.launch {
            val presetId = id ?: UUID.randomUUID().toString()
            store.update { settings ->
                val existing = settings.recurrencePresets.firstOrNull { it.id == presetId }
                val preset = RecurrencePreset(
                    id = presetId,
                    recurrence = recurrence,
                    name = name,
                    uses = existing?.uses ?: 0,
                    lastUsedAt = existing?.lastUsedAt,
                )
                settings.copy(recurrencePresets = settings.recurrencePresets.keeping(preset))
            }
            refreshRecurrencePresets()
        }
    }

    fun deleteRecurrencePreset(id: String) {
        viewModelScope.launch {
            store.update { settings -> settings.copy(recurrencePresets = settings.recurrencePresets.filterNot { it.id == id }) }
            refreshRecurrencePresets()
        }
    }

    private suspend fun refreshRecurrencePresets() {
        // From the store itself: the app-wide StateFlow catches up with a write on another
        // thread, and reading it straight after the write can still see the old value.
        val current = store.settings.first()
        _state.update { it.copy(recurrencePresets = recurrencePresetsByPopularity(current.recurrencePresets)) }
    }
    fun openKindPicker() = _state.update { it.openKindPicker() }
    fun pickKind(kind: TriggerKind) = _state.update { it.pickKind(kind) }
    fun editTrigger(index: Int) = _state.update { it.editTrigger(index) }
    fun removeTrigger(index: Int) = _state.update { it.removeTrigger(index) }
    fun commitTrigger(index: Int?, trigger: Trigger) = _state.update { it.commitTrigger(index, trigger) }
    fun addCondition(ruleIndex: Int) = _state.update { it.addCondition(ruleIndex) }
    fun editCondition(ruleIndex: Int, conditionIndex: Int) = _state.update { it.editCondition(ruleIndex, conditionIndex) }
    fun removeCondition(ruleIndex: Int, conditionIndex: Int) = _state.update { it.removeCondition(ruleIndex, conditionIndex) }
    fun commitCondition(ruleIndex: Int, conditionIndex: Int?, condition: Condition) =
        _state.update { it.commitCondition(ruleIndex, conditionIndex, condition) }
    fun closeSheet() = _state.update { it.closeSheet() }
    fun setPreviewing(previewing: Boolean) = _state.update { it.copy(previewing = previewing) }

    fun setAsPreset(asPreset: Boolean) = _state.update { it.setAsPreset(asPreset) }
    fun setPresetText(text: String) = _state.update { it.setPresetText(text) }

    fun curate(kind: CurateKind?) = _state.update { it.copy(curating = kind) }

    /**
     * Mending the offers. A tag or a phrase is not a record of its own — it is read off the
     * reminders that use it — so renaming one rewrites those reminders, and only those.
     * Dropping a phrase only stops it being offered: the reminders that used it are somebody's
     * history, not a list to tidy.
     */
    fun renameTag(from: String, to: String) = curateWith {
        repository.saveAll(renameTagIn(repository.allNow(), from, to))
        normalizeTag(to)?.let { renamed ->
            _state.update { state ->
                state.copy(draft = state.draft.copy(tags = normalizeTags(state.draft.tags.map { if (it.equals(from, true)) renamed else it })))
            }
        }
    }

    fun removeTag(tag: String) = curateWith {
        repository.saveAll(removeTagIn(repository.allNow(), tag))
        _state.update { state ->
            state.copy(draft = state.draft.copy(tags = state.draft.tags.filterNot { it.equals(tag, ignoreCase = true) }))
        }
    }

    fun renameText(from: String, to: String) = curateWith {
        repository.saveAll(renameTextIn(repository.allNow(), from, to))
        _state.update { state ->
            if (state.draft.text.trim().equals(from.trim(), ignoreCase = true)) state.withText(to.trim()) else state
        }
    }

    fun hideText(text: String) = curateWith {
        store.update { it.copy(hiddenTexts = withHiddenText(it.hiddenTexts, text)) }
    }

    /** Every mend ends the same way: the offers are read again so the screen tells the truth. */
    private fun curateWith(change: suspend () -> Unit) {
        viewModelScope.launch {
            change()
            val past = repository.allNow()
            val now = clock.instant()
            val hidden = store.settings.first().hiddenTexts
            _state.update { state ->
                state.copy(
                    existingTags = suggestedTags(past, now),
                    suggestedTexts = visibleTexts(suggestedTexts(past, now, limit = 8, exclude = state.draft.text), hidden),
                    allTexts = visibleTexts(suggestedTexts(past, now, limit = 100), hidden),
                )
            }
        }
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) {
            _state.update { it.copy(showErrors = true) }
            return
        }
        if (current.asPreset) {
            savePreset(current)
            return
        }
        viewModelScope.launch {
            val now = clock.instant()
            // The row as it is NOW, not as it was when the form opened: it may have rung and
            // been dealt with from the notification in between, and a save built on the
            // snapshot would hand back the status and the anchor from before that.
            val before = existing?.let { repository.get(it.id) ?: it }
            val reminder = current.draft.toReminder(
                id = before?.id ?: UUID.randomUUID().toString(),
                createdAt = before?.createdAt ?: now,
                now = now,
                // Editing something already done brings it back; otherwise the status is not
                // the editor's business.
                status = if (before == null || before.status == Status.DONE) Status.ACTIVE else before.status,
                // The recurrence's anchor and the last ring survive an edit; the snooze and
                // the armed moment do not. See Draft.toReminder.
                lastDealtAt = before?.lastDealtAt,
                lastFiredAt = before?.lastFiredAt,
            )
            repository.save(reminder)
            rearm()
            events.send(EditorEvent.Saved)
        }
    }

    /**
     * A preset lives in the settings, not the database: it is a shape, not something waiting.
     * A reminder being turned into one is left where it is — the toggle says what this screen
     * is writing, not what should happen to whatever it was opened on.
     */
    private fun savePreset(current: EditorUiState) {
        viewModelScope.launch {
            val now = clock.instant()
            val id = current.editingPreset?.id ?: UUID.randomUUID().toString()
            store.update { settings ->
                // The colour a new one gets is worked out from the others, so that is the list
                // toPreset is shown; where the preset itself goes is [keeping]'s business.
                val others = settings.presets.filterNot { it.id == id }
                val preset = current.toPreset(id, now, current.editingPreset, others)
                settings.copy(presets = settings.presets.keeping(preset))
            }
            events.send(EditorEvent.Saved)
        }
    }

    fun delete() {
        val preset = _state.value.editingPreset
        if (preset != null) {
            viewModelScope.launch {
                store.update { settings -> settings.copy(presets = settings.presets.filterNot { it.id == preset.id }) }
                events.send(EditorEvent.Close)
            }
            return
        }
        val target = existing ?: return
        viewModelScope.launch {
            repository.delete(target.id)
            events.send(EditorEvent.Deleted(target))
        }
    }

    /** Back: straight out when nothing changed, a question otherwise. */
    fun requestClose() {
        if (_state.value.dirty) _state.update { it.copy(confirmingDiscard = true) } else events.trySend(EditorEvent.Close)
    }

    fun keepEditing() = _state.update { it.copy(confirmingDiscard = false) }

    fun discard() {
        _state.update { it.copy(confirmingDiscard = false) }
        events.trySend(EditorEvent.Close)
    }

    class Factory(
        private val app: RwilcoApplication,
        private val reminderId: String?,
        private val fromPresetId: String? = null,
        private val editPresetId: String? = null,
        private val newPreset: Boolean = false,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            EditorViewModel(
                reminderId,
                fromPresetId,
                editPresetId,
                newPreset,
                app.repository,
                app.settingsStore,
                app.settings,
                { app.scheduler.rearmAll() },
                app.clock,
            ) as T
    }
}
