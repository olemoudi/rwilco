package dev.rwilco.ui.settings

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import dev.rwilco.model.AlertSound
import dev.rwilco.notify.Sounds

/**
 * The chime, so a sound can be chosen by ear rather than by name — once, or as the round the
 * insistent one plays.
 *
 * At alarm usage on purpose: a preview at notification volume on a phone whose notification
 * volume is at zero is a button that appears to do nothing, and the point of the button is to
 * tell you what the alarm will sound like.
 */
class SoundPreview(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var onRunning: ((Boolean) -> Unit)? = null

    /** The chime, once. */
    fun play(sound: AlertSound) {
        stop()
        start(sound) { stop() }
    }

    /**
     * The insistent sound, rehearsed: the same tone as many times as it would really come back,
     * with the waits between shortened to something somebody will sit through. Nobody is going
     * to hold the phone for five minutes to find out what "hasta que lo atienda" is like, and
     * the thing worth knowing about it is the shape — how many times, and that it keeps coming
     * — not the wait. The card says the real one underneath.
     */
    fun playRound(sound: AlertSound, times: Int, gapMs: Long, onRunning: (Boolean) -> Unit) {
        stop()
        this.onRunning = onRunning
        onRunning(true)
        var left = times.coerceAtLeast(1)
        fun again() {
            if (left <= 0) {
                stop()
                return
            }
            left--
            start(sound) { handler.postDelayed(::again, gapMs) }
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
        onRunning?.invoke(false)
        onRunning = null
    }

    private fun start(sound: AlertSound, onDone: () -> Unit) {
        val uri = Sounds.uri(context, sound) ?: return onDone()
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                // Let go of it as it finishes: a preview that has ended is a player nobody owns.
                setOnCompletionListener {
                    runCatching { release() }
                    if (player === this) player = null
                    onDone()
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
