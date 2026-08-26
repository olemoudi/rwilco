package dev.rwilco.ui.settings

import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import dev.rwilco.model.AlertSound
import dev.rwilco.notify.AlertAudio
import dev.rwilco.notify.Sounds

/**
 * The chime, so a sound can be chosen by ear rather than by name — once, or as the round the
 * insistent one plays.
 *
 * At alarm usage on purpose: a preview at notification volume on a phone whose notification
 * volume is at zero is a button that appears to do nothing, and the point of the button is to
 * tell you what the alarm will sound like.
 */
/** What the preview is doing, so one button can be both "reproducir" and "parar". */
enum class PreviewMode { ONCE, LOOPING, ROUND }

class SoundPreview(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var onRunning: ((PreviewMode?) -> Unit)? = null

    /** The chime, once. */
    fun play(sound: AlertSound, toHeadphones: Boolean = true, onRunning: (PreviewMode?) -> Unit = {}) {
        begin(PreviewMode.ONCE, onRunning)
        start(sound, toHeadphones, looping = false) { stop() }
    }

    /**
     * The sound as the full-screen alert makes it: round and round until somebody answers.
     *
     * Capped all the same. A preview is somebody finding out what it is like, and a loop left
     * running because they walked away is the app doing to them exactly what it promises never
     * to do — the alert screen itself gives up after two minutes for the same reason.
     */
    fun playLooping(sound: AlertSound, toHeadphones: Boolean = true, onRunning: (PreviewMode?) -> Unit = {}) {
        begin(PreviewMode.LOOPING, onRunning)
        start(sound, toHeadphones, looping = true) {}
        handler.postDelayed(::stop, LOOP_LIMIT_MS)
    }

    /**
     * The insistent sound, rehearsed: the same tone as many times as it would really come back,
     * with the waits between shortened to something somebody will sit through. Nobody is going
     * to hold the phone for five minutes to find out what "hasta que lo atienda" is like, and
     * the thing worth knowing about it is the shape — how many times, and that it keeps coming
     * — not the wait. The card says the real one underneath.
     */
    fun playRound(sound: AlertSound, times: Int, gapMs: Long, toHeadphones: Boolean = true, onRunning: (PreviewMode?) -> Unit = {}) {
        begin(PreviewMode.ROUND, onRunning)
        var left = times.coerceAtLeast(1)
        fun again() {
            if (left <= 0) {
                stop()
                return
            }
            left--
            start(sound, toHeadphones, looping = false) { handler.postDelayed(::again, gapMs) }
        }
        again()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        runCatching {
            player?.apply {
                if (isPlaying) stop()
                release()
            }
        }
        player = null
        AlertAudio.release(context, focus)
        focus = null
        onRunning?.invoke(null)
        onRunning = null
    }

    private fun begin(mode: PreviewMode, onRunning: (PreviewMode?) -> Unit) {
        stop()
        this.onRunning = onRunning
        onRunning(mode)
        // The preview ducks and routes exactly as the alert does, or it is a preview of
        // something else: the whole point of pressing it is to hear what will happen.
        focus = AlertAudio.duckOthers(context)
    }

    private fun start(sound: AlertSound, toHeadphones: Boolean, looping: Boolean, onDone: () -> Unit) {
        val uri = Sounds.uri(context, sound) ?: return onDone()
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(AlertAudio.attributes())
                AlertAudio.routeTo(context, this, toHeadphones)
                isLooping = looping
                // Let go of it as it finishes: a preview that has ended is a player nobody owns.
                if (!looping) {
                    setOnCompletionListener {
                        runCatching { release() }
                        if (player === this) player = null
                        onDone()
                    }
                }
                prepare()
                start()
            }
        }.onFailure {
            Log.w(TAG, "could not play the preview", it)
            onDone()
        }
    }

    private companion object {
        const val TAG = "RwilcoAlarms"

        /** As long as a preview of a looping sound may run before it stops itself. */
        const val LOOP_LIMIT_MS = 30_000L
    }
}

/**
 * A file somebody picked, kept: lasting read permission taken, and a name to call it by.
 *
 * The permission is the whole of it. A Uri from the picker is readable for as long as the app
 * is in the foreground and no longer, and an alarm three days from now is neither — so without
 * this the sound would work while it was being chosen and be silent when it mattered. Null when
 * the phone will not grant it, which is the only honest thing to do with a sound that cannot be
 * played later.
 */
fun Context.rememberSound(uri: Uri): AlertSound.Custom? {
    val taken = runCatching {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.isSuccess
    if (!taken || !Sounds.readable(this, uri)) {
        Log.w("RwilcoAlarms", "no lasting permission for $uri; not keeping it")
        return null
    }
    return AlertSound.Custom(uri.toString(), displayName(uri) ?: uri.lastPathSegment.orEmpty())
}

private fun Context.displayName(uri: Uri): String? = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0)?.substringBeforeLast('.') else null
    }
}.getOrNull()
