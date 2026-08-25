package dev.rwilco.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rwilco.RwilcoApplication
import dev.rwilco.data.ReminderRepository
import dev.rwilco.data.SettingsStore
import dev.rwilco.model.AppSettings
import dev.rwilco.model.Preset
import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import dev.rwilco.model.ValidationWarning
import dev.rwilco.model.presetsByPopularity
import dev.rwilco.model.toReminder
import dev.rwilco.model.used
import dev.rwilco.model.warnings
import java.util.UUID
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock

/** What Home reports back that is not state: things to say in a snackbar. */
sealed interface HomeEvent {
    /** A reminder left the list; the snackbar offers to bring it back. */
    data class Removed(val kind: Kind, val reminder: Reminder) : HomeEvent {
        enum class Kind { DONE, DELETED }
    }

    data object Refreshed : HomeEvent

    /** A preset was turned into a reminder in one tap; the snackbar offers to undo it. */
    data class Created(val reminder: Reminder) : HomeEvent

    /**
     * A preset could not be written blind — a moment it carries has already passed — so the
     * form is opened on it instead of quietly making something overdue.
     */
    data class NeedsEditor(val presetId: String) : HomeEvent
}

class HomeViewModel(
    private val repository: ReminderRepository,
    private val store: SettingsStore,
    settings: Flow<AppSettings?>,
    val clock: Clock,
) : ViewModel() {

    /** The shapes kept under a name, the ones actually used at the top. */
    val presets: StateFlow<List<Preset>> = settings
        .filterNotNull()
        .map { presetsByPopularity(it.presets) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The ones with a button on Home, in the same order: most reached for, first. */
    val pinnedPresets: StateFlow<List<Preset>> = presets
        .map { list -> list.filter { it.pinned } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun togglePin(preset: Preset) {
        viewModelScope.launch {
            store.update { settings ->
                settings.copy(presets = settings.presets.map { if (it.id == preset.id) it.copy(pinned = !it.pinned) else it })
            }
        }
    }

    /**
     * One tap on a preset button. [words] is what the person typed when the preset had none of
     * its own; everything else the preset already answered, so the reminder is written there
     * and then — unless one of its moments has already passed, which needs a person.
     */
    fun createFromPreset(preset: Preset, words: String? = null, defaultTime: java.time.LocalTime) {
        viewModelScope.launch {
            val now = clock.instant()
            if (warnings(preset.rules, now, clock.zone, defaultTime).any { it is ValidationWarning.InPast }) {
                events.send(HomeEvent.NeedsEditor(preset.id))
                return@launch
            }
            val reminder = preset.toReminder(id = UUID.randomUUID().toString(), now = now, words = words ?: preset.text)
            repository.save(reminder)
            store.update { settings ->
                settings.copy(presets = settings.presets.map { if (it.id == preset.id) it.used(now) else it })
            }
            events.send(HomeEvent.Created(reminder))
        }
    }

    /** Undoing a one-tap creation: the reminder goes, and so does the use it counted. */
    fun undoCreated(reminder: Reminder) {
        viewModelScope.launch { repository.delete(reminder.id) }
    }

    /**
     * Reaching for a preset counts as using it, whether or not the reminder is saved in the
     * end: what the list is ordering is what somebody reaches for, and an abandoned draft is
     * still a reach.
     */
    fun usePreset(preset: Preset) {
        viewModelScope.launch {
            val now = clock.instant()
            store.update { settings ->
                settings.copy(presets = settings.presets.map { if (it.id == preset.id) it.used(now) else it })
            }
        }
    }

    private val selectedTag = MutableStateFlow<String?>(null)
    private val searching = MutableStateFlow(false)
    private val query = MutableStateFlow("")
    private val refreshTick = MutableStateFlow(clock.instant())
    private val events = Channel<HomeEvent>(Channel.BUFFERED)
    val eventFlow: Flow<HomeEvent> = events.receiveAsFlow()

    /** A minute pulse, alive only while the state is collected: sections move at midnight. */
    private val minutePulse = flow {
        while (true) {
            delay(60_000L - Math.floorMod(clock.millis(), 60_000L))
            emit(clock.instant())
        }
    }

    val state: StateFlow<HomeUiState> = combine(
        repository.open,
        settings.filterNotNull(),
        selectedTag,
        // refreshTick is a StateFlow, so the merge has a value from the first collection on.
        merge(refreshTick, minutePulse),
    ) { reminders, current, tag, now ->
        buildHomeState(reminders, current.defaultTime, now, clock.zone, tag, current.dayStart)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /**
     * The magnifier's own state, kept apart from [state]: a keystroke must not send Home
     * through grouping and next-fire again, and what search shows does not depend on the clock.
     */
    val search: StateFlow<SearchUiState> = combine(repository.open, searching, query) { reminders, open, text ->
        buildSearchState(reminders, text, open)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    /** Opening clears whatever was typed last time: a magnifier always opens empty. */
    fun setSearching(open: Boolean) {
        query.value = ""
        searching.value = open
    }

    fun setQuery(text: String) {
        query.value = text
    }

    fun refresh() {
        refreshTick.value = clock.instant()
        events.trySend(HomeEvent.Refreshed)
    }

    /** Tapping the selected tag again clears the filter. */
    fun selectTag(tag: String?) {
        selectedTag.value = if (tag == null || selectedTag.value.equals(tag, ignoreCase = true)) null else tag
    }

    /** From a search result: the tag is being asked for, not toggled, and the search is done. */
    fun filterByTag(tag: String) {
        selectedTag.value = tag
        setSearching(false)
    }

    fun markDone(id: String) = removeAs(id, HomeEvent.Removed.Kind.DONE)

    fun delete(id: String) = removeAs(id, HomeEvent.Removed.Kind.DELETED)

    private fun removeAs(id: String, kind: HomeEvent.Removed.Kind) {
        viewModelScope.launch {
            val reminder = repository.get(id) ?: return@launch
            when (kind) {
                HomeEvent.Removed.Kind.DONE -> repository.setStatus(id, Status.DONE)
                HomeEvent.Removed.Kind.DELETED -> repository.delete(id)
            }
            events.send(HomeEvent.Removed(kind, reminder))
        }
    }

    fun undo(removed: HomeEvent.Removed) {
        viewModelScope.launch {
            when (removed.kind) {
                HomeEvent.Removed.Kind.DONE -> repository.setStatus(removed.reminder.id, removed.reminder.status)
                HomeEvent.Removed.Kind.DELETED -> repository.restore(removed.reminder)
            }
        }
    }

    fun togglePause(id: String, paused: Boolean) {
        viewModelScope.launch {
            repository.setStatus(id, if (paused) Status.ACTIVE else Status.PAUSED)
        }
    }

    class Factory(private val app: RwilcoApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(app.repository, app.settingsStore, app.settings, app.clock) as T
    }
}
