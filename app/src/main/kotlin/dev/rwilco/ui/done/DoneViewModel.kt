package dev.rwilco.ui.done

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rwilco.RwilcoApplication
import dev.rwilco.data.ReminderRepository
import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The done list: what was, with a way back for each and a way to empty it all. */
class DoneViewModel(private val repository: ReminderRepository) : ViewModel() {

    val done: StateFlow<List<Reminder>?> = repository.done
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun restore(id: String) {
        viewModelScope.launch { repository.setStatus(id, Status.ACTIVE) }
    }

    fun purge() {
        viewModelScope.launch { repository.purgeDone() }
    }

    class Factory(private val app: RwilcoApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DoneViewModel(app.repository) as T
    }
}
