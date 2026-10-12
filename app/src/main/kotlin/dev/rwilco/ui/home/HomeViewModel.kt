package dev.rwilco.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rwilco.RwilcoApplication
import dev.rwilco.alarm.ReminderFiring
import dev.rwilco.alarm.Door
import dev.rwilco.data.ReminderRepository
import dev.rwilco.data.SettingsStore
import dev.rwilco.notify.AlertNotifications
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
import dev.rwilco.model.knownTags
import dev.rwilco.model.removeTagIn
import dev.rwilco.model.removeTagInPresets
import dev.rwilco.model.renameTagIn
import dev.rwilco.model.tagMergeCount
import dev.rwilco.model.renameTagInPresets
import dev.rwilco.model.tagsEverUsed
import dev.rwilco.model.tagsInUse
import dev.rwilco.model.withTagForgotten
import dev.rwilco.model.withTagPref
import dev.rwilco.model.withTagRenamed
import dev.rwilco.model.Transition
import dev.rwilco.model.Trigger
import dev.rwilco.model.ValidationWarning
import dev.rwilco.model.moment
import dev.rwilco.model.NextFire
import dev.rwilco.model.nextFire
import dev.rwilco.model.presetsByPopularity
import dev.rwilco.model.typicalAccuracyM
import dev.rwilco.model.toReminder
import dev.rwilco.model.used
import dev.rwilco.model.warnings
import dev.rwilco.model.Heard
import dev.rwilco.model.HeardSpan
import dev.rwilco.model.Repaired
import dev.rwilco.model.Spoken
import dev.rwilco.model.hear
import dev.rwilco.model.repaired
import dev.rwilco.diag.Diag
import dev.rwilco.voice.VoiceInputEvent
import dev.rwilco.voice.GeminiAnswer
import dev.rwilco.voice.GeminiInput
import dev.rwilco.voice.GeminiSetup
import dev.rwilco.model.geminiInstruction
import dev.rwilco.model.geminiSchema
import dev.rwilco.model.geminiPlaces
import dev.rwilco.model.toHeard
import dev.rwilco.model.mightSayWhen
import dev.rwilco.ui.editor.spokenFormJson
import dev.rwilco.model.Recurrence
import dev.rwilco.ui.editor.sheets.isOnline
import dev.rwilco.voice.Refining
import dev.rwilco.voice.VoiceAlternative
import dev.rwilco.voice.VoiceCheck
import dev.rwilco.voice.VoiceOutcome
import dev.rwilco.voice.VoicePreview
import dev.rwilco.voice.VoiceState
import dev.rwilco.voice.choosing
import dev.rwilco.voice.refinedWith
import dev.rwilco.voice.refiningStopped
import dev.rwilco.voice.voiceStep
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.mapLatest
import java.util.UUID
import dev.rwilco.cheer.CheerText
import dev.rwilco.model.CheerPick
import dev.rwilco.model.DayParts
import dev.rwilco.model.dayParts
import dev.rwilco.model.daySlot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import android.util.Log
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import dev.rwilco.model.SnoozeOffer
import dev.rwilco.model.snoozeTerms
import dev.rwilco.model.snoozeBoard
import dev.rwilco.model.SnoozeTerms
import dev.rwilco.model.SnoozeBoard
import dev.rwilco.model.DEFAULT_SNOOZE_MINUTES
import java.time.Instant
import dev.rwilco.ui.settings.AlertReadiness
import dev.rwilco.ui.settings.liveDismissals
import dev.rwilco.geo.hereFix
import dev.rwilco.model.Fix
import dev.rwilco.model.SnoozePlace
import dev.rwilco.model.circle
import dev.rwilco.model.hereCircle
import dev.rwilco.model.snoozePlaceOffers
import dev.rwilco.model.speaksForHere
import dev.rwilco.geo.hasBackgroundLocation
import kotlinx.coroutines.flow.first
import dev.rwilco.model.FiringEvent
import dev.rwilco.model.FiringKind
import dev.rwilco.model.roundExpiry

/** What Home reports back that is not state: things to say in a snackbar. */
sealed interface HomeEvent {
    /**
     * A reminder left the list; the snackbar offers to bring it back. [history] is what a
     * delete takes with it (the cascade), so the undo can put it back too.
     */
    data class Removed(
        val kind: Kind,
        val reminder: Reminder,
        val history: List<FiringEvent> = emptyList(),
        /**
         * When it comes back, for a reminder that is not finished — the one thing "Hecho" never
         * said. A recurring reminder dealt with from the list stays open on its next round, and
         * a word that reads as "this is over" was the whole of what somebody was told about it.
         * Null when it really is done, when it is a delete, and when nothing can name a moment
         * (a place: it comes back on arrival, and no clock knows when that is).
         */
        val comesBackAt: Instant? = null,
        /** The history line the "hecho" or "saltar" wrote: what its undo takes back, and nothing else. */
        val line: Long? = null,
    ) : HomeEvent {
        /** [SKIPPED] is a "hecho" given ahead of the ring, said with the word for that. */
        enum class Kind { DONE, DELETED, SKIPPED }
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
    data class Snoozed(
        val reminder: Reminder,
        val until: Instant?,
        val place: Trigger.Location? = null,
        /** "Quitar el posponer": said outright, rather than read off two nulls. */
        val cancelled: Boolean = false,
        /**
         * Which side of its line the watch had the phone on for the place [reminder] was waiting
         * at before this, if it was waiting at one. An undo puts the circle back on the row,
         * and the watch — which prunes what it has no circle for — has to be told the side
         * again, or the first crossing is not news to it.
         */
        val sideBefore: Boolean? = null,
        /**
         * The history line the snooze wrote, so its undo can take that one back — left there, it
         * was a snooze the statistics counted and nobody gave. Null for a cancel, which writes none.
         */
        val line: Long? = null,
    ) : HomeEvent

    /** "Al salir de aquí" asked where here is, and nothing could say. Nothing was written. */
    data object NoFix : HomeEvent

    /**
     * A preset could not be written blind — a moment it carries has already passed — so the
     * form is opened on it instead of quietly making something overdue.
     */
    data class NeedsEditor(val presetId: String) : HomeEvent

    /**
     * A tag was renamed on [count] reminders. [undoable] is whether the same rename the other
     * way is the inverse: it is not once [to] was a tag already, because then it would sweep
     * up the rows that were [to] all along.
     */
    data class TagRenamed(val from: String, val to: String, val count: Int, val undoable: Boolean) : HomeEvent
}

class HomeViewModel(
    private val repository: ReminderRepository,
    private val store: SettingsStore,
    /** "Hecho" is one answer with one meaning, wherever it is given: see [markDone]. */
    private val firing: ReminderFiring,
    settings: Flow<AppSettings?>,
    /** Which circles the phone is inside, as the place watch last saw it. */
    private val placeWatch: Flow<PlaceWatchState>,
    /**
     * What this phone's positions usually come back at, from the watch's own log — the number
     * that decides whether a circle can be judged at all ([TriggerRowUi.underDoubt]). A flow
     * because it moves as the phone does: a week of GPS in the street and a week of wifi
     * indoors are two different phones as far as a fifty-metre circle is concerned.
     */
    private val fixAccuracy: Flow<Int?> = flowOf(null),
    val clock: Clock,
    /** Where the phone is now, for "al salir de aquí"; null when nothing can say. */
    private val hereFix: suspend () -> Fix? = { null },
    /** Whether a place answer can be kept at all: without "all the time" location neither the fences nor the watch run. */
    private val locationAllowed: () -> Boolean = { false },
    /** The watch's door for which side of a line the phone starts on; see [ReminderFiring.snoozeToPlace]. */
    private val rememberSide: suspend (String, Transition) -> Unit = { _, _ -> },
    /**
     * Which of our cards are in the shade, by notification id — the second witness to "waiting
     * for an answer" (`Waiting.kt`), for the answers written nowhere else: "todavía no" on a
     * routine's question takes the card down and writes nothing.
     *
     * Read inside the rebuild rather than kept in a flow of its own. There is no telling when a
     * card is swiped — a listener for that is a system-wide permission, for a personal app, to
     * be told about every notification on the phone — so it is asked at the moments Home is
     * built anyway: every minute, on every row that changes, and on every resume. A row can
     * therefore outlive its card by up to a minute, which is the cheap way round: the other
     * would be a row that vanished while being read.
     */
    private val openCards: suspend () -> Set<Int> = { emptySet() },
    /** The line of encouragement for a part of the day, or null for silence (`Cheering.lineFor`). */
    private val cheerLine: suspend (String) -> CheerPick? = { null },
    /** Whether Gemini may be asked, and with which key (0.178.0). */
    private val geminiSetup: suspend () -> GeminiSetup = { GeminiSetup() },
    /** Asks Gemini: key, sentence, instruction, schema. */
    private val askGemini: dev.rwilco.voice.AskGemini = { _, _, _, _ -> GeminiAnswer.Failed },
    /** Whether the phone has a network right now: offline, Gemini is not asked at all (0.183.0). */
    private val online: () -> Boolean = { true },
) : ViewModel() {

    /** The settings as last read, for the dictation sheet's live reading ([preview]). */
    private val latestSettings: StateFlow<AppSettings?> = settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The place answers a held card can give: the most-used saved place as a doorway in, and "aquí". */
    val placeOffers: StateFlow<List<SnoozePlace>> = combine(
        settings.filterNotNull(),
        repository.open,
        repository.done,
        placeWatch,
    ) { current, open, done, watch -> snoozePlaceOffers(current.savedPlaces, open + done, watch, locationAllowed()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The shapes kept under a name, the ones actually used at the top. */
    val presets: StateFlow<List<Preset>> = settings
        .filterNotNull()
        .map { presetsByPopularity(it.presets) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Whether any open reminder has a place in it — the question that turns the location grant
     * into one of Home's problems rather than a permission nobody here needs ([stripProblems]).
     *
     * Off the whole open list and not off Home's own chips: a routine is not on Home, and a
     * circle that counts one as done needs the grant exactly as much as one that rings.
     */
    val hasPlaceReminders: StateFlow<Boolean> = repository.open
        .map { reminders -> reminders.any { reminder -> reminder.rules.any { it.trigger is Trigger.Location } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The alert problems somebody has waved off from Home's strip; see [stripShows]. */
    val dismissedAlertProblems: StateFlow<Set<String>> = settings
        .filterNotNull()
        .map { it.dismissedAlertProblems }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** "Ahora no" on the strip: these problems, as they are, until the phone is fixed. */
    fun dismissAlertStrip(problems: Set<String>) {
        viewModelScope.launch { store.update { it.copy(dismissedAlertProblems = it.dismissedAlertProblems + problems) } }
    }

    /**
     * What is still waved off, after a real reading: a problem that has been fixed is forgotten,
     * so it is news again if it comes back. Never acted on before the first read — the optimistic
     * default says nothing is wrong, and taking it at its word threw away the "ahora no" that had
     * just been given.
     */
    fun noteAlertReadiness(readiness: AlertReadiness, placesReady: Boolean?) {
        val dismissed = dismissedAlertProblems.value
        val live = liveDismissals(readiness, dismissed, placesReady)
        if (live == dismissed) return
        viewModelScope.launch { store.update { it.copy(dismissedAlertProblems = live) } }
    }

    /** Home's cards down to a line each; see [AppSettings.compactHome]. */
    val compactHome: StateFlow<Boolean> = settings
        .filterNotNull()
        .map { it.compactHome }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * The cards showing the *opposite* of the mode: expanded ones while the list is compact,
     * and compacted ones while it is not.
     *
     * Kept as what has been flipped rather than as a state per card, because that is what it
     * is — the mode is the answer and these are the exceptions to it — and because it means a
     * reminder nobody has touched never has to be listed at all. Screen state, not settings: a
     * card opened out to read it is a thing somebody is doing now, not a preference. It lives
     * here rather than in the composable so it survives a rotation, and [setCompactHome]
     * empties it, because a toggle that left yesterday's exceptions behind would be a mode that
     * does not quite do what it says.
     */
    private val flipped = MutableStateFlow(emptySet<String>())
    val flippedCards: StateFlow<Set<String>> = flipped

    fun setCompactHome(compact: Boolean) {
        flipped.value = emptySet()
        viewModelScope.launch { store.update { it.copy(compactHome = compact) } }
    }

    /** One card in or out of step with the mode. */
    fun flipCard(id: String) {
        flipped.value = if (id in flipped.value) flipped.value - id else flipped.value + id
    }

    /**
     * One card open, whatever the mode: what a reminder just written asks for, so its words can
     * be read back and the pencil is one tap away. See [shownOpen].
     */
    fun expandCard(id: String) {
        flipped.value = shownOpen(flipped.value, id, compactHome.value)
    }

    /** How long the custom snooze is, for the menu's offers to read the way the alert's do. */
    val snoozeCustomMinutes: StateFlow<Int> = settings
        .filterNotNull()
        .map { it.snoozeCustomMinutes }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_SNOOZE_MINUTES)

    /**
     * Which snooze offers a card's menu shows and which wait behind "a otro momento", and what
     * their moments are worked out from: the alert's own board ([snoozeBoard]), so the same
     * answers sit in the same places wherever the question is asked.
     */
    val snoozeBoard: StateFlow<SnoozeBoard> = settings
        .filterNotNull()
        .map { snoozeBoard(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), snoozeBoard(AppSettings()))
    val snoozeTerms: StateFlow<SnoozeTerms> = settings
        .filterNotNull()
        .map { it.snoozeTerms }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings().snoozeTerms)

    /** The ones with a button on Home, in the same order: most reached for, first. */
    val pinnedPresets: StateFlow<List<Preset>> = presets
        .map { list -> list.filter { it.pinned } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Every tag there is, for the panel behind the row's "+".
     *
     * **Finished reminders included** — that is the whole point of the flow taking two lists.
     * The editor offers a tag back for as long as anything has ever worn it, so a tag left only
     * on finished reminders was being offered from one screen and was absent from the only place
     * that can rename or delete it: undeletable, by construction (0.90.0). Home's own chips still
     * leave it out, because a filter that finds nothing is not a filter — [TagRow.onHome] is what
     * lets the panel say that rather than leave it looking broken.
     */
    val tagRows: StateFlow<List<TagRow>> = combine(
        repository.open,
        repository.done,
        settings.filterNotNull(),
    ) { open, done, current ->
        val prefs = current.tagPrefs
        val live = tagsInUse(open)
        knownTags(tagsEverUsed(open + done), prefs).map { name ->
            TagRow(
                name = name,
                pinned = prefs.any { it.pinned && it.name.equals(name, ignoreCase = true) },
                onHome = live.any { it.equals(name, ignoreCase = true) },
            )
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Which tags lead the row of chips. Nothing else about the tag changes. */
    fun toggleTagPin(tag: String) {
        viewModelScope.launch {
            store.update { settings ->
                val pinned = settings.tagPrefs.any { it.pinned && it.name.equals(tag, ignoreCase = true) }
                settings.copy(tagPrefs = withTagPref(settings.tagPrefs, tag, pinned = !pinned))
            }
        }
    }

    /** A tag written down before anything wears it, so the editor can offer it straight away. */
    fun createTag(tag: String) {
        viewModelScope.launch {
            store.update { settings -> settings.copy(tagPrefs = withTagPref(settings.tagPrefs, tag, pinned = false)) }
        }
    }

    /** A rename that would merge two tags into one, waiting for its answer: see [tagMergeCount]. */
    data class TagMergeAsk(val from: String, val to: String, val count: Int)

    private val _tagMerge = MutableStateFlow<TagMergeAsk?>(null)
    val tagMerge: StateFlow<TagMergeAsk?> = _tagMerge

    /**
     * Renaming and removing reach the reminders themselves — a tag is what they carry, not a
     * record on its own — and then the row here, so a pinned tag is not left pinned under a
     * name nothing uses. Removing has no undo, which is why the panel asks before it; a rename
     * says how far it reached and offers itself the other way round (0.93.0) — it used to
     * rewrite forty rows and say nothing whatever.
     */
    fun renameTag(from: String, to: String, confirmed: Boolean = false) {
        viewModelScope.launch {
            val all = repository.allNow()
            // **A rename onto a tag that already exists is a merge, and a merge is asked about**
            // (0.132.0). It has no inverse — afterwards nothing says which reminders wore which —
            // and it used to be neither asked nor undoable: the rows were rewritten and the undo
            // quietly withheld. A respelling is not one ("casa" to "Casa" lands on itself), which
            // the old check got wrong too, so that rename lost the undo it is owed.
            val current = store.settings.first()
            val known = current.tagPrefs.map { it.name } + current.presets.flatMap { it.tags }
            val merge = tagMergeCount(all, known, from, to)
            if (merge != null && !confirmed) {
                _tagMerge.value = TagMergeAsk(from, to, merge)
                return@launch
            }
            val merged = merge != null
            val changed = renameTagIn(all, from, to)
            repository.saveAll(changed)
            store.update { settings ->
                settings.copy(
                    tagPrefs = withTagRenamed(settings.tagPrefs, from, to),
                    presets = renameTagInPresets(settings.presets, from, to),
                )
            }
            events.send(HomeEvent.TagRenamed(from, to, changed.size, undoable = !merged))
        }
    }

    /** The merge somebody was asked about, and said yes to. */
    fun confirmTagMerge() {
        val ask = _tagMerge.value ?: return
        _tagMerge.value = null
        renameTag(ask.from, ask.to, confirmed = true)
    }

    fun cancelTagMerge() {
        _tagMerge.value = null
    }

    fun deleteTag(tag: String) {
        viewModelScope.launch {
            repository.saveAll(removeTagIn(repository.allNow(), tag))
            store.update { settings ->
                settings.copy(
                    tagPrefs = withTagForgotten(settings.tagPrefs, tag),
                    presets = removeTagInPresets(settings.presets, tag),
                )
            }
        }
    }

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
            ).let { made ->
                // The deadline's close for the round that starts now, as the editor's save writes it.
                made.copy(expiresAt = made.roundExpiry(now, clock.zone, defaultTime, shape = shape))
            }
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

    // --- dictation (0.176.0) ---

    private val _voice = MutableStateFlow<VoiceState?>(null)

    /** Where the dictation sheet is, or null when it is closed (see [VoiceState] for why it lives here). */
    val voice: StateFlow<VoiceState?> = _voice

    /** The sentence being turned into a reminder; cancelled when the sheet is let go of first. */
    private var voiceWork: Job? = null

    /**
     * Gemini's second look at a written reminder the grammar had doubts about; cancelled with the
     * sheet. Set from the reading's own thread and cancelled from the main one.
     */
    @Volatile
    private var refineWork: Job? = null

    /**
     * What the words heard so far would be written as, for the sheet to show while it listens —
     * and what of them is being taken as a correction (0.187.0). Off the main thread, a beat after
     * each new word.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val voicePreview: StateFlow<VoicePreview?> = _voice
        .map { (it as? VoiceState.Listening)?.stretches ?: (it as? VoiceState.Thinking)?.stretches }
        .distinctUntilChanged()
        .mapLatest { stretches -> stretches?.takeIf { it.isNotEmpty() }?.let(::preview) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The sheet opens: listening at once, or asking for the microphone first. */
    fun openVoice(micAllowed: Boolean) {
        if (_voice.value == null) _voice.value = if (micAllowed) VoiceState.Listening() else VoiceState.Asking
    }

    /** One thing that happened to the sheet; a finished sentence is turned into a reminder here, once. */
    fun onVoice(event: VoiceInputEvent, hereLabel: String) {
        // Stepped on whatever the sheet is now, atomically: Gemini's answer may be landing on another thread.
        var before: VoiceState? = null
        val after = _voice.updateAndGet { current -> before = current; current?.let { voiceStep(it, event) } } ?: return
        if (after !is VoiceState.Thinking || before is VoiceState.Thinking) return
        // Off the main thread: reading a sentence is a few hundred regex passes, and the sheet is
        // animating while it happens.
        voiceWork = viewModelScope.launch(Dispatchers.Default) {
            val (outcome, refine) = fromVoice(after.stretches, hereLabel)
            val answer = when (outcome) {
                is VoiceOutcome.Created -> VoiceInputEvent.Written(outcome.reminder, outcome.check)
                is VoiceOutcome.Proposed -> VoiceInputEvent.Proposed(outcome.reminder, outcome.form, outcome.spoken, outcome.here, outcome.check)
                is VoiceOutcome.ToEditor -> VoiceInputEvent.Unwritten(outcome.text, outcome.why, outcome.form)
            }
            _voice.update { current -> current?.let { voiceStep(it, answer) } }
            // Not for a sheet let go of while this was finishing: nobody would see the answer.
            if (refine != null && outcome is VoiceOutcome.Created && isActive) refine(outcome.reminder, refine)
        }
    }

    /** The sheet is leaving: whatever it was working on stops now, before it slides away. */
    fun stopVoice() {
        voiceWork?.cancel()
        voiceWork = null
        refineWork?.cancel()
        refineWork = null
        _voice.update { it.refiningStopped() }
    }

    /** The sheet is gone. */
    fun closeVoice() {
        stopVoice()
        _voice.value = null
    }

    /**
     * A sentence said to the mic: written as a reminder when it was understood whole, or what
     * stopped it, for the sheet to take the words to the editor with (`Spoken.kt`). Nothing is
     * written once the sheet has been let go of ([stopVoice]).
     */
    private suspend fun fromVoice(stretches: List<String>, hereLabel: String): Pair<VoiceOutcome, Refine?> {
        val settings = store.settings.first()
        // What was meant, read; what was said, shown and asked of Gemini (0.187.0): Gemini reads
        // corrections by itself, and its second look is worth most where this one went wrong.
        val repaired = repaired(stretches, clock.instant(), clock.zone, settings)
        val transcript = repaired.heard
        return when (val heard = withHere { here -> hear(repaired.text, clock.instant(), clock.zone, settings, here, hereLabel) }) {
            is Here.Read -> {
                // **Written first, refined after** (0.183.0; the owner: "piensa que gemini puede
                // fallar o tardar en responder"). A reading with doubts in it is written at once,
                // as any reading is, and Gemini is asked beside it; whatever it says is offered,
                // and a Gemini that is slow or silent costs nothing but the offer.
                val setup = if (heard.read.doubts.isEmpty()) null else geminiSetup().takeIf { it.usable && online() }
                val outcome = settle(heard.read, heard.fix, settings, write = true, repaired, refining = setup != null)
                outcome to setup?.let { Refine(transcript, heard.read.said, it.key, settings, hereLabel, heard.fix) }
            }
            is Here.Missing -> (VoiceOutcome.ToEditor(heard.said, VoiceOutcome.Why.NO_FIX) to null).also { noteVoice("no position for here") }
            is Here.Unsure -> {
                // What the grammar could not read, Gemini is asked — when the person said so in
                // Settings and there is a network to ask over; never for a sentence that is only
                // a "when" (nothing to guess), and never for one with nothing in it that sounds
                // like a when or a where at all ("comprar pan"), which no guess can turn into one.
                val setup = geminiSetup()
                val worthAsking = heard.unsure.why == Heard.Why.PARTIAL || (heard.unsure.why == Heard.Why.NOTHING && mightSayWhen(repaired.text))
                if (setup.usable && worthAsking && online()) return fromGemini(repaired, heard.unsure.text, setup.key, settings, hereLabel) to null
                toEditor(heard.unsure) to null
            }
        }
    }

    /**
     * What Gemini's second look at a written reading needs: the sentence, how to ask, and the
     * position "aquí" was already drawn round — the same circle, or two readings of one sentence
     * would never agree, and a second position would spend Gemini's seconds on the GPS.
     */
    private class Refine(val transcript: String, val said: String, val key: String, val settings: AppSettings, val hereLabel: String, val fix: Fix?)

    /**
     * Gemini's second look at [written], a reading the grammar had doubts about (0.183.0), beside
     * the sheet that already shows it ("Mejorando la captura…"). At most [GEMINI_PATIENCE_MS]: past
     * that, or with no answer, the reading stays as it is and the sheet says so. Its answer goes
     * only onto the sheet it was asked for — one let go of, or showing another reminder, is left alone.
     */
    private fun refine(written: Reminder, refine: Refine) {
        refineWork = viewModelScope.launch(Dispatchers.Default) {
            val offer = withTimeoutOrNull(GEMINI_PATIENCE_MS) { geminiReadingOf(written, refine) }
            val now = _voice.updateAndGet { it.refinedWith(written.id, offer) }
            (now as? VoiceState.Created)?.takeIf { it.reminder.id == written.id }?.let { noteVoice("gemini refine: ${it.check.refining.name.lowercase()}") }
        }
    }

    /** Gemini's reading of the sentence, through the same gate as the grammar's, under [written]'s id; null for none. */
    private suspend fun geminiReadingOf(written: Reminder, refine: Refine): VoiceAlternative? {
        val settings = refine.settings
        val answer = askGemini(refine.key, refine.transcript, geminiInstruction(clock.instant(), clock.zone, settings), geminiSchema(settings.geminiPlaces))
        val reading = (answer as? GeminiAnswer.Reading)?.reading ?: return null
        val heard = if (refine.fix != null) {
            (reading.toHeard(clock.instant(), clock.zone, settings, refine.fix, refine.hereLabel, refine.said) as? Heard.Read)?.let { Here.Read(it, refine.fix) }
        } else {
            withHere { here -> reading.toHeard(clock.instant(), clock.zone, settings, here, refine.hereLabel, refine.said) } as? Here.Read
        } ?: return null
        // Settled as of the moment the sentence was written, so the two readings can be compared.
        val reminder = heard.read.spoken.toReminder(written.id, written.createdAt, clock.zone, settings) ?: return null
        return VoiceAlternative(reminder, firesAt(reminder, settings), VoiceAlternative.Kind.GEMINI, heard.fix)
    }

    /**
     * Gemini asked about a sentence the grammar could not read (0.178.0). Its answer goes through
     * the same doors a reading of the grammar's does, and is then **proposed, not written**: the
     * sheet shows it and asks.
     */
    private suspend fun fromGemini(repaired: Repaired, said: String, key: String, settings: AppSettings, hereLabel: String): VoiceOutcome {
        _voice.update { current -> (current as? VoiceState.Thinking)?.copy(asking = true) ?: current }
        // Never longer than the person would wait: a Gemini still thinking is a Gemini that failed.
        val answer = withTimeoutOrNull(GEMINI_PATIENCE_MS) {
            askGemini(key, repaired.heard, geminiInstruction(clock.instant(), clock.zone, settings), geminiSchema(settings.geminiPlaces))
        } ?: GeminiAnswer.Failed
        val reading = when (answer) {
            is GeminiAnswer.Reading -> answer.reading
            GeminiAnswer.KeyRefused -> return VoiceOutcome.ToEditor(said, VoiceOutcome.Why.GEMINI_KEY).also { noteVoice("gemini: key refused") }
            GeminiAnswer.Failed -> return VoiceOutcome.ToEditor(said, VoiceOutcome.Why.GEMINI_FAILED).also { noteVoice("gemini: no answer") }
        }
        return when (val heard = withHere { here -> reading.toHeard(clock.instant(), clock.zone, settings, here, hereLabel, said) }) {
            // Gemini's reading is of what was said, so nothing in it is marked as a correction taken out.
            is Here.Read -> settle(heard.read, heard.fix, settings, write = false, repaired, gemini = true)
            is Here.Missing -> VoiceOutcome.ToEditor(said, VoiceOutcome.Why.NO_FIX)
            is Here.Unsure -> toEditor(heard.unsure).also { noteVoice("gemini: no reading") }
        }
    }

    /** A reading, once "aquí" has had its position — or the reason it could not be read. */
    private sealed interface Here {
        class Read(val read: Heard.Read, val fix: Fix?) : Here
        class Unsure(val unsure: Heard.Unsure) : Here
        class Missing(val said: String) : Here
    }

    /** [read] without a position, and again with one when it asks for "aquí". */
    private suspend fun withHere(read: suspend (Fix?) -> Heard): Here {
        var heard = read(null)
        var fix: Fix? = null
        if (heard is Heard.NeedsHere) {
            fix = hereFix() ?: return Here.Missing(heard.text)
            heard = read(fix)
        }
        return when (heard) {
            is Heard.Read -> Here.Read(heard, fix)
            is Heard.Unsure -> Here.Unsure(heard)
            is Heard.NeedsHere -> Here.Missing(heard.text)
        }
    }

    /**
     * A reading, through the gate: refused → the editor with the reading in it; otherwise
     * written ([write], the grammar's) or proposed (Gemini's, written only at "Guardar").
     */
    private suspend fun settle(
        read: Heard.Read,
        fix: Fix?,
        settings: AppSettings,
        write: Boolean,
        repaired: Repaired,
        refining: Boolean = false,
        gemini: Boolean = false,
    ): VoiceOutcome {
        val spoken = read.spoken
        val defaults = if (spoken.recurrence is Recurrence.Since) settings.routineActions else settings.defaultActions
        val form = spokenFormJson(spoken, spoken.actions.applyTo(defaults), clock.instant())
        val reminder = spoken.toReminder(UUID.randomUUID().toString(), clock.instant(), clock.zone, settings)
            ?: return VoiceOutcome.ToEditor(read.said, VoiceOutcome.Why.REFUSED, form).also { noteVoice("to the editor: it would not ring as said") }
        // What the sheet shows to check it by (0.182.0). The other readings are written under the
        // same id, so choosing one puts it in this one's place and the undo still finds it.
        val alternatives = read.alternatives
            .mapNotNull { it.toReminder(reminder.id, clock.instant(), clock.zone, settings) }
            .map { VoiceAlternative(it, firesAt(it, settings)) }
        // Shown against what was said: the reading's offsets are into what was meant, and go back
        // to where each word was heard; what was taken out as a correction is shown struck through.
        val check = VoiceCheck(
            heard = repaired.heard,
            parts = read.parts.map { HeardSpan(repaired.inHeard(it.range), it.family) }.filterNot { it.range.isEmpty() },
            doubts = read.doubts.map(repaired::inHeard).filterNot { it.isEmpty() },
            firesAt = firesAt(reminder, settings),
            alternatives = alternatives,
            refining = if (refining) Refining.ASKING else Refining.NONE,
            here = fix,
            dropped = if (gemini) emptyList() else repaired.dropped,
        )
        if (!write) {
            noteVoice("gemini: proposed ${reminder.rules.size} rule(s), ${reminder.recurrence::class.simpleName}")
            return VoiceOutcome.Proposed(reminder, form, spoken, fix, check)
        }
        currentCoroutineContext().ensureActive()
        repository.save(reminder)
        if (fix != null) seedHere(reminder, fix)
        noteVoice(
            "written: ${reminder.rules.size} rule(s), ${reminder.recurrence::class.simpleName}${if (fix != null) ", here" else ""}" +
                ", ${read.doubts.size} doubt(s), ${alternatives.size} alternative(s), ${repaired.dropped.size} repair(s)",
        )
        return VoiceOutcome.Created(reminder, check)
    }

    /** When [reminder] first rings, when that is a moment: a place has none, and a random draw keeps its own. */
    private fun firesAt(reminder: Reminder, settings: AppSettings): Instant? =
        (nextFire(reminder, clock.instant(), clock.zone, settings.defaultTime, settings.dayStart, settings.dayShape) as? NextFire.Scheduled)?.at

    /**
     * The other reading of the sentence, chosen in the sheet (0.182.0): it takes the written one's
     * place — same id, so the undo and "Editar" find it — and the one it replaced becomes the
     * alternative, so a second tap takes the choice back. Shown at once; written right after.
     */
    fun useAlternative(chosen: VoiceAlternative) {
        // A sheet with something to look at twice stays open as long as somebody likes: by the
        // time the chip is tapped its moment may have gone, and a reading of the past is not one
        // to write. It leaves the offer instead.
        if (chosen.firesAt != null && chosen.firesAt <= clock.instant()) {
            _voice.update { current -> if (current is VoiceState.Created) current.copy(check = current.check.copy(alternatives = current.check.alternatives - chosen)) else current }
            return
        }
        var chose = false
        _voice.update { current -> current.choosing(chosen).also { chose = it !== current } }
        if (!chose) return
        viewModelScope.launch {
            repository.save(chosen.reminder.copy(updatedAt = clock.instant()))
            chosen.here?.let { seedHere(chosen.reminder, it) }
            noteVoice("alternative chosen: ${chosen.kind.name.lowercase()}")
        }
    }

    private fun toEditor(unsure: Heard.Unsure): VoiceOutcome {
        val why = when (unsure.why) {
            Heard.Why.NOTHING -> VoiceOutcome.Why.NOTHING
            Heard.Why.PARTIAL -> VoiceOutcome.Why.PARTIAL
            Heard.Why.NO_WORDS -> VoiceOutcome.Why.NO_WORDS
        }
        noteVoice("to the editor: ${why.name.lowercase()}")
        return VoiceOutcome.ToEditor(unsure.text, why)
    }

    /**
     * "Aquí" is a circle the phone is standing in, and the watch is told so: its first look leans
     * towards the side a doorway waits for, and a doorway out whose first look says "outside"
     * never sees the way out. Only that circle: a saved place's doorway beside it ("o al llegar a
     * casa") starts from whatever the watch sees, as one chosen in the editor does.
     */
    private suspend fun seedHere(reminder: Reminder, fix: Fix) {
        reminder.rules.forEachIndexed { index, rule ->
            val place = rule.trigger as? Trigger.Location ?: return@forEachIndexed
            val here = place.onCrossing && place.placeId == null && place.lat == fix.lat && place.lng == fix.lng
            if (here) rememberSide(GeofenceIds.encode(reminder.id, index, place), Transition.ENTER)
        }
    }

    /**
     * "Guardar" on Gemini's guess: written now, settled to now — a countdown starts from the tap,
     * not from the guess — and through the gate again, since the moment may have passed meanwhile.
     * Once: a second tap while it is writing is not a second reminder.
     */
    fun confirmVoice() {
        val shown = _voice.value as? VoiceState.Confirm ?: return
        if (voiceWork?.isActive == true) return
        voiceWork = viewModelScope.launch(Dispatchers.Default) {
            val settings = store.settings.first()
            val reminder = shown.spoken.toReminder(shown.reminder.id, clock.instant(), clock.zone, settings)
            val next = if (reminder == null) {
                VoiceInputEvent.Unwritten(shown.spoken.text, VoiceOutcome.Why.REFUSED, shown.form)
            } else {
                currentCoroutineContext().ensureActive()
                repository.save(reminder)
                shown.here?.let { seedHere(reminder, it) }
                noteVoice("gemini: confirmed")
                VoiceInputEvent.Written(reminder, shown.check.copy(firesAt = firesAt(reminder, settings)))
            }
            // Only onto the sheet it was asked from: one let go of meanwhile stays gone.
            _voice.compareAndSet(shown, voiceStep(shown, next))
        }
    }

    /** What [voicePreview] reads a dictation as, off the settings Home already holds. */
    private fun preview(stretches: List<String>): VoicePreview? {
        val settings = latestSettings.value ?: return null
        val repaired = repaired(stretches, clock.instant(), clock.zone, settings)
        return VoicePreview(repaired.heard, repaired.dropped, (hear(repaired.text, clock.instant(), clock.zone, settings) as? Heard.Read)?.spoken)
    }

    /** Undoing a dictated reminder: it goes. */
    fun undoSpoken(reminder: Reminder) {
        viewModelScope.launch { repository.delete(reminder.id) }
    }

    /** The shape of what happened and never the words: the diagnostics report holds no reminder's text. */
    private fun noteVoice(text: String) = Diag.note("voice", text)

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
        // it changes on its own, which is what makes a mark change back — paired with how tight
        // this phone's positions come, because `combine` takes five and these two are one
        // question: what the watch knows about where you are.
        combine(placeWatch, fixAccuracy) { watch, accuracy -> watch to accuracy },
    ) { reminders, current, tag, _, (watch, accuracy) ->
        // The ticks only say WHEN to rebuild; the moment is read fresh. startTick is a
        // StateFlow, and on every resubscription — the app coming back after five seconds
        // away — it replays its last value, the instant the ViewModel was made, possibly hours
        // ago: Home was built for a morning that had passed until the next minute tick.
        val cards = openCards()
        buildHomeState(
            reminders, current.defaultTime, clock.instant(), clock.zone, tag,
            current.dayStart, current.dayShape, accuracy,
            inside = { id, index -> insideOf(reminders, watch, id, index) },
            tagPrefs = current.tagPrefs,
            cardOpen = { id -> AlertNotifications.cardOpen(cards, id) },
        )
    }
        .flowOn(Dispatchers.Default)
        // A read that throws used to cancel the combine and leave the placeholder cards up for
        // ever, with nothing to press; it is a state now, with the diagnostics door (0.68.0).
        .catch { failure ->
            Log.e(TAG, "could not build Home", failure)
            emit(HomeUiState(loaded = true, failed = true))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /**
     * The quiet line under the hero (0.151.0): one per part of the day, the same one however
     * often Home is opened in it, and none at all while the switch is off, while something is
     * waiting for an answer — praise over an unanswered alarm is the app not listening
     * (`calmForCheer`, which the notification asks of the rows) — or when there is nothing true
     * and good to say. Worked out
     * off the main thread from the whole history, once a slot: never part of the per-minute
     * rebuild of [state].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val cheer: StateFlow<CheerPick?> = combine(
        settings.filterNotNull().map { it.cheerLine to it.dayParts }.distinctUntilChanged(),
        state.map { it.loaded && !it.failed && it.calm }.distinctUntilChanged(),
    ) { (on, parts), calm -> if (on && calm) parts else null }
        .flatMapLatest { parts ->
            if (parts == null) flowOf(null)
            else slots(parts).map { slot ->
                try {
                    cheerLine(slot)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    Log.w(TAG, "no line", failure)
                    null
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The part of the day, asked by the minute and said when it changes. */
    private fun slots(parts: DayParts) = flow {
        while (true) {
            emit(daySlot(clock.instant(), clock.zone, parts))
            delay(60_000L - Math.floorMod(clock.millis(), 60_000L))
        }
    }.distinctUntilChanged()

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
    // Over what is open, and only that (0.146.0): what was done has its own search, on the
    // Hechos screen, where it is the thing being looked for rather than the tail of a list of
    // things to do.
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

    /** From the tags offered under an empty search field: the same, for the app's own two chips. */
    fun selectTagAndClose(tag: TagFilter?) {
        selectedTag.value = tag
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

    /**
     * "Lo hice a su hora" from an overdue routine's row (0.157.0): the "hecho" dated to its
     * deadline ([ReminderFiring.doneOnTime]), said and undone exactly as [markDone] is.
     */
    fun markDoneOnTime(id: String) {
        viewModelScope.launch {
            val reminder = repository.get(id) ?: return@launch
            val dated = firing.doneOnTime(id, via = Door.HOME)
            if (dated.written) events.send(HomeEvent.Removed(HomeEvent.Removed.Kind.DONE, reminder, comesBackAt = comesBack(id), line = dated.line))
        }
    }

    /**
     * "Saltar la próxima": the same door as "hecho", because it *is* one — a dismissal given to
     * a recurring reminder that is not ringing spends its next round (`momentDealtWith`) and
     * the round after is next. The card still leaves and comes back on its own; only the word
     * differs, and the word is the whole point: pausing and remembering to come back was the
     * only way to miss one Tuesday of "cada martes".
     */
    fun skipNext(id: String) = removeAs(id, HomeEvent.Removed.Kind.SKIPPED)

    private fun removeAs(id: String, kind: HomeEvent.Removed.Kind) {
        viewModelScope.launch {
            val reminder = repository.get(id) ?: return@launch
            var history: List<FiringEvent> = emptyList()
            var comesBackAt: Instant? = null
            var line: Long? = null
            when (kind) {
                HomeEvent.Removed.Kind.DONE, HomeEvent.Removed.Kind.SKIPPED -> {
                    line = firing.dismiss(id, skip = kind == HomeEvent.Removed.Kind.SKIPPED, via = Door.HOME)
                    // Asked of the row the dismissal left rather than worked out again here:
                    // whether that was the end of it, and when it comes back if it was not, are
                    // [ReminderFiring]'s answers; the snackbar only reads them back.
                    comesBackAt = comesBack(id)
                }
                HomeEvent.Removed.Kind.DELETED -> {
                    history = repository.historyAsWritten(id)
                    repository.delete(id)
                }
            }
            val event = HomeEvent.Removed(kind, reminder, history, comesBackAt, line)
            if (kind == HomeEvent.Removed.Kind.DELETED) keepUndoable(event)
            events.send(event)
        }
    }

    /**
     * When [id] rings next, for a reminder a "hecho" left open — its recurrence's next round.
     * Null for one that is finished, and for one whose next answer is a place rather than a
     * moment ([dev.rwilco.model.moment]).
     */
    private suspend fun comesBack(id: String): Instant? {
        val after = repository.get(id)?.takeIf { it.status == Status.ACTIVE } ?: return null
        val current = store.settings.first()
        return nextFire(after, clock.instant(), clock.zone, current.defaultTime, current.dayStart, current.dayShape)?.moment
    }

    /**
     * **The last delete stays undoable for a minute, whatever the snackbar does** (0.67.0).
     *
     * A delete is the one thing on Home with no other way back: "hecho" is on the Hechos
     * screen, a pause is a tap, a snooze is on the card — a deleted row is gone. Its only door
     * was the snackbar, which lasts four seconds and is replaced by the next one; so a delete
     * followed by a "hecho" on the card under it, which is how a list gets cleared, destroyed
     * the first reminder with a vault restore as the only way back. This is that door held
     * open: Home draws a row at the top of the list for [UNDO_DELETE_MS] with the same undo the
     * snackbar had. The snackbar is untouched.
     */
    val pendingDelete: StateFlow<HomeEvent.Removed?> get() = _pendingDelete
    private val _pendingDelete = MutableStateFlow<HomeEvent.Removed?>(null)
    private var pendingDeleteTimer: Job? = null

    /**
     * A delete the editor did, handed here so it gets the same snackbar and the same minute as
     * one from a card (0.93.0): the editor's screen — and its scope — is gone by the time
     * either is wanted, and two undos with one row between them is how a reminder comes back
     * twice.
     */
    fun noteDeleted(reminder: Reminder, history: List<FiringEvent>) {
        val event = HomeEvent.Removed(HomeEvent.Removed.Kind.DELETED, reminder, history)
        keepUndoable(event)
        viewModelScope.launch { events.send(event) }
    }

    private fun keepUndoable(removed: HomeEvent.Removed) {
        pendingDeleteTimer?.cancel()
        _pendingDelete.value = removed
        pendingDeleteTimer = viewModelScope.launch {
            delay(UNDO_DELETE_MS)
            _pendingDelete.compareAndSet(removed, null)
        }
    }

    /**
     * The reminder exactly as it was: dealing with one now moves more than its status — the
     * anchor its recurrence counts from, a half-finished round, a snooze — and putting only the
     * status back would leave the clock wound forward.
     */
    fun undo(removed: HomeEvent.Removed) {
        // Undone from either door, the other closes: the row is about a delete that is no more.
        if (_pendingDelete.compareAndSet(removed, null)) pendingDeleteTimer?.cancel()
        viewModelScope.launch {
            repository.restore(removed.reminder, removed.history)
            // A "hecho" taken back takes its line in the history back too, as the shade's undo
            // always did (`undoDismiss`): left there, the statistics counted a hecho nobody gave.
            // That line by its id, never "the newest one": see [FiringEventDao.deleteLine].
            removed.line?.let { repository.forgetLine(removed.reminder.id, it) }
        }
    }

    /**
     * Said in a snackbar with an undo, like "hecho" and "borrar": a paused card goes grey and
     * slides to the bottom of the list, which from the middle of a scroll is a card that vanished.
     */
    fun togglePause(id: String, paused: Boolean) {
        viewModelScope.launch {
            val reminder = repository.get(id) ?: return@launch
            repository.setStatus(id, if (paused) Status.ACTIVE else Status.PAUSED)
            if (!paused) firing.paused(id)
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
    fun snooze(id: String, snooze: SnoozeOffer) {
        viewModelScope.launch {
            val reminder = repository.get(id) ?: return@launch
            val sideBefore = sideOf(reminder)
            val line = firing.snooze(id, snooze)
            // Gone between the two reads (dealt with from the shade): nothing to say, and
            // nothing an undo could put back.
            val after = repository.get(id) ?: return@launch
            events.send(HomeEvent.Snoozed(reminder, after.snoozedUntil, sideBefore = sideBefore, line = line))
        }
    }

    /** The watch's word on the circle [reminder] waits at, before that changes. */
    private suspend fun sideOf(reminder: Reminder): Boolean? =
        reminder.snoozedToPlace?.let { placeWatch.first().inside[GeofenceIds.encodeSnooze(reminder.id, it)] }

    /**
     * "Al llegar a casa" / "al salir de aquí" from a held card. The second first has to know
     * where here is; with nothing to draw the circle around it says so and writes nothing.
     */
    /** "A una fecha concreta": the same door, at a moment picked off a calendar. */
    fun snoozeUntil(id: String, until: Instant) {
        viewModelScope.launch {
            val reminder = repository.get(id) ?: return@launch
            val sideBefore = sideOf(reminder)
            val line = firing.snoozeUntil(id, until)
            val after = repository.get(id) ?: return@launch
            events.send(HomeEvent.Snoozed(reminder, after.snoozedUntil, sideBefore = sideBefore, line = line))
        }
    }

    fun snoozeToPlace(id: String, offer: SnoozePlace, hereLabel: String) {
        viewModelScope.launch {
            val reminder = repository.get(id) ?: return@launch
            val sideBefore = sideOf(reminder)
            val now = clock.instant()
            val (place, fix) = when (offer) {
                is SnoozePlace.Arrive -> offer.circle() to placeWatch.first().lastFix?.takeIf { it.speaksForHere(now) }
                is SnoozePlace.LeaveHere -> {
                    val fix = hereFix() ?: run { events.send(HomeEvent.NoFix); return@launch }
                    hereCircle(fix, hereLabel) to fix
                }
            }
            val line = firing.snoozeToPlace(id, place, fix, rememberSide)
            events.send(HomeEvent.Snoozed(reminder, until = null, place = place, sideBefore = sideBefore, line = line))
        }
    }

    fun cancelSnooze(id: String) {
        viewModelScope.launch {
            val reminder = repository.get(id) ?: return@launch
            val sideBefore = sideOf(reminder)
            firing.unsnooze(id)
            events.send(HomeEvent.Snoozed(reminder, until = null, cancelled = true, sideBefore = sideBefore))
        }
    }

    /** The snooze the row had before — none, usually — and the alarm follows the row. */
    fun undoSnooze(event: HomeEvent.Snoozed) {
        viewModelScope.launch {
            val reminder = event.reminder
            repository.snooze(reminder.id, reminder.snoozedUntil, reminder.snoozedToPlace)
            // And the line the snooze wrote, which the statistics would otherwise count — unless it
            // has rung since, which would turn one answered round into two unanswered rings.
            event.line?.let { repository.forgetLine(reminder.id, it, keepIfRangSince = true) }
            // The circle is back on the row; the watch is told the side it knew, the way the
            // snooze itself told it (see ReminderFiring.snoozeToPlace).
            val place = reminder.snoozedToPlace ?: return@launch
            val side = event.sideBefore ?: return@launch
            rememberSide(GeofenceIds.encodeSnooze(reminder.id, place), if (side) Transition.ENTER else Transition.EXIT)
        }
    }

    class Factory(private val app: RwilcoApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(
                app.repository,
                app.settingsStore,
                app.firing,
                app.settings,
                app.placeWatch.state,
                app.placeLog.log.map { it.typicalAccuracyM(app.clock.instant()) },
                app.clock,
                hereFix = { hereFix(app, app.placeWatch, app.clock.instant()) },
                locationAllowed = { app.hasBackgroundLocation() },
                rememberSide = app.placeWatcher::remember,
                openCards = { AlertNotifications.openCards(app) },
                cheerLine = { slot -> app.cheering.lineFor(slot, CheerText.variants(app.resources)) },
                geminiSetup = { app.geminiStore.read() },
                askGemini = { key, sentence, instruction, schema -> GeminiInput.ask(key, sentence, instruction, schema) },
                online = { app.isOnline() },
            ) as T
    }
}

/** How long a deleted reminder can be brought back from the row on Home; see [HomeViewModel.pendingDelete]. */
const val UNDO_DELETE_MS = 60_000L

private const val TAG = "HomeViewModel"

/**
 * How long dictation waits for Gemini (0.183.0): past it, Gemini has failed for this sentence. The
 * free tier usually answers in a second or two; a phone on a poor network should not hold a person
 * looking at "Mejorando la captura…" for the client's own ten seconds.
 */
private const val GEMINI_PATIENCE_MS = 8_000L
