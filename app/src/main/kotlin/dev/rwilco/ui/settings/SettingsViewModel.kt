package dev.rwilco.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rwilco.RwilcoApplication
import dev.rwilco.data.ReminderRepository
import dev.rwilco.data.SettingsStore
import dev.rwilco.geo.PlaceLogStore
import dev.rwilco.model.Action
import dev.rwilco.model.AppSettings
import dev.rwilco.model.SoundLimits
import dev.rwilco.model.AlertSound
import dev.rwilco.model.PlaceWatchState
import dev.rwilco.model.SOUND_ACTIONS
import dev.rwilco.model.SavedPlace
import dev.rwilco.model.PlaceWatchPolicy
import dev.rwilco.model.ThemeMode
import dev.rwilco.model.Trigger
import dev.rwilco.model.VibrationPattern
import dev.rwilco.model.WatchLog
import dev.rwilco.model.pollsSince
import dev.rwilco.model.TriggerKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalTime

class SettingsViewModel(
    private val store: SettingsStore,
    val settings: StateFlow<AppSettings?>,
    repository: ReminderRepository,
    placeWatch: Flow<PlaceWatchState>,
    private val placeLog: PlaceLogStore,
    private val clock: Clock,
) : ViewModel() {

    /** Only ask for "allow all the time" when something actually waits on a place. */
    val hasPlaceReminders: StateFlow<Boolean> = repository.open
        .map { reminders -> reminders.any { reminder -> reminder.rules.any { it.trigger is Trigger.Location } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** What the place watch last saw and when it looks next, for the Location card. */
    val placeWatch: StateFlow<PlaceWatchState?> = placeWatch
        .map<PlaceWatchState, PlaceWatchState?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Whether the two insistent-sound numbers are worth showing at all: something has to be
     * asking for that sound, either a reminder or what a blank one starts with.
     */
    val insistentInUse: StateFlow<Boolean> = combine(repository.open, settings) { reminders, current ->
        Action.SOUND_UNTIL_ANSWERED in current?.defaultActions.orEmpty() ||
            reminders.any { Action.SOUND_UNTIL_ANSWERED in it.actions }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Every look the place watch took, newest first: the log behind the button. */
    val watchLog: StateFlow<WatchLog> = placeLog.log
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchLog())

    /**
     * Looks that actually spent radio in the last hour — the same count the notice is about, so
     * the screen and the notification can never disagree about what is going on.
     */
    val pollsThisHour: StateFlow<Int> = placeLog.log
        .map { it.notes.pollsSince(clock.instant() - PlaceWatchPolicy.BUSY_WINDOW) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun clearWatchLog() {
        viewModelScope.launch { placeLog.clear() }
    }

    /** The one thing the place watch is allowed to say about itself, and only if asked to. */
    fun setBusyWatchNotice(on: Boolean) = update { it.copy(busyWatchNotice = on) }

    /** A new place when [index] is null, otherwise the one at [index] rewritten. */
    fun savePlace(index: Int?, place: SavedPlace) = update { settings ->
        val places = settings.savedPlaces.toMutableList()
        if (index != null && index in places.indices) places[index] = place else places += place
        settings.copy(savedPlaces = places)
    }

    fun removePlace(index: Int) = update { settings ->
        settings.copy(savedPlaces = settings.savedPlaces.filterIndexed { i, _ -> i != index })
    }


    fun setTheme(theme: ThemeMode) = update { it.copy(theme = theme) }

    /** What a blank reminder starts with; the editor's own tiles, one screen up. */
    fun toggleDefaultAction(action: Action) = update { settings ->
        val actions = settings.defaultActions
        // The two sound tiles are one choice here too; see EditorUiState.toggleAction.
        settings.copy(
            defaultActions = when {
                action in actions -> actions - action
                action in SOUND_ACTIONS -> actions - SOUND_ACTIONS + action
                else -> actions + action
            },
        )
    }
    fun setDefaultTime(time: LocalTime) = update { it.copy(defaultTime = time) }

    /** What "the next day" means to this person: where a recurrence in days or months lands. */
    fun setDayStart(time: LocalTime) = update { it.copy(dayStart = time) }
    fun setHaptics(enabled: Boolean) = update { it.copy(haptics = enabled) }

    /** What a reminder feels like. Unrelated to [setHaptics], which is the UI's own touch feedback. */
    fun setVibration(pattern: VibrationPattern) = update { it.copy(vibration = pattern) }

    /** What a reminder sounds like, and how insistently. */
    fun setAlertSound(sound: AlertSound) = update { it.copy(alertSound = sound) }
    fun setSoundPlays(plays: Int) = update { it.copy(soundPlays = plays.coerceIn(SoundLimits.PLAYS)) }
    fun setSoundGap(minutes: Int) = update { it.copy(soundGapMinutes = minutes.coerceIn(SoundLimits.GAP_MINUTES)) }

    /** Null puts the six tiles back in their usual order: no favourite. */
    // The two are one row of answers on screen: choosing a kind puts the popular order away.
    fun setDefaultTriggerKind(kind: TriggerKind?) = update { it.copy(defaultTriggerKind = kind, popularTriggersFirst = false) }

    fun setPopularTriggersFirst(on: Boolean) = update { it.copy(popularTriggersFirst = on, defaultTriggerKind = null) }

    /** What "the weekend" means when a reminder is put off to it. */
    fun setWeekend(day: DayOfWeek, time: LocalTime) = update { it.copy(weekendDay = day, weekendTime = time) }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { store.update(transform) }
    }

    class Factory(private val app: RwilcoApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(
                app.settingsStore,
                app.settings,
                app.repository,
                app.placeWatcher.state,
                app.placeLog,
                app.clock,
            ) as T
    }
}
