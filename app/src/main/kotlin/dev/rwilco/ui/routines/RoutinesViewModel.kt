package dev.rwilco.ui.routines

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rwilco.RwilcoApplication
import dev.rwilco.alarm.ReminderFiring
import dev.rwilco.model.FiringEvent
import dev.rwilco.model.FiringKind
import dev.rwilco.data.ReminderRepository
import dev.rwilco.data.SettingsStore
import dev.rwilco.model.AppSettings
import dev.rwilco.model.Reminder
import dev.rwilco.model.snoozeTerms
import dev.rwilco.model.snoozeBoard
import dev.rwilco.model.SnoozeTerms
import dev.rwilco.model.SnoozeBoard
import dev.rwilco.model.contactScheduleOf
import dev.rwilco.model.RoutineFilter
import dev.rwilco.model.SnoozeOffer
import dev.rwilco.model.Status
import dev.rwilco.model.dayShape
import dev.rwilco.model.nextFire
import dev.rwilco.model.moment
import dev.rwilco.ui.home.UNDO_DELETE_MS
import dev.rwilco.ui.home.shownOpen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalTime

/** What the routines screen says in a snackbar, each with a way back. */
sealed interface RoutinesEvent {
    /** «¿He hecho «X»? Sí»: the count starts again; [comesBackAt] is when it will next ask. */
    data class Done(val reminder: Reminder, val comesBackAt: Instant?) : RoutinesEvent

    /** A routine left the list; [history] is what the cascade took with it, for the undo. */
    data class Deleted(val reminder: Reminder, val history: List<FiringEvent>) : RoutinesEvent

    data class Paused(val reminder: Reminder, val paused: Boolean) : RoutinesEvent

    /**
     * Put off until [until]; null with [cancelled] is the snooze being taken back. [at] is the
     * moment just before it was given, so the undo can take its history line back with the row.
     */
    data class Snoozed(val reminder: Reminder, val until: Instant?, val cancelled: Boolean = false, val at: Instant? = null) : RoutinesEvent
}

/**
 * The routines: the list, the filter, and the same answers Home gives a card — through the same
 * doors. "Sí, lo he hecho" is [ReminderFiring.dismiss], which under a routine is the reset
 * (`Routines.kt`); a delete keeps its minute of undo exactly as Home's does.
 */
class RoutinesViewModel(
    private val repository: ReminderRepository,
    private val firing: ReminderFiring,
    private val store: SettingsStore,
    settings: Flow<AppSettings?>,
    val clock: Clock,
) : ViewModel() {

    private val filter = MutableStateFlow<RoutineFilter>(RoutineFilter.All)
    private val query = MutableStateFlow("")
    private val startTick = MutableStateFlow(clock.instant())
    private val events = Channel<RoutinesEvent>(Channel.BUFFERED)
    val eventFlow: Flow<RoutinesEvent> = events.receiveAsFlow()

    /** A minute pulse, alive only while the screen is: a "Sí" turns into a "No" on its own. */
    private val minutePulse = flow {
        while (true) {
            delay(60_000L - Math.floorMod(clock.millis(), 60_000L))
            emit(clock.instant())
        }
    }

    val state: StateFlow<RoutinesUiState> = combine(
        repository.open,
        settings.filterNotNull(),
        filter,
        query,
        merge(startTick, minutePulse),
    ) { reminders, current, chosen, words, _ ->
        buildRoutinesState(reminders, chosen, clock.instant(), clock.zone, current.dayStart, words, current::contactScheduleOf)
    }
        .flowOn(Dispatchers.Default)
        .catch { failure ->
            Log.e(TAG, "could not build the routines", failure)
            emit(RoutinesUiState(loaded = true, failed = true))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutinesUiState())

    /** How long "un rato" is on the menu's snooze offers: the person's own length. */
    val snoozeCustomMinutes: StateFlow<Int> = settings.filterNotNull().map { it.snoozeCustomMinutes }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings().snoozeCustomMinutes)

    /** The same board a card's menu and the alert read: which offers show, and which wait behind the door. */
    val snoozeBoard: StateFlow<SnoozeBoard> = settings.filterNotNull().map { snoozeBoard(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), snoozeBoard(AppSettings()))
    val snoozeTerms: StateFlow<SnoozeTerms> = settings.filterNotNull().map { it.snoozeTerms }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings().snoozeTerms)

    /** The hour a date with no time of its own means, which is what the calendar opens on. */
    val defaultTime: StateFlow<LocalTime> = settings.filterNotNull().map { it.defaultTime }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings().defaultTime)

    /** The routines down to their words and their track; see [AppSettings.compactRoutines]. */
    val compact: StateFlow<Boolean> = settings.filterNotNull().map { it.compactRoutines }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings().compactRoutines)

    /**
     * The rows showing the *opposite* of the mode: open ones while the list is folded, folded
     * ones while it is not. The exceptions to the mode rather than a state per row, exactly as
     * Home keeps them ([dev.rwilco.ui.home.HomeViewModel.flippedCards], [shownOpen]) — screen
     * state, because a routine opened out to read it is a thing somebody is doing now.
     */
    private val flipped = MutableStateFlow(emptySet<String>())
    val flippedRows: StateFlow<Set<String>> = flipped

    fun setCompact(compact: Boolean) {
        // A toggle that left yesterday's exceptions behind would be a mode that does not quite
        // do what it says.
        flipped.value = emptySet()
        viewModelScope.launch { store.update { it.copy(compactRoutines = compact) } }
    }

    /** One row in or out of step with the mode: the tap on a card, both ways. */
    fun flipRow(id: String) {
        flipped.value = if (id in flipped.value) flipped.value - id else flipped.value + id
    }

    /** One row open whatever the mode: what arriving from Home's overdue line asks for. */
    fun expandRow(id: String) {
        flipped.value = shownOpen(flipped.value, id, compact.value)
    }

    /** What the search field types; blank is not searching. */
    fun search(words: String) { query.value = words }

    /** Tapping the selected chip again clears the filter. */
    fun selectFilter(chosen: RoutineFilter) {
        filter.value = if (chosen == filter.value) RoutineFilter.All else chosen
    }

    /**
     * The swipe that says "sí, lo he hecho": the same door as every other "hecho" in the app,
     * which under a routine moves the count to now and re-arms the deadline.
     */
    fun markDone(id: String) {
        viewModelScope.launch {
            val before = repository.get(id) ?: return@launch
            firing.dismiss(id)
            events.send(RoutinesEvent.Done(before, comesBack(id)))
        }
    }

    /**
     * The routine a "lo hice otro día" is being dated for — the row itself and the hour the day
     * starts at, so the sheet can say why a day will not do before the button does
     * ([doneEarlierRefusal]). ViewModel state: it has to outlive a rotation with the sheet up.
     */
    data class Dating(val reminder: Reminder, val dayStart: LocalTime)

    private val _dating = MutableStateFlow<Dating?>(null)
    val dating: StateFlow<Dating?> = _dating

    fun askWhenDone(id: String) {
        viewModelScope.launch {
            val reminder = repository.get(id) ?: return@launch
            _dating.value = Dating(reminder, store.settings.first().dayStart)
        }
    }

    fun cancelDating() {
        _dating.value = null
    }

    /**
     * "Sí — pero fue el lunes": [ReminderFiring.doneEarlier], which asks the refusal again under
     * its own lock. Said and undone exactly as a "hecho" is: the word is the same, only the day
     * is not.
     */
    fun doneEarlier(at: Instant) {
        val asked = _dating.value ?: return
        _dating.value = null
        viewModelScope.launch {
            val before = repository.get(asked.reminder.id) ?: return@launch
            if (firing.doneEarlier(before.id, at)) events.send(RoutinesEvent.Done(before, comesBack(before.id)))
        }
    }

    /** When [id] next rings, read off the row the dismissal left. */
    private suspend fun comesBack(id: String): Instant? {
        val after = repository.get(id)?.takeIf { it.status == Status.ACTIVE } ?: return null
        val current = store.settings.first()
        return nextFire(after, clock.instant(), clock.zone, current.defaultTime, current.dayStart, current.dayShape)?.moment
    }

    fun delete(id: String) {
        viewModelScope.launch {
            val reminder = repository.get(id) ?: return@launch
            val history = repository.historyAsWritten(id)
            repository.delete(id)
            val event = RoutinesEvent.Deleted(reminder, history)
            keepUndoable(event)
            events.send(event)
        }
    }

    /** The last delete, undoable for a minute whatever the snackbar does — as on Home. */
    val pendingDelete: StateFlow<RoutinesEvent.Deleted?> get() = _pendingDelete
    private val _pendingDelete = MutableStateFlow<RoutinesEvent.Deleted?>(null)
    private var pendingDeleteTimer: Job? = null

    private fun keepUndoable(removed: RoutinesEvent.Deleted) {
        pendingDeleteTimer?.cancel()
        _pendingDelete.value = removed
        pendingDeleteTimer = viewModelScope.launch {
            delay(UNDO_DELETE_MS)
            _pendingDelete.compareAndSet(removed, null)
        }
    }

    /**
     * The undo of a "hecho", dated or not: the row as it was — a "hecho" moved the count, and only
     * the whole row puts it back — and the line it wrote taken back, which the statistics would
     * otherwise count as a hecho nobody gave.
     */
    fun undoDone(event: RoutinesEvent.Done) {
        viewModelScope.launch {
            repository.restore(event.reminder)
            repository.forgetNewest(event.reminder.id, listOf(FiringKind.DEALT, FiringKind.SKIPPED), event.reminder.lastDealtAt)
        }
    }

    /** The snooze the row had before, and the line this one wrote taken back. */
    fun undoSnooze(event: RoutinesEvent.Snoozed) {
        viewModelScope.launch {
            repository.restore(event.reminder)
            event.at?.let { repository.forgetNewest(event.reminder.id, listOf(FiringKind.SNOOZED), it.minusMillis(1)) }
        }
    }

    /** The row exactly as it was, and after a delete its history with it. */
    fun undo(reminder: Reminder, history: List<FiringEvent> = emptyList()) {
        _pendingDelete.value?.let { pending ->
            if (pending.reminder.id == reminder.id && _pendingDelete.compareAndSet(pending, null)) pendingDeleteTimer?.cancel()
        }
        viewModelScope.launch { repository.restore(reminder, history) }
    }

    fun togglePause(id: String, paused: Boolean) {
        viewModelScope.launch {
            val reminder = repository.get(id) ?: return@launch
            repository.setStatus(id, if (paused) Status.ACTIVE else Status.PAUSED)
            events.send(RoutinesEvent.Paused(reminder, paused = !paused))
        }
    }

    fun undoPause(event: RoutinesEvent.Paused) {
        viewModelScope.launch { repository.setStatus(event.reminder.id, event.reminder.status) }
    }

    /** "Posponer" from a held row: the same door the notification and the alert screen use. */
    fun snooze(id: String, snooze: SnoozeOffer) {
        viewModelScope.launch {
            val before = repository.get(id) ?: return@launch
            val at = clock.instant()
            firing.snooze(id, snooze)
            events.send(RoutinesEvent.Snoozed(before, repository.get(id)?.snoozedUntil, at = at))
        }
    }

    /** "A una fecha concreta": the same door, at a moment picked off a calendar. */
    fun snoozeUntil(id: String, until: Instant) {
        viewModelScope.launch {
            val before = repository.get(id) ?: return@launch
            val at = clock.instant()
            firing.snoozeUntil(id, until)
            events.send(RoutinesEvent.Snoozed(before, repository.get(id)?.snoozedUntil, at = at))
        }
    }

    fun cancelSnooze(id: String) {
        viewModelScope.launch {
            val before = repository.get(id) ?: return@launch
            firing.unsnooze(id)
            events.send(RoutinesEvent.Snoozed(before, null, cancelled = true))
        }
    }

    class Factory(private val app: RwilcoApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RoutinesViewModel(app.repository, app.firing, app.settingsStore, app.settings, app.clock) as T
    }
}

private const val TAG = "RoutinesViewModel"
