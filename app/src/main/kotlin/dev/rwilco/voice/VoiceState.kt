package dev.rwilco.voice

import dev.rwilco.model.Reminder

/**
 * Where the dictation sheet is. A pure step function drives it ([voiceStep]); Home's ViewModel
 * holds it, so a rotation keeps "Creado" and its undo (and the sentence being thought about,
 * which is not thought about twice), and a process brought back from the dead does not open a
 * microphone by itself. The sheet only draws it.
 */
sealed interface VoiceState {
    /** Waiting on the microphone permission. */
    data object Asking : VoiceState

    /** Listening: what has been heard so far, and how loud it is (0..1). */
    data class Listening(val partial: String = "", val level: Float = 0f) : VoiceState

    /** The sentence is in, and being turned into a reminder. */
    data class Thinking(val text: String) : VoiceState

    /** Written: shown, with its undo, for a moment. */
    data class Created(val reminder: Reminder) : VoiceState

    /** Not written: the sheet goes, and the words go to the editor with the line that says why. */
    data class ToEditor(val text: String, val why: VoiceOutcome.Why) : VoiceState

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
    data class Written(val reminder: Reminder) : VoiceInputEvent
    data class Unwritten(val text: String, val why: VoiceOutcome.Why) : VoiceInputEvent

    /** Again, from the top. */
    data object Retry : VoiceInputEvent
}

/**
 * The sheet's next state. Events that arrive late — a level after the sentence is in, a result
 * after a failure — change nothing: the recognizer does not stop talking on cue.
 */
fun voiceStep(state: VoiceState, event: VoiceInputEvent): VoiceState = when (event) {
    is VoiceInputEvent.Permission ->
        if (state !is VoiceState.Asking) state else if (event.granted) VoiceState.Listening() else VoiceState.Failed(VoiceFailure.NO_PERMISSION)
    VoiceInputEvent.Retry -> VoiceState.Listening()
    is VoiceInputEvent.Written -> if (state is VoiceState.Thinking) VoiceState.Created(event.reminder) else state
    is VoiceInputEvent.Unwritten -> if (state is VoiceState.Thinking) VoiceState.ToEditor(event.text, event.why) else state
    is VoiceInputEvent.Heard -> if (state !is VoiceState.Listening) state else when (val heard = event.event) {
        VoiceEvent.Ready -> state
        is VoiceEvent.Level -> state.copy(level = levelOf(heard.rms))
        is VoiceEvent.Partial -> state.copy(partial = heard.text)
        is VoiceEvent.Final -> {
            // Some recognizers end with an empty final after a perfectly good partial.
            val text = heard.text.ifBlank { state.partial }.trim()
            if (text.isEmpty()) VoiceState.Failed(VoiceFailure.NOT_HEARD) else VoiceState.Thinking(text)
        }
        is VoiceEvent.Failed -> when (heard.error) {
            // ERROR_SPEECH_TIMEOUT and ERROR_NO_MATCH: silence, or noise that was nobody speaking.
            6, 7 -> if (state.partial.isNotBlank()) VoiceState.Thinking(state.partial.trim()) else VoiceState.Failed(VoiceFailure.NOT_HEARD)
            // ERROR_INSUFFICIENT_PERMISSIONS.
            9 -> VoiceState.Failed(VoiceFailure.NO_PERMISSION, state.partial)
            else -> VoiceState.Failed(VoiceFailure.BROKEN, state.partial)
        }
    }
}

/** The recognizer's dB, which runs from about -2 to 10, as 0..1 for the mic to grow by. */
fun levelOf(rms: Float): Float = ((rms + 2f) / 12f).coerceIn(0f, 1f)

/** What a sentence said to the mic came to. */
sealed interface VoiceOutcome {
    data class Created(val reminder: Reminder) : VoiceOutcome

    /** Not written: the words go to the editor, and [why] is the line that says so. */
    data class ToEditor(val text: String, val why: Why) : VoiceOutcome

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
    }
}
