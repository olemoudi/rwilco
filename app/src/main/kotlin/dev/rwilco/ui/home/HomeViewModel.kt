package dev.rwilco.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rwilco.RwilcoApplication
import dev.rwilco.alarm.ReminderFiring
import dev.rwilco.data.ReminderRepository
import dev.rwilco.data.SettingsStore
import dev.rwilco.model.Action
import dev.rwilco.model.DayShape
import dev.rwilco.model.GeofenceIds
import dev.rwilco.model.dayShape
import dev.rwilco.model.AppSettings
import dev.rwilco.model.Preset
import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import dev.rwilco.model.PlaceWatchState
import dev.rwilco.model.TagFilter
import dev.rwilco.model.Trigger
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
import dev.rwilco.model.Snooze
import dev.rwilco.model.DEFAULT_SNOOZE_MINUTES
import java.time.Instant

/** What Home reports back that is not state: things to say in a snackbar. */
sealed interface HomeEvent {
    /** A reminder left the list; the snackbar offers to bring it back. */
    data class Removed(val kind: Kind, val reminder: Reminder) : HomeEvent {
        enum class Kind { DONE, DELETED }
    }


    /** A preset was turned into a reminder in one tap; the snackbar offers to undo it. */
    data class Created(val reminder: Reminder, val preset: Preset) : HomeEvent

    /**
     * A reminder was paused or resumed ([paused] is where it ended up); [reminder] is the row
     * as it was, so the undo puts the status it had back.
     */
    data class Paused(val reminder: Reminder, val paused: Boolean) : HomeEvent

    /**
     * A reminder was put off from Home until [until], or ([until] null) its snooze was taken
     * back; [reminder] is the row as it was, so the undo restores the snooze it had.
     */
    data class Snoozed(val reminder: Reminder, val until: Instant?) : HomeEvent

    /**
     * A preset could not be written blind — a moment it carries has already passed — so the
     * form is opened on it instead of quietly making something overdue.
     */
    data class NeedsEditor(val presetId: String) : HomeEvent
}

class HomeViewModel(
    private val repository: ReminderRepository,
    private val store: SettingsStore,
    /** "Hecho" is one answer with one meaning, wherever it is given: see [markDone]. */
    private val firing: ReminderFiring,
    settings: Flow<AppSettings?>,
    /** Which circles the phone is inside, as the place watch last saw it. */
    private val placeWatch: Flow<PlaceWatchState>,
    val clock: Clock,
) : ViewModel() {

    /** The shapes kept under a name, the ones actually used at the top. */
    val presets: StateFlow<List<Preset>> = settings
        .filterNotNull()
        .map { presetsByPopularity(it.presets) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** How long the custom snooze is, for the menu's offers to read the way the alert's do. */
    val snoozeCustomMinutes: StateFlow<Int> = settings
        .filterNotNull()
        .map { it.snoozeCustomMinutes }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_SNOOZE_MINUTES)

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
    fun createFromPreset(
        preset: Preset,
        words: String? = null,
        defaultTime: java.time.LocalTime,
        shape: DayShape,
        /** What it should do when it rings, when the dialog was asked; null keeps the shape's. */
        actions: Set<Action>? = null,
    ) {
        viewModelScope.launch {
            val now = clock.instant()
            if (warnings(preset.rules, now, clock.zone, defaultTime, shape = shape).any { it is ValidationWarning.InPast }) {
                events.send(HomeEvent.NeedsEditor(preset.id))
                return@launch
            }
            val reminder = preset.toReminder(
                id = UUID.randomUUID().toString(),
                now = now,
                words = words ?: preset.text,
                actions = actions ?: preset.actions,
                zone = clock.zone,
                shape = shape,
            )
            repository.save(reminder)
            store.update { settings ->
                settings.copy(presets = settings.presets.map { if (it.id == preset.id) it.used(now) else it })
            }
            events.send(HomeEvent.Created(reminder, preset))
        }
    }

    /** Undoing a one-tap creation: the reminder goes, and so does the use it counted. */
    fun undoCreated(reminder: Reminder, preset: Preset) {
        viewModelScope.launch {
            repository.delete(reminder.id)
            store.update { settings ->
                settings.copy(presets = settings.presets.map { if (it.id == preset.id) it.copy(uses = preset.uses, lastUsedAt = preset.lastUsedAt) else it })
            }
        }
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

    private val selectedTag = MutableStateFlow<TagFilter?>(null)
    private val searching = MutableStateFlow(false)
    private val query = MutableStateFlow("")
    /**
     * The seed the minute pulse has not produced yet.
     *
     * It was the manual "actualizar" tick until that button went; what it still does is give
     * [combine] a value to work with before [minutePulse]'s first delay is up, which is the
     * difference between Home drawing at once and Home drawing in a minute's time.
     */
    private val startTick = MutableStateFlow(clock.instant())
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
        // startTick is a StateFlow, so the merge has a value from the first collection on.
        merge(startTick, minutePulse),
        // Which circles the phone is in, as the watch last saw it: the rule marks read it, and
        // it changes on its own, which is what makes a mark change back.
        placeWatch,
    ) { reminders, current, tag, _, watch ->
        // The ticks only say WHEN to rebuild; the moment is read fresh. startTick is a
        // StateFlow, and on every resubscription — the app coming back after five seconds
        // away — it replays its last value, the instant the ViewModel was made, possibly hours
        // ago: Home was built for a morning that had passed until the next minute tick.
        buildHomeState(reminders, current.defaultTime, clock.instant(), clock.zone, tag, current.dayStart, current.dayShape) { id, index ->
            insideOf(reminders, watch, id, index)
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /**
     * Whether the phone is inside the circle of one rule, from the watch's own memory. Keyed by
     * the id that carries the circle itself, so an edited place is a different question rather
     * than the old answer (see GeofenceIds).
     */
    private fun insideOf(reminders: List<Reminder>, watch: PlaceWatchState, id: String, index: Int): Boolean? {
        val place = reminders.firstOrNull { it.id == id }?.rules?.getOrNull(index)?.trigger as? Trigger.Location ?: return null
        return watch.inside[GeofenceIds.encode(id, index, place)]
    }

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


    /** Tapping the selected chip again clears the filter. */
    fun selectTag(tag: TagFilter?) {
        selectedTag.value = if (tag == null || tag == selectedTag.value) null else tag
    }

    /** From a search result: the tag is being asked for, not toggled, and the search is done. */
    fun filterByTag(tag: String) {
        selectedTag.value = TagFilter.Named(tag)
        setSearching(false)
    }

    /**
     * The swipe that says "hecho", which is the same answer as the one on the notification and
     * on the alert screen — so it goes through the same door.
     *
     * It used to file the reminder as DONE outright, which is right for most of them and wrong
     * for every one that was asked to come back: a reminder repeating "cada 6 h" was finished by
     * the swipe instead of starting its next round, and the moment its recurrence counts from
     * was never written down. [ReminderFiring.dismiss] is where that decision lives
     * (`statusAfterDismissal`), and it also stamps `lastDealtAt`, clears a half-finished ALL
     * round, takes down the notification and re-arms.
     */
    fun markDone(id: String) = removeAs(id, HomeEvent.Removed.Kind.DONE)

    fun delete(id: String) = removeAs(id, HomeEvent.Removed.Kind.DELETED)

    private fun removeAs(id: String, kind: HomeEvent.Removed.Kind) {
        viewModelScope.launch {
            val reminder = repository.get(id) ?: return@launch
            when (kind) {
                HomeEvent.Removed.Kind.DONE -> firing.dismiss(id)
                HomeEvent.Removed.Kind.DELETED -> repository.delete(id)
            }
            events.send(HomeEvent.Removed(kind, reminder))
        }
    }

    /**
     * The reminder exactly as it was: dealing with one now moves more than its status — the
     * anchor its recurrence counts from, a half-finished round, a snooze — and putting only the
     * status back would leave the clock wound forward.
     */
    fun undo(removed: HomeEvent.Removed) {
        viewModelScope.launch { repository.restore(removed.reminder) }
    }

    /**
     * Said in a snackbar with an undo, like "hecho" and "borrar": a paused card goes grey and
     * slides to the bottom of the list, which from the middle of a scroll is a card that vanished.
     */
    fun togglePause(id: String, paused: Boolean) {
        viewModelScope.launch {
            val reminder = repository.get(id) ?: return@launch
            repository.setStatus(id, if (paused) Status.ACTIVE else Status.PAUSED)
            events.send(HomeEvent.Paused(reminder, paused = !paused))
        }
    }

    fun undoPause(event: HomeEvent.Paused) {
        viewModelScope.launch { repository.setStatus(event.reminder.id, event.reminder.status) }
    }

    /**
     * "Posponer" from a held card: the same door the notification and the alert screen use
     * ([ReminderFiring.snooze]), so it takes the notification down and re-arms the same way.
     * Offered only where the card says it is an answer (`ReminderCardUi.snoozeOffered`).
     */
    fun snooze(id: String, snooze: Snooze) {
        viewModelScope.launch {
            val reminder = repository.get(id) ?: return@launch
            firing.snooze(id, snooze)
            val until = repository.get(id)?.snoozedUntil
            events.send(HomeEvent.Snoozed(reminder, until))
        }
    }

    fun cancelSnooze(id: String) {
        viewModelScope.launch {
            val reminder = repository.get(id) ?: return@launch
            firing.unsnooze(id)
            events.send(HomeEvent.Snoozed(reminder, until = null))
        }
    }

    /** The snooze the row had before — none, usually — and the alarm follows the row. */
    fun undoSnooze(event: HomeEvent.Snoozed) {
        viewModelScope.launch { repository.snooze(event.reminder.id, event.reminder.snoozedUntil) }
    }

    class Factory(private val app: RwilcoApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(app.repository, app.settingsStore, app.firing, app.settings, app.placeWatch.state, app.clock) as T
    }
}
