package dev.rwilco.ui.done

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rwilco.RwilcoApplication
import dev.rwilco.data.ReminderRepository
import dev.rwilco.model.DoneSection
import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import dev.rwilco.model.doneByDay
import dev.rwilco.model.groupDone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock

/**
 * What the done screen shows: the bands, the fortnight's bars, and how many there are. Built
 * here and off the main thread (0.68.0), the way Home's state is: the screen used to group up
 * to three months of rows and count the chart inside the list's own lambda, on every
 * recomposition, and read "today" once — so left open across midnight, every "hoy" was wrong.
 * [failed] is the store refusing to be read, which is an error to say rather than a
 * placeholder to sit in.
 */
data class DoneView(
    val sections: List<Pair<DoneSection, List<Reminder>>>,
    val bars: List<Int>,
    val total: Int,
    val failed: Boolean = false,
)

/** The done list: what was, with a way back for each and a way to empty it all. */
class DoneViewModel(private val repository: ReminderRepository, private val clock: Clock) : ViewModel() {

    /** A minute pulse, alive only while the screen is: the bands move at midnight. */
    private val minutePulse = flow {
        while (true) {
            delay(60_000L - Math.floorMod(clock.millis(), 60_000L))
            emit(clock.instant())
        }
    }

    val view: StateFlow<DoneView?> = combine(repository.done, merge(MutableStateFlow(clock.instant()), minutePulse)) { list, _ ->
        val now = clock.instant()
        DoneView(
            sections = groupDone(list, now, clock.zone).toList(),
            bars = doneByDay(list, now, clock.zone),
            total = list.size,
        )
    }
        .flowOn(Dispatchers.Default)
        .catch { failure ->
            Log.e(TAG, "could not build the done list", failure)
            emit(DoneView(sections = emptyList(), bars = emptyList(), total = 0, failed = true))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun restore(id: String) {
        viewModelScope.launch { repository.setStatus(id, Status.ACTIVE) }
    }

    /**
     * The undo of a restore: the row exactly as it was, done stamp and all. `setStatus(DONE)`
     * would stamp it done *now* and move it to the top of today's band, which is not where it was.
     */
    fun undoRestore(reminder: Reminder) {
        viewModelScope.launch { repository.restore(reminder) }
    }

    fun purge() {
        viewModelScope.launch { repository.purgeDone() }
    }

    class Factory(private val app: RwilcoApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DoneViewModel(app.repository, app.clock) as T
    }
}

private const val TAG = "DoneViewModel"
