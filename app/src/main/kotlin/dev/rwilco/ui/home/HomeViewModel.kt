package dev.rwilco.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rwilco.RwilcoApplication
import dev.rwilco.data.ReminderRepository
import dev.rwilco.model.AppSettings
import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
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
}

class HomeViewModel(
    private val repository: ReminderRepository,
    settings: Flow<AppSettings?>,
    val clock: Clock,
) : ViewModel() {

    private val selectedTag = MutableStateFlow<String?>(null)
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
        buildHomeState(reminders, current.defaultTime, now, clock.zone, tag)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun refresh() {
        refreshTick.value = clock.instant()
        events.trySend(HomeEvent.Refreshed)
    }

    /** Tapping the selected tag again clears the filter. */
    fun selectTag(tag: String?) {
        selectedTag.value = if (tag == null || selectedTag.value.equals(tag, ignoreCase = true)) null else tag
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
            HomeViewModel(app.repository, app.settings, app.clock) as T
    }
}
