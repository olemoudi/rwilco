package dev.rwilco.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
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

    /** The sentence as finally heard; empty when nothing was. */
    data class Final(val text: String) : VoiceEvent

    /** One of `SpeechRecognizer.ERROR_*`. */
    data class Failed(val error: Int) : VoiceEvent
}

/**
 * The ear, behind an interface so a device test can speak to the sheet without a microphone.
 * Every call on the main thread, which is where Android's recognizer lives.
 */
interface Listener {
    fun start(locale: Locale, onEvent: (VoiceEvent) -> Unit)

    /** Stop listening: what was said so far is the sentence. */
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
 */
class PlatformListener(private val context: Context) : Listener {
    private var recognizer: SpeechRecognizer? = null

    /**
     * The phone's own recognizer, kept after it was asked to fetch a language pack: the request
     * goes to the recognition service asynchronously, and destroying the recognizer straight
     * away could unbind it before the service has heard it. Let go with the sheet ([cancel]).
     */
    private var fetching: SpeechRecognizer? = null

    override fun start(locale: Locale, onEvent: (VoiceEvent) -> Unit) {
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
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
            override fun onResults(results: Bundle?) = onEvent(VoiceEvent.Final(firstOf(results).orEmpty()))
            override fun onError(error: Int) {
                if (onDevice && error in LANGUAGE_ERRORS) {
                    // The phone's own model has no pack for this language yet: ask for one, and
                    // let this sentence go to the recognizer that has it.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) runCatching { ear.triggerModelDownload(intent) }
                    fetching?.destroy()
                    fetching = ear
                    recognizer = null
                    listen(locale, onEvent, onDevice = false)
                } else {
                    onEvent(VoiceEvent.Failed(error))
                }
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        ear.startListening(intent)
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
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
    }
}
