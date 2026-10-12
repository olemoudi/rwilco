package dev.rwilco.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.rwilco.diag.Diag
import java.util.Locale

/** What a [Listener] says while somebody speaks. */
sealed interface VoiceEvent {
    data object Ready : VoiceEvent

    /** How loud, as the recognizer reports it (roughly -2 to 10 dB). */
    data class Level(val rms: Float) : VoiceEvent

    data class Partial(val text: String) : VoiceEvent

    /**
     * A stretch closed by a pause, as finally heard — empty when nothing final was — and the ear
     * listening on (0.186.0): a pause is somebody thinking, not somebody done.
     */
    data class Segment(val text: String, val alternatives: List<String> = emptyList()) : VoiceEvent

    /** The last stretch as finally heard, once [Listener.stop] was asked; empty when nothing was. */
    data class Final(val text: String, val alternatives: List<String> = emptyList()) : VoiceEvent

    /** One of `SpeechRecognizer.ERROR_*`. */
    data class Failed(val error: Int) : VoiceEvent
}

/**
 * The ear, behind an interface so a device test can speak to the sheet without a microphone.
 * Every call on the main thread, which is where Android's recognizer lives.
 */
interface Listener {
    /**
     * Listens until [stop] or [cancel], however long the pauses (0.186.0; the owner: the
     * dictation must not end by itself, so a sentence can be said calmly and corrected): each
     * stretch a pause closes comes as a [VoiceEvent.Segment], and the ear goes on listening.
     * [words] are the person's own, for the recognizer to lean towards where it can (0.189.0).
     */
    fun start(locale: Locale, words: List<String>, onEvent: (VoiceEvent) -> Unit)

    /** Stop listening: the stretch being said comes as the [VoiceEvent.Final], and that is the end. */
    fun stop()

    /** Stop and forget it. */
    fun cancel()
}

/** Where the sheet gets its ear; a test puts a fake one here. */
object VoiceInput {
    var factory: (Context) -> Listener = ::PlatformListener
}

/**
 * Android's own recognizer, **on the phone itself whenever it can be** (API 31+): nothing said
 * leaves the phone, and it works with no signal. When the phone's own model cannot take the
 * language — no pack installed for it yet — it is asked to fetch one (API 33+) and this sentence
 * goes to the ordinary recognizer instead, told to prefer staying offline; that is also the only
 * one there is below API 31.
 *
 * Android's recognizer hears one stretch and stops at the first pause. From API 33 it can be asked
 * to listen *through* the pauses itself — a segmented session, each stretch handed over as it
 * closes (0.188.0) — and that is asked first: nothing to start again, so nothing lost in the
 * moment it takes. A recognizer that does not do it simply stops at the pause, and then it is
 * started again straight away (0.186.0); one that refuses it is not asked again. Either way a
 * pause only closes a stretch. How it went is noted for the diagnostics report, never the words.
 */
class PlatformListener(private val context: Context) : Listener {
    private var recognizer: SpeechRecognizer? = null

    /**
     * The phone's own recognizer, kept after it was asked to fetch a language pack: the request
     * goes to the recognition service asynchronously, and destroying the recognizer straight
     * away could unbind it before the service has heard it. Let go when the ear closes
     * ([cancel]) — seconds of speech later, long after the service has heard it.
     */
    private var fetching: SpeechRecognizer? = null

    /**
     * The phone's own recognizer has no pack for the language: asked once, and every round after
     * it — "Seguir", a rotation — goes straight to the one that has it, rather than asking again.
     */
    private var noPack = false

    /** The recognizer failed a segmented session before it was even ready: rounds of one stretch from then on. */
    private var noSegments = false

    private val main = Handler(Looper.getMainLooper())
    private var onEvent: ((VoiceEvent) -> Unit)? = null

    /** [stop] was asked: the stretch being heard is the last. */
    private var finishing = false

    /** Whether a round is running now, rather than the ear between two of them. */
    private var hearing = false

    /** Whether this round's recognizer said it was ready: an error before that is about how it was asked. */
    private var ready = false
    private var startedAt = 0L

    /** Stretches in a row that ended at once with nothing in them: a recognizer that cannot listen, not a quiet room. */
    private var hollow = 0

    /** What this listening was like, for the diagnostics note: never the words. */
    private var how = How()

    /** Stretches this round handed over: a round with none is a hollow one, segmented or not. */
    private var roundSegments = 0

    /** The note for this listening was written: once is enough. */
    private var noted = false

    private class How(var onDevice: Boolean = false, var segments: Int = 0, var rounds: Int = 0, val errors: MutableList<Int> = mutableListOf())

    /** The person's own words, asked for on every round of this listening. */
    private var words: List<String> = emptyList()

    override fun start(locale: Locale, words: List<String>, onEvent: (VoiceEvent) -> Unit) {
        this.onEvent = onEvent
        this.words = words
        finishing = false
        hollow = 0
        noted = false
        val onDevice = !noPack && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        how = How(onDevice = onDevice)
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
            Diag.note("voice", "ear: no recognizer")
            onEvent(VoiceEvent.Failed(SpeechRecognizer.ERROR_CLIENT))
            return
        }
        listen(locale, onEvent, onDevice)
    }

    private fun listen(locale: Locale, onEvent: (VoiceEvent) -> Unit, onDevice: Boolean) {
        recognizer?.destroy()
        val ear = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = ear
        how.onDevice = onDevice
        val segmented = !noSegments && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val intent = intentFor(locale, preferOffline = !onDevice, segmented)
        ear.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                ready = true
                onEvent(VoiceEvent.Ready)
            }
            override fun onRmsChanged(rmsdB: Float) = onEvent(VoiceEvent.Level(rmsdB))
            override fun onPartialResults(partialResults: Bundle?) {
                firstOf(partialResults)?.let { onEvent(VoiceEvent.Partial(it)) }
            }

            // A segmented session (API 33): each stretch as a pause closes it, the session going
            // on, and its end once asked for — or once the recognizer decides it has listened long
            // enough, when a new one is started as after any stretch.
            override fun onSegmentResults(segmentResults: Bundle) {
                if (!hearing) return
                how.segments++
                roundSegments++
                onEvent(VoiceEvent.Segment(firstOf(segmentResults).orEmpty(), allOf(segmentResults)))
            }
            override fun onEndOfSegmentedSession() {
                if (!hearing) return
                hearing = false
                if (finishing) {
                    onEvent(VoiceEvent.Final(""))
                    noteHow("finished")
                } else {
                    // A session that ends at once with nothing handed over is as hollow as a round that does.
                    again(ear, intent, empty = roundSegments == 0, SpeechRecognizer.ERROR_NO_MATCH)
                }
            }

            override fun onResults(results: Bundle?) {
                // A stretch closes once: a recognizer that answers twice (results, then a stray
                // "no match") would otherwise start two rounds on top of each other.
                if (!hearing) return
                hearing = false
                // After a segmented round's stretches, a recognizer may still send the whole of it
                // as results: those words have all come already.
                val text = if (roundSegments > 0) "" else firstOf(results).orEmpty()
                if (finishing) {
                    onEvent(VoiceEvent.Final(text, allOf(results)))
                    noteHow("finished")
                } else {
                    onEvent(VoiceEvent.Segment(text, allOf(results)))
                    again(ear, intent, empty = text.isEmpty(), SpeechRecognizer.ERROR_NO_MATCH)
                }
            }
            override fun onError(error: Int) {
                if (!hearing) return
                hearing = false
                how.errors += error
                if (onDevice && error in LANGUAGE_ERRORS) {
                    // The phone's own model has no pack for this language yet: ask for one, and
                    // let this sentence go to the recognizer that has it.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) runCatching { ear.triggerModelDownload(intent) }
                    fetching?.destroy()
                    fetching = ear
                    noPack = true
                    recognizer = null
                    listen(locale, onEvent, onDevice = false)
                } else if (segmented && !ready && !finishing && how.rounds == 1 && error !in QUIET + PASSING + PERMISSION) {
                    // Refused on its very first round, before it was even ready, with something
                    // other than a hiccup or a quiet room: the way it was asked. Asked the old way
                    // from now on, by a recognizer of its own.
                    noSegments = true
                    main.postDelayed({ if (recognizer === ear && !finishing) listen(locale, onEvent, onDevice) }, PASSING_DELAY_MS)
                } else if (!finishing && error in QUIET) {
                    // Silence, or noise that was nobody speaking: somebody thinking. Listen on.
                    onEvent(VoiceEvent.Segment(""))
                    again(ear, intent, empty = true, error)
                } else if (!finishing && error in PASSING) {
                    // A recognizer asked again too soon, or a service that went away: a new one,
                    // a moment later. A dictation does not end over a hiccup.
                    onEvent(VoiceEvent.Segment(""))
                    again(ear, intent, empty = true, error) { listen(locale, onEvent, onDevice) }
                } else {
                    onEvent(VoiceEvent.Failed(error))
                    noteHow("failed")
                }
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        hear(ear, intent)
    }

    private fun hear(ear: SpeechRecognizer, intent: Intent) {
        hearing = true
        ready = false
        roundSegments = 0
        startedAt = SystemClock.elapsedRealtime()
        how.rounds++
        ear.startListening(intent)
    }

    /**
     * The next round, once the recognizer is out of its own callback. A recognizer that keeps
     * ending at once with nothing heard is broken, not listening to a quiet room: that is said,
     * rather than asked again for ever.
     */
    private fun again(ear: SpeechRecognizer, intent: Intent, empty: Boolean, error: Int, anew: (() -> Unit)? = null) {
        hollow = if (empty && SystemClock.elapsedRealtime() - startedAt < HOLLOW_MS) hollow + 1 else 0
        if (hollow >= MAX_HOLLOW) {
            onEvent?.invoke(VoiceEvent.Failed(if (error in QUIET) SpeechRecognizer.ERROR_CLIENT else error))
            noteHow("hollow")
            return
        }
        val next = Runnable { if (recognizer === ear && !finishing && !hearing) anew?.invoke() ?: hear(ear, intent) }
        if (anew == null) main.post(next) else main.postDelayed(next, PASSING_DELAY_MS)
    }

    override fun stop() {
        finishing = true
        // Between two rounds there is nothing to wait for: the last stretch has already come.
        if (hearing) {
            recognizer?.stopListening()
        } else {
            onEvent?.invoke(VoiceEvent.Final(""))
            noteHow("finished")
        }
    }

    override fun cancel() {
        if (onEvent != null && how.rounds > 0) noteHow("closed")
        main.removeCallbacksAndMessages(null)
        onEvent = null
        hearing = false
        recognizer?.destroy()
        recognizer = null
        fetching?.destroy()
        fetching = null
    }

    /**
     * How the listening went, in the shape the diagnostics report keeps: which recognizer, whether
     * it listened through the pauses itself (stretches handed over in one session) or was started
     * again after each, and the errors it said on the way. Never what was said.
     */
    private fun noteHow(end: String) {
        if (noted) return
        noted = true
        val mode = when {
            how.segments > 0 -> "segmented"
            noSegments || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "restarting"
            else -> "segments asked, none given"
        }
        Diag.note(
            "voice",
            "ear: ${if (how.onDevice) "on-device" else "service"}, $mode, ${how.rounds} round(s), ${how.segments} segment(s)" +
                (if (how.errors.isEmpty()) "" else ", errors ${how.errors.joinToString(",")}") + ", $end",
        )
    }

    private fun intentFor(locale: Locale, preferOffline: Boolean, segmented: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // A few readings of each stretch, for the one with the person's own words in it to win.
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_ALTERNATIVES)
        if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        if (words.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(words))
        }
        if (segmented && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Segmented by length: the session lasts at least this long, handing over a stretch at
            // each pause, and ends sooner only when asked to. The length is the historical long.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SEGMENTED_SESSION_MS)
        }
    }

    private fun firstOf(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    /** Every reading of a stretch the recognizer offered, best first. */
    private fun allOf(results: Bundle?): List<String> =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }

    private companion object {
        /** `ERROR_LANGUAGE_NOT_SUPPORTED` and `ERROR_LANGUAGE_UNAVAILABLE` (API 31). */
        val LANGUAGE_ERRORS = setOf(12, 13)

        /** `ERROR_SPEECH_TIMEOUT` and `ERROR_NO_MATCH`: nobody said anything. */
        val QUIET = setOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH)

        /**
         * `ERROR_CLIENT`, `ERROR_RECOGNIZER_BUSY` and `ERROR_SERVER_DISCONNECTED` (API 31): what
         * a recognizer started again straight after a stretch says now and then, and gets over.
         */
        val PASSING = setOf(SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, 11)

        /** `ERROR_INSUFFICIENT_PERMISSIONS`: the microphone, not the way it was asked. */
        val PERMISSION = setOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)

        /** How many readings of a stretch are asked for. */
        const val MAX_ALTERNATIVES = 5

        /** How long before a new recognizer after one of those. */
        const val PASSING_DELAY_MS = 300L

        /** A stretch that ends with nothing in it sooner than this ended at once. */
        const val HOLLOW_MS = 800L

        /** How many of those in a row are a broken recognizer. */
        const val MAX_HOLLOW = 5

        /**
         * How long a segmented session is asked to last: longer than any dictation, since the
         * sheet itself pauses the microphone after half a minute with nothing new said.
         */
        const val SEGMENTED_SESSION_MS = 300_000L
    }
}
