package dev.rwilco.voice

import dev.rwilco.model.Fix
import dev.rwilco.model.HeardSpan
import dev.rwilco.model.Reminder
import dev.rwilco.model.Spoken
import dev.rwilco.model.TriggerFamily
import dev.rwilco.model.fold
import java.time.Instant

/**
 * Where the dictation sheet is. A pure step function drives it ([voiceStep]); Home's ViewModel
 * holds it, so a rotation keeps "Creado" and its undo (and the sentence being thought about,
 * which is not thought about twice), and a process brought back from the dead does not open a
 * microphone by itself. The sheet only draws it.
 */
sealed interface VoiceState {
    /** Waiting on the microphone permission. */
    data object Asking : VoiceState

    /**
     * Listening, until "Terminado" and never by itself (0.186.0): the stretch being said
     * ([partial]), the ones a pause already closed ([said]), and how loud it is (0..1).
     * [finishing] once "Terminado" has asked the ear for its last stretch; [paused] once a long
     * silence, or the app left behind, closed the microphone — the words kept, waiting for
     * "Seguir" or "Terminado".
     */
    data class Listening(
        val partial: String = "",
        val level: Float = 0f,
        val said: List<String> = emptyList(),
        val finishing: Boolean = false,
        val paused: Boolean = false,
    ) : VoiceState {
        /** Everything heard so far, stretch by stretch. */
        val stretches: List<String> get() = (said + partial).map { it.trim() }.filter { it.isNotEmpty() }

        /** Everything heard so far, as one sentence. */
        val transcript: String get() = stretches.joinToString(" ")
    }

    /**
     * The sentence is in, stretch by stretch as the pauses left it, and being turned into a
     * reminder; [asking] while Gemini is asked (0.178.0).
     */
    data class Thinking(val stretches: List<String>, val asking: Boolean = false) : VoiceState {
        val text: String get() = stretches.joinToString(" ")
    }

    /**
     * What Gemini made of it, not written yet: a guess is shown and confirmed, never written
     * unseen (the owner's call). [form] is the same reading for the editor, if "Editar".
     */
    data class Confirm(val reminder: Reminder, val form: String, val spoken: Spoken, val here: Fix?, val check: VoiceCheck = VoiceCheck()) : VoiceState

    /** Written: shown with what it was read from, its undo, and what else it could have been ([check]). */
    data class Created(val reminder: Reminder, val check: VoiceCheck = VoiceCheck()) : VoiceState

    /** Not written: the sheet goes, and the words go to the editor with the line that says why — or the whole reading ([form]). */
    data class ToEditor(val text: String, val why: VoiceOutcome.Why, val form: String? = null) : VoiceState

    data class Failed(val why: VoiceFailure, val partial: String = "") : VoiceState
}

enum class VoiceFailure {
    /** The microphone permission was refused. */
    NO_PERMISSION,

    /** Nothing was heard. */
    NOT_HEARD,

    /** No recognizer on this phone, or it broke. */
    BROKEN,
}

/** What can happen to the sheet: the listener's events, the permission's answer, and what the sentence came to. */
sealed interface VoiceInputEvent {
    data class Permission(val granted: Boolean) : VoiceInputEvent
    data class Heard(val event: VoiceEvent) : VoiceInputEvent
    data class Written(val reminder: Reminder, val check: VoiceCheck = VoiceCheck()) : VoiceInputEvent
    data class Proposed(val reminder: Reminder, val form: String, val spoken: Spoken, val here: Fix?, val check: VoiceCheck = VoiceCheck()) : VoiceInputEvent
    data class Unwritten(val text: String, val why: VoiceOutcome.Why, val form: String? = null) : VoiceInputEvent

    /** Again, from the top. */
    data object Retry : VoiceInputEvent

    /**
     * "Terminado" (0.186.0): the ear is asked for its last stretch. Asked again — a second tap,
     * or an ear that never answered — what has been heard is the sentence.
     */
    data object Finish : VoiceInputEvent

    /** A long silence, or the app left behind: the microphone closes and the words stay. */
    data object Pause : VoiceInputEvent

    /** "Seguir": the microphone opens again after a pause, the words still there. */
    data object Resume : VoiceInputEvent
}

/**
 * The sheet's next state. Events that arrive late — a level after the sentence is in, a result
 * after a failure, anything from an ear already closed by a pause — change nothing: the
 * recognizer does not stop talking on cue.
 */
fun voiceStep(state: VoiceState, event: VoiceInputEvent): VoiceState = when (event) {
    is VoiceInputEvent.Permission ->
        if (state !is VoiceState.Asking) state else if (event.granted) VoiceState.Listening() else VoiceState.Failed(VoiceFailure.NO_PERMISSION)
    VoiceInputEvent.Retry -> VoiceState.Listening()
    VoiceInputEvent.Finish -> when {
        state !is VoiceState.Listening -> state
        state.paused || state.finishing -> settled(state.stretches)
        else -> state.copy(finishing = true)
    }
    VoiceInputEvent.Pause -> when {
        state !is VoiceState.Listening || state.paused -> state
        // Already asked to end: it ends, with what there is.
        state.finishing -> settled(state.stretches)
        state.stretches.isEmpty() -> VoiceState.Failed(VoiceFailure.NOT_HEARD)
        else -> VoiceState.Listening(said = state.stretches, paused = true)
    }
    VoiceInputEvent.Resume -> if (state is VoiceState.Listening && state.paused) state.copy(paused = false) else state
    // Written from the sentence, or from Gemini's guess once confirmed.
    is VoiceInputEvent.Written -> if (state is VoiceState.Thinking || state is VoiceState.Confirm) VoiceState.Created(event.reminder, event.check) else state
    is VoiceInputEvent.Proposed -> if (state is VoiceState.Thinking) VoiceState.Confirm(event.reminder, event.form, event.spoken, event.here, event.check) else state
    is VoiceInputEvent.Unwritten -> if (state is VoiceState.Thinking || state is VoiceState.Confirm) VoiceState.ToEditor(event.text, event.why, event.form) else state
    is VoiceInputEvent.Heard -> if (state !is VoiceState.Listening || state.paused) state else when (val heard = event.event) {
        // A fresh ear (after a rotation took the old one) starts a stretch of its own: the one
        // the old ear was in the middle of is kept as it was heard.
        VoiceEvent.Ready -> if (state.partial.isBlank()) state else state.copy(said = state.stretches, partial = "")
        is VoiceEvent.Level -> state.copy(level = levelOf(heard.rms))
        is VoiceEvent.Partial -> state.copy(partial = heard.text)
        // Some recognizers end with an empty final after a perfectly good partial.
        is VoiceEvent.Segment -> state.copy(said = (state.said + heard.text.ifBlank { state.partial }).filter { it.isNotBlank() }, partial = "")
        is VoiceEvent.Final -> settled(state.said + heard.text.ifBlank { state.partial })
        is VoiceEvent.Failed -> when {
            // "Terminado" was pressed: whatever the ear says on its way out, the words are the sentence.
            state.finishing -> settled(state.stretches)
            // ERROR_SPEECH_TIMEOUT and ERROR_NO_MATCH: silence, or noise that was nobody speaking.
            heard.error == 6 || heard.error == 7 -> settled(state.stretches)
            // ERROR_INSUFFICIENT_PERMISSIONS.
            heard.error == 9 -> VoiceState.Failed(VoiceFailure.NO_PERMISSION, state.transcript)
            else -> VoiceState.Failed(VoiceFailure.BROKEN, state.transcript)
        }
    }
}

/** The sentence, when anything was heard. */
private fun settled(stretches: List<String>): VoiceState {
    val heard = stretches.map { it.trim() }.filter { it.isNotEmpty() }
    return if (heard.isEmpty()) VoiceState.Failed(VoiceFailure.NOT_HEARD) else VoiceState.Thinking(heard)
}

/**
 * What the sheet shows so a written reminder can be checked against what was said (0.182.0): the
 * sentence as heard and where in it each "when" was ([parts]), the words that sound like a "when"
 * nobody read ([doubts]), when it first rings ([firesAt], when that is a moment), and the other
 * readings one tap away ([alternatives]).
 */
data class VoiceCheck(
    val heard: String = "",
    val parts: List<HeardSpan> = emptyList(),
    val doubts: List<IntRange> = emptyList(),
    val firesAt: Instant? = null,
    val alternatives: List<VoiceAlternative> = emptyList(),
    /** Where Gemini's second look at a doubtful reading is (0.183.0). */
    val refining: Refining = Refining.NONE,
    /** The position "aquí" was drawn round in the reading shown, for the watch to be told if it comes back. */
    val here: Fix? = null,
) {
    /**
     * Whether the reading shown is the grammar's own, which [parts] and [doubts] describe: once
     * Gemini's has taken its place they are about a reading that is no longer the one on screen.
     */
    val readsAsSaid: Boolean get() = alternatives.none { it.kind == VoiceAlternative.Kind.ORIGINAL }

    /**
     * Nothing to look at twice, so the sheet may go by itself (the owner's call): a doubt or an
     * alternative keeps it until somebody says "Listo", and so does Gemini still looking.
     */
    val clear: Boolean get() = doubts.isEmpty() && alternatives.isEmpty() && refining != Refining.ASKING

    /**
     * [chosen] has taken [current]'s place: it leaves the alternatives and [current] joins them,
     * so the choice can be taken back the same way it was made. Gemini's reading chosen leaves the
     * grammar's as the original; the original chosen back leaves Gemini's offered again.
     */
    fun swapped(current: Reminder, chosen: VoiceAlternative): VoiceCheck = copy(
        firesAt = chosen.firesAt,
        here = chosen.here,
        alternatives = alternatives.map { if (it == chosen) VoiceAlternative(current, firesAt, chosen.kind.replaced, here) else it },
    )

    /**
     * What Gemini's second look at a doubtful reading comes to (0.183.0). The reminder was written
     * before it was asked — Gemini can be slow or not answer at all, and nothing waits for it — so
     * this only ever *offers*: the same reading means the doubt was nothing ([Refining.AGREED]); a
     * different one becomes the first alternative, never applied unseen ([Refining.OFFERED]); no
     * reading, or none in time, leaves everything as it was ([Refining.FAILED]).
     */
    fun refined(shown: Reminder, gemini: VoiceAlternative?): VoiceCheck = when {
        gemini == null -> copy(refining = Refining.FAILED)
        sameReading(shown, gemini.reminder) -> copy(refining = Refining.AGREED, doubts = emptyList())
        else -> {
            // Gemini's reading may be one already offered (the other half of an open hour): then
            // it is that one, promoted, not the same thing twice.
            val known = alternatives.firstOrNull { sameReading(it.reminder, gemini.reminder) }
            val offer = (known ?: gemini).copy(kind = VoiceAlternative.Kind.GEMINI)
            copy(refining = Refining.OFFERED, alternatives = listOf(offer) + alternatives.filter { it != known })
        }
    }
}

/** Gemini's second look at a reading the grammar had doubts about. */
enum class Refining { NONE, ASKING, AGREED, OFFERED, FAILED }

/*
 * The changes to a written reminder's sheet that do not come from the ear — Gemini's answer, a
 * reading chosen, the sheet let go of — as pure steps, so the ViewModel applies each atomically
 * to whatever the sheet is *now* (`MutableStateFlow.update`): a Gemini answer that arrives on its
 * own thread must neither bring back a sheet closed meanwhile nor undo a choice made meanwhile.
 */

/** Gemini's answer about the reminder [id], laid on the sheet only while it still shows that reminder. */
fun VoiceState?.refinedWith(id: String, offer: VoiceAlternative?): VoiceState? =
    if (this is VoiceState.Created && reminder.id == id) copy(check = check.refined(reminder, offer)) else this

/** [chosen] in place of the shown reading — only while it is still on offer on the sheet. */
fun VoiceState?.choosing(chosen: VoiceAlternative): VoiceState? =
    if (this is VoiceState.Created && chosen in check.alternatives) VoiceState.Created(chosen.reminder, check.swapped(reminder, chosen)) else this

/** The sheet is leaving, so Gemini's look is over: a sheet that comes back (a rotation mid-hide) shows no endless "mejorando". */
fun VoiceState?.refiningStopped(): VoiceState? =
    if (this is VoiceState.Created && check.refining == Refining.ASKING) copy(check = check.copy(refining = Refining.FAILED)) else this

/**
 * Whether two readings of a sentence say the same thing: the same words (case, accents and
 * punctuation aside — Gemini may end with a full stop) and the same rules, set, recurrence,
 * deadline and ways of being told.
 */
fun sameReading(a: Reminder, b: Reminder): Boolean =
    wordsOf(a.text) == wordsOf(b.text) && a.rules == b.rules && a.ruleMatch == b.ruleMatch &&
        a.recurrence == b.recurrence && a.deadline == b.deadline && a.actions == b.actions

private fun wordsOf(text: String): String = fold(text).map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

/** One stretch of the sentence as heard, and what the sheet colours it as. */
data class HeardMark(val range: IntRange, val kind: Kind, val family: TriggerFamily? = null) {
    enum class Kind {
        /** How it was said, not when: "recuérdame", a join, "con pantalla completa". Dimmed. */
        MANNER,

        /** A "when", in its family's colour. */
        WHEN,

        /** Left in the words and sounding like a "when" nobody read. */
        DOUBT,
    }
}

/**
 * The marks to lay over [VoiceCheck.heard], in the order they are laid: how it was said first,
 * then the "when"s, then the doubts — so where two meet, the one that asks to be looked at shows.
 * Clamped to the sentence: offsets that run past it (a sentence heard again) are cut, never thrown.
 */
fun VoiceCheck.marks(): List<HeardMark> {
    fun IntRange.clamped(): IntRange? = (first.coerceAtLeast(0)..last.coerceAtMost(heard.length - 1)).takeUnless { it.isEmpty() }
    val manner = parts.filter { it.family == null }.mapNotNull { part -> part.range.clamped()?.let { HeardMark(it, HeardMark.Kind.MANNER) } }
    val whens = parts.filter { it.family != null }.mapNotNull { part -> part.range.clamped()?.let { HeardMark(it, HeardMark.Kind.WHEN, part.family) } }
    val doubtful = doubts.mapNotNull { range -> range.clamped()?.let { HeardMark(it, HeardMark.Kind.DOUBT) } }
    return manner + whens + doubtful
}

/** The doubtful words themselves, as heard, for the line that names them. */
fun VoiceCheck.doubtWords(): List<String> = marks().filter { it.kind == HeardMark.Kind.DOUBT }.map { heard.substring(it.range.first, it.range.last + 1) }.distinct()

/**
 * Another reading of the same sentence, written under the same id so it can take the shown one's
 * place. [here] is the position an "aquí" in it was drawn round, for the watch to be told.
 */
data class VoiceAlternative(val reminder: Reminder, val firesAt: Instant?, val kind: Kind = Kind.OTHER_HALF, val here: Fix? = null) {
    enum class Kind {
        /** The other half of an open hour ("a las 8": tonight, or tomorrow morning). */
        OTHER_HALF,

        /** Gemini's reading of a sentence the grammar had doubts about. */
        GEMINI,

        /** The grammar's own reading, once Gemini's has taken its place. */
        ORIGINAL;

        /** What the shown reading becomes when one of this kind takes its place. */
        val replaced: Kind
            get() = when (this) {
                OTHER_HALF -> OTHER_HALF
                GEMINI -> ORIGINAL
                ORIGINAL -> GEMINI
            }
    }
}

/** The recognizer's dB, which runs from about -2 to 10, as 0..1 for the mic to grow by. */
fun levelOf(rms: Float): Float = ((rms + 2f) / 12f).coerceIn(0f, 1f)

/** What a sentence said to the mic came to. */
sealed interface VoiceOutcome {
    data class Created(val reminder: Reminder, val check: VoiceCheck = VoiceCheck()) : VoiceOutcome

    /**
     * Gemini's guess, written once somebody says so: what it was heard as and the position
     * "aquí" was drawn round travel with it, so "Guardar" settles the same reading at the tap.
     */
    data class Proposed(val reminder: Reminder, val form: String, val spoken: Spoken, val here: Fix?, val check: VoiceCheck = VoiceCheck()) : VoiceOutcome

    /**
     * Not written: the words go to the editor, and [why] is the line that says so. [form] is the
     * whole reading, for one understood but refused: the editor opens on it, warning and all.
     */
    data class ToEditor(val text: String, val why: Why, val form: String? = null) : VoiceOutcome

    enum class Why {
        /** No "when" understood. */
        NOTHING,

        /** More "when" than was understood. */
        PARTIAL,

        /** Only a "when", nothing to remember. */
        NO_WORDS,

        /** "Aquí", and no position to draw it around. */
        NO_FIX,

        /** Understood, but it would not ring as said: the editor shows why. */
        REFUSED,

        /** Gemini would not take the key: Settings is where that is fixed. */
        GEMINI_KEY,

        /** Gemini was asked and gave no answer: offline, too slow, too many asks. */
        GEMINI_FAILED,
    }
}
