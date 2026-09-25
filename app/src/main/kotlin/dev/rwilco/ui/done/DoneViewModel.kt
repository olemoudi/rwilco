package dev.rwilco.ui.done

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rwilco.RwilcoApplication
import dev.rwilco.data.ReminderRepository
import dev.rwilco.model.DoneSection
import dev.rwilco.model.Reminder
import dev.rwilco.model.SearchHit
import dev.rwilco.model.search
import dev.rwilco.model.Status
import dev.rwilco.data.SettingsStore
import dev.rwilco.model.Goal
import dev.rwilco.model.Standing
import dev.rwilco.model.Unlocked
import dev.rwilco.model.achievements
import dev.rwilco.model.asSubject
import dev.rwilco.model.globalStats
import dev.rwilco.model.mergeUnlocked
import dev.rwilco.model.nextGoal
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
 *
 * Since 0.150.0 the numbers are every "hecho" (`globalStats`), not the finished rows: [bars],
 * [week] and [lastWeek] count a pill taken every morning as much as a one-off finished. [total]
 * is still the rows, which is what the bands, the search and "vaciar" are about. [achievements]
 * are newest first.
 */
data class DoneView(
    val sections: List<Pair<DoneSection, List<Reminder>>>,
    val bars: List<Int>,
    val total: Int,
    val week: Int = 0,
    val lastWeek: Int = 0,
    val hechos: Int = 0,
    val streaks: List<Standing> = emptyList(),
    val neverFail: List<Standing> = emptyList(),
    val firstTime: Pair<Int, Int>? = null,
    val achievements: List<Unlocked> = emptyList(),
    val goal: Goal? = null,
    val failed: Boolean = false,
)

/** The done list: what was, with a way back for each and a way to empty it all. */
class DoneViewModel(
    private val repository: ReminderRepository,
    private val store: SettingsStore,
    private val clock: Clock,
) : ViewModel() {

    /** A minute pulse, alive only while the screen is: the bands move at midnight. */
    private val minutePulse = flow {
        while (true) {
            delay(60_000L - Math.floorMod(clock.millis(), 60_000L))
            emit(clock.instant())
        }
    }

    /** What the numbers name: every reminder still here, open or done. Unmoved by a re-arm. */
    private val openSubjects = repository.open.map { list -> list.map(Reminder::asSubject) }.distinctUntilChanged()

    private val kept = store.settings.map { it.achievements }.distinctUntilChanged()

    val view: StateFlow<DoneView?> = combine(
        repository.done,
        repository.allHistory,
        openSubjects,
        kept,
        merge(MutableStateFlow(clock.instant()), minutePulse),
    ) { list, history, open, unlocked, _ ->
        val now = clock.instant()
        val subjects = (open + list.map(Reminder::asSubject)).associateBy { it.id }
        val stats = globalStats(history, subjects, now, clock.zone)
        // What the history proves, kept for good (a no-op when it adds nothing); the kept list
        // coming back through [kept] then finds nothing new to write.
        val derived = achievements(stats.tallies, now, clock.zone)
        // A settings write that fails costs the keeping, not the screen.
        runCatching { store.keepUnlocked(derived) }.onFailure { Log.w(TAG, "could not keep the achievements", it) }
        val earned = mergeUnlocked(unlocked, derived)
        DoneView(
            sections = groupDone(list, now, clock.zone).toList(),
            bars = stats.byDay,
            total = list.size,
            week = stats.thisWeek,
            lastWeek = stats.lastWeek,
            hechos = stats.hechos,
            streaks = stats.streaks,
            neverFail = stats.neverFail,
            firstTime = stats.firstTime,
            achievements = earned.sortedByDescending { it.on },
            goal = nextGoal(stats, earned),
        )
    }
        .flowOn(Dispatchers.Default)
        .catch { failure ->
            Log.e(TAG, "could not build the done list", failure)
            emit(DoneView(sections = emptyList(), bars = emptyList(), total = 0, failed = true))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val query = MutableStateFlow("")

    /**
     * This screen's own search (0.146.0): what was done is looked for here, and Home's magnifier
     * finds only what is still to do. The same forgiving match, best first; null while nothing
     * is typed, which is the screen as it always was rather than an empty result.
     */
    val found: StateFlow<List<Reminder>?> = combine(repository.done, query) { list, text ->
        if (text.isBlank()) null
        else search(list, text, limit = list.size).filterIsInstance<SearchHit.OfReminder>().map { it.reminder }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(text: String) {
        query.value = text
    }

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
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DoneViewModel(app.repository, app.settingsStore, app.clock) as T
    }
}

private const val TAG = "DoneViewModel"
