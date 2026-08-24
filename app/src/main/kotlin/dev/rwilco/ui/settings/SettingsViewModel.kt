package dev.rwilco.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rwilco.RwilcoApplication
import dev.rwilco.data.ReminderRepository
import dev.rwilco.data.SettingsStore
import dev.rwilco.model.AppSettings
import dev.rwilco.model.ThemeMode
import dev.rwilco.model.Trigger
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime

class SettingsViewModel(
    private val store: SettingsStore,
    val settings: StateFlow<AppSettings?>,
    repository: ReminderRepository,
) : ViewModel() {

    /** Only ask for "allow all the time" when something actually waits on a place. */
    val hasPlaceReminders: StateFlow<Boolean> = repository.open
        .map { reminders -> reminders.any { reminder -> reminder.rules.any { it.trigger is Trigger.Location } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)


    fun setTheme(theme: ThemeMode) = update { it.copy(theme = theme) }
    fun setDefaultTime(time: LocalTime) = update { it.copy(defaultTime = time) }
    fun setHaptics(enabled: Boolean) = update { it.copy(haptics = enabled) }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { store.update(transform) }
    }

    class Factory(private val app: RwilcoApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(app.settingsStore, app.settings, app.repository) as T
    }
}
