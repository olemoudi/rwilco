package dev.rwilco.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rwilco.RwilcoApplication
import dev.rwilco.data.ReminderRepository
import dev.rwilco.data.SettingsStore
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
    fun setDefaultTime(time: LocalTime) = update { it.copy(defaultTime = time) }
    fun setHaptics(enabled: Boolean) = update { it.copy(haptics = enabled) }

    /** Null puts the six tiles back in their usual order: no favourite. */
    fun setDefaultTriggerKind(kind: TriggerKind?) = update { it.copy(defaultTriggerKind = kind) }

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
