package dev.rwilco.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rwilco.RwilcoApplication
import dev.rwilco.data.ReminderRepository
import dev.rwilco.data.SettingsStore
import dev.rwilco.geo.PlaceLogStore
import dev.rwilco.model.ContactSchedule
import dev.rwilco.model.AwakeHours
import dev.rwilco.model.Action
import dev.rwilco.model.AppSettings
import dev.rwilco.model.SoundLimits
import dev.rwilco.model.AlertSound
import dev.rwilco.model.SafetyNetSettings
import dev.rwilco.model.AlertStacking
import dev.rwilco.model.PlaceWatchState
import dev.rwilco.model.toggling
import dev.rwilco.model.SavedPlace
import dev.rwilco.model.isRoutine
import dev.rwilco.model.Status
import dev.rwilco.model.Reminder
import dev.rwilco.model.movePlaceIn
import dev.rwilco.model.movePlaceInPresets
import dev.rwilco.model.SavedWindow
import dev.rwilco.model.PlaceWatchPolicy
import dev.rwilco.model.ThemeMode
import dev.rwilco.model.UpdateChannel
import dev.rwilco.model.Trigger
import dev.rwilco.model.VibrationPattern
import dev.rwilco.model.WatchLog
import dev.rwilco.model.WatchTally
import dev.rwilco.model.tally
import dev.rwilco.model.pollsSince
import dev.rwilco.model.TriggerKind
import kotlinx.coroutines.flow.Flow
import dev.rwilco.model.FiringEvent
import dev.rwilco.model.placeUsersOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.DayOfWeek
import java.time.LocalTime
import dev.rwilco.model.RemovedCustomSnooze
import dev.rwilco.model.SnoozeSpec
import dev.rwilco.model.withSnoozeShown
import dev.rwilco.model.SnoozeLimits
import dev.rwilco.model.ContactKind
import dev.rwilco.model.ContactLoad
import dev.rwilco.model.contactLoad
import dev.rwilco.model.contactScheduleOf
import dev.rwilco.model.withCustomSnooze
import dev.rwilco.model.withCustomSnoozeBack
import dev.rwilco.model.withNotificationSnooze
import dev.rwilco.model.withoutCustomSnooze
import dev.rwilco.alarm.TestAlert

class SettingsViewModel(
    private val store: SettingsStore,
    val settings: StateFlow<AppSettings?>,
    private val repository: ReminderRepository,
    placeWatch: Flow<PlaceWatchState>,
    private val placeLog: PlaceLogStore,
    /** The app's own, not the system's: what the rows that say "ahora mismo" are drawn against. */
    val clock: Clock,
) : ViewModel() {

    /** Only ask for "allow all the time" when something actually waits on a place. */
    val hasPlaceReminders: StateFlow<Boolean> = repository.open
        .map { reminders -> reminders.any { reminder -> reminder.rules.any { it.trigger is Trigger.Location } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * What each kind of contact asks of its schedule against what that schedule can carry
     * (`contactLoad`): the one place the app says out loud that there are more people than turns.
     * Past capacity nothing was ever said — the queue simply never reached the back of itself.
     */
    val contactLoads: StateFlow<Map<ContactKind, ContactLoad>> = combine(repository.open, settings) { reminders, current ->
        if (current == null) {
            emptyMap()
        } else {
            ContactKind.entries.associateWith { kind -> contactLoad(reminders, kind, current.contactScheduleOf(kind), clock.zone) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

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

    /**
     * What the last day of looking came to: how many looks, what each cost, and which circle set
     * the pace. The lines underneath are the argument; this is the answer, and it is the only
     * form of it anybody can compare with yesterday's.
     */
    val watchTally: StateFlow<WatchTally> = placeLog.log
        .map { it.notes.tally(clock.instant() - TALLY_WINDOW) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchTally())

    /**
     * A real reminder ten seconds out, through the real path (see [TestAlert]). Saved and
     * nothing else: the scheduling watcher arms it, the alarm fires it, and "hecho" deletes it.
     */
    fun testAlert(text: String, actions: Set<Action> = TestAlert.EVERYTHING) {
        viewModelScope.launch {
            // One at a time: a rehearsal nobody answers stays as a row (only "hecho" removes
            // one), and three taps while testing left three overdue cards on Home, each with a
            // safety net of its own a day later.
            repository.allNow().filter { TestAlert.isTest(it.id) }.forEach { repository.delete(it.id) }
            repository.save(TestAlert.reminder(clock.instant(), clock.zone, text, actions))
        }
    }

    fun clearWatchLog() {
        viewModelScope.launch { placeLog.clear() }
    }

    /** The one thing the place watch is allowed to say about itself, and only if asked to. */
    fun setBusyWatchNotice(on: Boolean) = update { it.copy(busyWatchNotice = on) }

    private val _placeMove = MutableStateFlow<PlaceMoveAsk?>(null)

    /** An edited place that reminders still carry a copy of, waiting for "update them too?". */
    val placeMove: StateFlow<PlaceMoveAsk?> = _placeMove.asStateFlow()

    /**
     * A new place when [index] is null, otherwise the one at [index] rewritten. A rule copies a
     * saved place rather than pointing at it, so an edit that some reminder still carries the
     * old copy of asks first whether they go with it ([answerPlaceMove]).
     */
    fun savePlace(index: Int?, place: SavedPlace) {
        val old = index?.let { settings.value?.savedPlaces?.getOrNull(it) }
        if (old == null || old == place) return writePlace(index, place, carry = null)
        viewModelScope.launch {
            val carrying = movePlaceIn(repository.allNow(), old, place)
            if (carrying.isEmpty()) writePlace(index, place, carry = null)
            else _placeMove.value = PlaceMoveAsk(index, old, place, carrying.map(::PlaceUser))
        }
    }

    /** The answer: [carry] moves every copy with the place; otherwise only the place is saved. */
    fun answerPlaceMove(carry: Boolean) {
        val ask = _placeMove.value ?: return
        _placeMove.value = null
        writePlace(ask.index, ask.new, carry = ask.old.takeIf { carry })
    }

    /** [carry] is the place as it was, when the reminders and presets on it move too. */
    private fun writePlace(index: Int?, place: SavedPlace, carry: SavedPlace?) {
        viewModelScope.launch {
            if (carry != null) repository.saveAll(movePlaceIn(repository.allNow(), carry, place))
            store.update { settings ->
                val places = settings.savedPlaces.toMutableList()
                if (index != null && index in places.indices) places[index] = place else places += place
                settings.copy(
                    savedPlaces = places,
                    presets = if (carry != null) movePlaceInPresets(settings.presets, carry, place) else settings.presets,
                )
            }
        }
    }

    private val _placeRemove = MutableStateFlow<PlaceRemoveAsk?>(null)

    /** A place being deleted that reminders still ring by, waiting for what to do with them. */
    val placeRemove: StateFlow<PlaceRemoveAsk?> = _placeRemove.asStateFlow()

    private val _placeRemoved = Channel<PlaceRemoved>(Channel.BUFFERED)

    /** A place gone, for the snackbar that offers it back. */
    val placeRemoved: Flow<PlaceRemoved> = _placeRemoved.receiveAsFlow()

    /**
     * One tap when nothing rings by the place, as it always was. When something does, the
     * question first ([answerPlaceRemove]): those reminders can stay on their own copies of the
     * circle, or go with it — and going with it is asked twice, because it deletes things.
     */
    fun removePlace(index: Int) {
        val place = settings.value?.savedPlaces?.getOrNull(index) ?: return
        viewModelScope.launch {
            val users = placeUsersOf(repository.allNow(), place)
            if (users.isEmpty()) dropPlace(index, place, deleting = emptyList())
            else _placeRemove.value = PlaceRemoveAsk(index, place, users)
        }
    }

    /** Null is "cancel"; false keeps the reminders; true deletes them, the second time it is said. */
    fun answerPlaceRemove(deleteThem: Boolean?) {
        val ask = _placeRemove.value ?: return
        when {
            deleteThem == null -> _placeRemove.value = null
            deleteThem && !ask.sure -> _placeRemove.value = ask.copy(sure = true)
            else -> {
                _placeRemove.value = null
                viewModelScope.launch { dropPlace(ask.index, ask.place, deleting = if (deleteThem) ask.reminders else emptyList()) }
            }
        }
    }

    /** The place out of the list and [deleting] out of the database, their history kept for the undo. */
    private suspend fun dropPlace(index: Int, place: SavedPlace, deleting: List<Reminder>) {
        val deleted = deleting.map { reminder ->
            val history = repository.historyAsWritten(reminder.id)
            repository.delete(reminder.id)
            DeletedReminder(reminder, history)
        }
        store.update { settings -> settings.copy(savedPlaces = settings.savedPlaces.filterIndexed { i, _ -> i != index }) }
        _placeRemoved.send(PlaceRemoved(index, place, deleted))
    }

    /** The undo: the place back where it was (or at the end if the list has shrunk since), and whatever went with it. */
    fun undoPlaceRemoval(removed: PlaceRemoved) {
        viewModelScope.launch {
            removed.deleted.forEach { repository.restore(it.reminder, it.history) }
            store.update { settings ->
                val places = settings.savedPlaces.toMutableList()
                places.add(removed.index.coerceIn(0, places.size), removed.place)
                settings.copy(savedPlaces = places)
            }
        }
    }

    /** The same two, for a stretch of the day kept under a name. */
    fun saveWindow(index: Int?, window: SavedWindow) = update { settings ->
        val windows = settings.savedWindows.toMutableList()
        if (index != null && index in windows.indices) windows[index] = window else windows += window
        settings.copy(savedWindows = windows)
    }

    fun removeWindow(index: Int) = update { settings ->
        settings.copy(savedWindows = settings.savedWindows.filterIndexed { i, _ -> i != index })
    }

    fun restoreWindow(index: Int, window: SavedWindow) = update { settings ->
        val windows = settings.savedWindows.toMutableList()
        windows.add(index.coerceIn(0, windows.size), window)
        settings.copy(savedWindows = windows)
    }


    fun setTheme(theme: ThemeMode) = update { it.copy(theme = theme) }

    /** What a blank reminder starts with; the editor's own tiles, one screen up. */
    fun toggleDefaultAction(action: Action) = update { it.copy(defaultActions = it.defaultActions.toggling(action)) }
    fun toggleRoutineAction(action: Action) = update { it.copy(routineActions = it.routineActions.toggling(action)) }
    fun setDefaultTime(time: LocalTime) = update { it.copy(defaultTime = time) }

    /** What "the next day" means to this person: where a recurrence in days or months lands. */
    fun setDayStart(time: LocalTime) = update { it.copy(dayStart = time) }
    /** The hours "por la tarde" and "por la noche" stand for: see [dev.rwilco.model.DayParts]. */
    fun setAfternoon(time: LocalTime) = update { it.copy(afternoon = time) }
    fun setEvening(time: LocalTime) = update { it.copy(evening = time) }
    fun setHaptics(enabled: Boolean) = update { it.copy(haptics = enabled) }

    /** Home's line of encouragement, and the silent word every few days (0.151.0). */
    fun setCheerLine(on: Boolean) = update { it.copy(cheerLine = on) }

    fun setCheerNotifications(on: Boolean) = update { it.copy(cheerNotifications = on) }

    /** What a reminder feels like. Unrelated to [setHaptics], which is the UI's own touch feedback. */
    fun setVibration(pattern: VibrationPattern) = update { it.copy(vibration = pattern) }

    /** What a reminder sounds like, and how insistently. */
    fun setAlertSound(sound: AlertSound) = update { it.copy(alertSound = sound) }

    /** Null puts the two back together: the insistent reminders go back to the one above. */
    fun setInsistentSound(sound: AlertSound?) = update { it.copy(insistentSound = sound) }

    /** The three numbers the safety net is made of. There is no switch: it holds for every reminder. */
    fun setSafetyNet(net: SafetyNetSettings) = update { it.copy(safetyNet = net) }
    fun setSnoozeCustomMinutes(minutes: Int) = update { it.copy(snoozeCustomMinutes = minutes.coerceIn(SnoozeLimits.CUSTOM_MINUTES)) }
    /** An offer on the alert, or one door further behind "a otro momento": see [dev.rwilco.model.snoozeBoard]. */
    fun setSnoozeShown(key: String, shown: Boolean) = update { it.withSnoozeShown(key, shown) }
    /** One of the notification's two, by its key: one of the app's or one of the person's own. */
    fun pickNotificationSnooze(key: String) = update { it.withNotificationSnooze(key) }

    /**
     * The snoozes that are the person's own ([SnoozeSpec]). One that is refused changes nothing —
     * the builder has already said why — and one that is deleted goes from everywhere it was
     * named, which is why the undo is handed back everything that went with it.
     */
    fun addCustomSnooze(spec: SnoozeSpec) = update { it.withCustomSnooze(spec) }
    fun removeCustomSnooze(key: String) = update { it.withoutCustomSnooze(key) }
    fun restoreCustomSnooze(removed: RemovedCustomSnooze) = update { it.withCustomSnoozeBack(removed) }
    fun setSoundPlays(plays: Int) = update { it.copy(soundPlays = plays.coerceIn(SoundLimits.PLAYS)) }
    fun setSoundGap(minutes: Int) = update { it.copy(soundGapMinutes = minutes.coerceIn(SoundLimits.GAP_MINUTES)) }

    /** Where a reminder's sound comes out when headphones are connected. */
    fun setAlertToHeadphones(only: Boolean) = update { it.copy(alertToHeadphones = only) }

    /** Updates only where the data is not paid for by the megabyte; the button always goes. */
    fun setUpdatesWifiOnly(only: Boolean) = update { it.copy(updatesWifiOnly = only) }

    /** Which stream of builds this phone follows. See [UpdateChannel]. */
    fun setUpdateChannel(channel: UpdateChannel) = update { it.copy(updateChannel = channel) }

    /** Two full-screen reminders at once: one behind the other, or side by side as strips. */
    fun setAlertStacking(stacking: AlertStacking) = update { it.copy(alertStacking = stacking) }

    /** Null puts the six tiles back in their usual order: no favourite. */
    // The two are one row of answers on screen: choosing a kind puts the popular order away.
    fun setDefaultTriggerKind(kind: TriggerKind?) = update { it.copy(defaultTriggerKind = kind, popularTriggersFirst = false) }

    fun setPopularTriggersFirst(on: Boolean) = update { it.copy(popularTriggersFirst = on, defaultTriggerKind = null) }

    /** What "the weekend" means when a reminder is put off to it. */
    fun setWeekend(day: DayOfWeek, time: LocalTime) = update { it.copy(weekendDay = day, weekendTime = time) }

    /**
     * How each kind of contact is told about. Every contact follows it, the ones already written
     * included (RwilcoApplication carries the cadences into their rows), bar what was set by hand.
     */
    fun setWorkContacts(schedule: ContactSchedule) = update { it.copy(workContacts = schedule) }

    fun setPersonalContacts(schedule: ContactSchedule) = update { it.copy(personalContacts = schedule) }

    /** And when it is over, which is what gives a Sunday night its earlier bedtime. */
    fun setWeekendEnd(day: DayOfWeek, time: LocalTime) = update { it.copy(weekendEndDay = day, weekendEndTime = time) }

    /** The hours somebody is up: where "at random during the day" draws its moment from. */
    fun setAwake(hours: AwakeHours) = update { it.copy(awake = hours) }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { store.update(transform) }
    }

    private companion object {
        /** A day, because that is the unit anybody compares a battery against. */
        val TALLY_WINDOW: Duration = Duration.ofHours(24)
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

/** A saved place edited from [old] to [new], and the reminders that still carry [old]. */
data class PlaceMoveAsk(val index: Int, val old: SavedPlace, val new: SavedPlace, val users: List<PlaceUser>)

/** One line of the list the question shows: whose words, and whether it is a routine or resting. */
data class PlaceUser(val text: String, val routine: Boolean, val paused: Boolean) {
    constructor(reminder: Reminder) : this(reminder.text, reminder.isRoutine, reminder.status == Status.PAUSED)
}

/** A saved place being deleted, the reminders that ring by it, and whether "delete them" has been said once already. */
data class PlaceRemoveAsk(val index: Int, val place: SavedPlace, val reminders: List<Reminder>, val sure: Boolean = false) {
    val users: List<PlaceUser> get() = reminders.map(::PlaceUser)
}

/** A reminder deleted with its place, and its history, so the undo can bring back both. */
data class DeletedReminder(val reminder: Reminder, val history: List<FiringEvent>)

/** A saved place deleted from [index], and the reminders deleted with it, if any. */
data class PlaceRemoved(val index: Int, val place: SavedPlace, val deleted: List<DeletedReminder>)
