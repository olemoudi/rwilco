package dev.rwilco.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rwilco.RwilcoApplication
import dev.rwilco.data.ReminderRepository
import dev.rwilco.model.Action
import dev.rwilco.model.AppSettings
import dev.rwilco.model.Reminder
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.Status
import dev.rwilco.model.Condition
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerKind
import dev.rwilco.model.suggestedTags
import dev.rwilco.model.suggestedTexts
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
    private val repository: ReminderRepository,
    settings: Flow<AppSettings?>,
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
            val draft = loaded?.toDraft() ?: Draft()
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
                suggestedTexts = suggestedTexts(past, now, limit = 8, exclude = draft.text),
                defaultTime = current.defaultTime,
                defaultKind = current.defaultTriggerKind,
            )
        }
    }

    fun setText(text: String) = _state.update { it.withText(text) }
    fun toggleTag(tag: String) = _state.update { it.toggleTag(tag) }
    fun addTag(raw: String) = _state.update { it.addTag(raw) }
    fun toggleAction(action: Action) = _state.update { it.toggleAction(action) }
    fun setRuleMatch(match: RuleMatch) = _state.update { it.setRuleMatch(match) }
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

    fun save() {
        val current = _state.value
        if (!current.canSave) {
            _state.update { it.copy(showErrors = true) }
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

    fun delete() {
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

    class Factory(private val app: RwilcoApplication, private val reminderId: String?) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            EditorViewModel(reminderId, app.repository, app.settings, app.clock) as T
    }
}
