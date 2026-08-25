package dev.rwilco.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rwilco.RwilcoApplication
import dev.rwilco.data.ReminderRepository
import dev.rwilco.data.SettingsStore
import dev.rwilco.model.Action
import dev.rwilco.model.AppSettings
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
                editedPreset != null -> Draft(text = editedPreset.name, tags = editedPreset.tags, rules = editedPreset.rules, ruleMatch = editedPreset.ruleMatch, actions = editedPreset.actions, repeats = editedPreset.repeats)
                source != null -> Draft(text = source.text, tags = source.tags, rules = source.rules, ruleMatch = source.ruleMatch, actions = source.actions, repeats = source.repeats)
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
                defaultKind = current.defaultTriggerKind,
                savedPlaces = current.savedPlaces,
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
    fun setRepeats(repeats: Boolean) = _state.update { it.setRepeats(repeats) }
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
            val hidden = settings.filterNotNull().first().hiddenTexts
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
            val before = existing
            val reminder = current.draft.toReminder(
                id = before?.id ?: UUID.randomUUID().toString(),
                createdAt = before?.createdAt ?: now,
                now = now,
                // Editing something already done brings it back; otherwise the status is not
                // the editor's business.
                status = if (before == null || before.status == Status.DONE) Status.ACTIVE else before.status,
            )
            repository.save(reminder)
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
                val others = settings.presets.filterNot { it.id == id }
                val preset = current.toPreset(id, now, current.editingPreset, others)
                settings.copy(presets = others + preset)
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
            EditorViewModel(reminderId, fromPresetId, editPresetId, newPreset, app.repository, app.settingsStore, app.settings, app.clock) as T
    }
}
