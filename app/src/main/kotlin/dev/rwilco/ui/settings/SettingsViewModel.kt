package dev.rwilco.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rwilco.RwilcoApplication
import dev.rwilco.data.ReminderRepository
import dev.rwilco.data.SettingsStore
import dev.rwilco.model.Action
import dev.rwilco.model.AppSettings
import dev.rwilco.model.PlaceWatchState
import dev.rwilco.model.SavedPlace
import dev.rwilco.model.ThemeMode
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime

class SettingsViewModel(
    private val store: SettingsStore,
    val settings: StateFlow<AppSettings?>,
    repository: ReminderRepository,
    placeWatch: Flow<PlaceWatchState>,
) : ViewModel() {

    /** Only ask for "allow all the time" when something actually waits on a place. */
    val hasPlaceReminders: StateFlow<Boolean> = repository.open
        .map { reminders -> reminders.any { reminder -> reminder.rules.any { it.trigger is Trigger.Location } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** What the place watch last saw and when it looks next, for the Location card. */
    val placeWatch: StateFlow<PlaceWatchState?> = placeWatch
        .map<PlaceWatchState, PlaceWatchState?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
        settings.copy(defaultActions = if (action in actions) actions - action else actions + action)
    }
    fun setDefaultTime(time: LocalTime) = update { it.copy(defaultTime = time) }

    /** What "the next day" means to this person: where a recurrence in days or months lands. */
    fun setDayStart(time: LocalTime) = update { it.copy(dayStart = time) }
    fun setHaptics(enabled: Boolean) = update { it.copy(haptics = enabled) }

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
            SettingsViewModel(app.settingsStore, app.settings, app.repository, app.placeWatcher.state) as T
    }
}
