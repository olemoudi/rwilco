package dev.rwilco.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log

/**
 * How a reminder makes its noise: where it comes out, and what it does to whatever else was
 * playing.
 *
 * Both used to be left to the defaults, and the defaults are wrong for this. An alarm-usage
 * sound goes to the phone's own speaker even with earbuds in, which is the right answer for a
 * seven-in-the-morning alarm and the wrong one for somebody wearing headphones at their desk.
 * And asking for the audio *exclusively* stops the podcast — a reminder is ten seconds long and
 * has no business ending what somebody was listening to.
 */
object AlertAudio {

    private const val TAG = "RwilcoAlarms"

    /** The alarm stream, always: it is the one volume slider that governs reminders. */
    fun attributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * Asks for the audio, letting everybody else stay on quietly underneath.
     *
     * `MAY_DUCK` rather than `EXCLUSIVE`: the music drops a few decibels for the seconds the
     * reminder lasts and comes back by itself, which is what a car does and what anybody
     * listening to something would want. The reminder is on the alarm stream and the music is
     * on the media one, so it is over the top of it without either fighting the other.
     */
    fun duckOthers(context: Context): AudioFocusRequest? = runCatching {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes())
            .setWillPauseWhenDucked(false)
            .build()
        context.getSystemService(AudioManager::class.java)?.requestAudioFocus(request)
        request
    }.onFailure { Log.w(TAG, "could not ask for the audio", it) }.getOrNull()

    fun release(context: Context, request: AudioFocusRequest?) {
        request ?: return
        runCatching { context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) }
    }

    /**
     * Sends the sound to the headphones when there are any, and leaves it to the phone when
     * there are not.
     *
     * A preference, not a command: the platform falls back to its own routing if it cannot
     * honour it, and the device is looked up at the moment of playing rather than remembered,
     * so a pair unplugged a minute ago cannot swallow a reminder. Set [toHeadphones] false and
     * this does nothing at all, which is the setting for anybody who leaves earbuds in a
     * drawer — an alarm nobody hears is worse than one that comes out of the wrong hole.
     */
    fun routeTo(context: Context, player: MediaPlayer, toHeadphones: Boolean) {
        if (!toHeadphones) return
        val headset = headset(context) ?: return
        runCatching { player.preferredDevice = headset }
            .onFailure { Log.w(TAG, "could not route to the headset", it) }
    }

    /** The headphones, of whatever kind, if any are connected right now. */
    fun headset(context: Context): AudioDeviceInfo? {
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        val outputs = runCatching { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull() ?: return null
        return outputs.firstOrNull { it.type in HEADSETS }
    }

    fun headsetConnected(context: Context): Boolean = headset(context) != null

    /** Everything somebody can be wearing or have plugged in. Not the speaker, not the earpiece. */
    private val HEADSETS = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )
}
