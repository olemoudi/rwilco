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
    data class Segment(val text: String) : VoiceEvent

    /** The last stretch as finally heard, once [Listener.stop] was asked; empty when nothing was. */
    data class Final(val text: String) : VoiceEvent

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
     */
    fun start(locale: Locale, onEvent: (VoiceEvent) -> Unit)

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
 * Android's recognizer hears one stretch and stops at the first pause; this one starts it again
 * straight away (0.186.0), so a pause only closes a stretch and nobody has to say it all in one
 * breath. Words said in the moment it takes to start again can be lost, which is why the sheet
 * shows what it has heard as it hears it.
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

    private val main = Handler(Looper.getMainLooper())
    private var onEvent: ((VoiceEvent) -> Unit)? = null

    /** [stop] was asked: the stretch being heard is the last. */
    private var finishing = false

    /** Whether a stretch is being heard now, rather than the ear between two of them. */
    private var hearing = false
    private var startedAt = 0L

    /** Stretches in a row that ended at once with nothing in them: a recognizer that cannot listen, not a quiet room. */
    private var hollow = 0

    override fun start(locale: Locale, onEvent: (VoiceEvent) -> Unit) {
        this.onEvent = onEvent
        finishing = false
        hollow = 0
        val onDevice = !noPack && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
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
        val intent = intentFor(locale, preferOffline = !onDevice)
        ear.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onEvent(VoiceEvent.Ready)
            override fun onRmsChanged(rmsdB: Float) = onEvent(VoiceEvent.Level(rmsdB))
            override fun onPartialResults(partialResults: Bundle?) {
                firstOf(partialResults)?.let { onEvent(VoiceEvent.Partial(it)) }
            }
            override fun onResults(results: Bundle?) {
                // A stretch closes once: a recognizer that answers twice (results, then a stray
                // "no match") would otherwise start two rounds on top of each other.
                if (!hearing) return
                hearing = false
                val text = firstOf(results).orEmpty()
                if (finishing) {
                    onEvent(VoiceEvent.Final(text))
                } else {
                    onEvent(VoiceEvent.Segment(text))
                    again(ear, intent, empty = text.isEmpty(), SpeechRecognizer.ERROR_NO_MATCH)
                }
            }
            override fun onError(error: Int) {
                if (!hearing) return
                hearing = false
                if (onDevice && error in LANGUAGE_ERRORS) {
                    // The phone's own model has no pack for this language yet: ask for one, and
                    // let this sentence go to the recognizer that has it.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) runCatching { ear.triggerModelDownload(intent) }
                    fetching?.destroy()
                    fetching = ear
                    noPack = true
                    recognizer = null
                    listen(locale, onEvent, onDevice = false)
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
        startedAt = SystemClock.elapsedRealtime()
        ear.startListening(intent)
    }

    /**
     * The next stretch, once the recognizer is out of its own callback. A recognizer that keeps
     * ending at once with nothing heard is broken, not listening to a quiet room: that is said,
     * rather than asked again for ever.
     */
    private fun again(ear: SpeechRecognizer, intent: Intent, empty: Boolean, error: Int, anew: (() -> Unit)? = null) {
        hollow = if (empty && SystemClock.elapsedRealtime() - startedAt < HOLLOW_MS) hollow + 1 else 0
        if (hollow >= MAX_HOLLOW) {
            onEvent?.invoke(VoiceEvent.Failed(if (error in QUIET) SpeechRecognizer.ERROR_CLIENT else error))
            return
        }
        val next = Runnable { if (recognizer === ear && !finishing && !hearing) anew?.invoke() ?: hear(ear, intent) }
        if (anew == null) main.post(next) else main.postDelayed(next, PASSING_DELAY_MS)
    }

    override fun stop() {
        finishing = true
        // Between two stretches there is nothing to wait for: the last one has already come.
        if (hearing) recognizer?.stopListening() else onEvent?.invoke(VoiceEvent.Final(""))
    }

    override fun cancel() {
        main.removeCallbacksAndMessages(null)
        onEvent = null
        hearing = false
        recognizer?.destroy()
        recognizer = null
        fetching?.destroy()
        fetching = null
    }

    private fun intentFor(locale: Locale, preferOffline: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    private fun firstOf(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

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

        /** How long before a new recognizer after one of those. */
        const val PASSING_DELAY_MS = 300L

        /** A stretch that ends with nothing in it sooner than this ended at once. */
        const val HOLLOW_MS = 800L

        /** How many of those in a row are a broken recognizer. */
        const val MAX_HOLLOW = 5
    }
}
