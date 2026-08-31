package dev.rwilco.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
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

    /**
     * How long a reminder sent to the headphones is given before it comes out of the phone
     * instead.
     *
     * A pair of earbuds is not proof that anybody is listening to them. Bluetooth headphones
     * routinely hold two devices at once, and while the *other* one has the channel — a laptop
     * playing music, a second phone on a call — an alarm handed to the earbuds is played into a
     * link that is not carrying it, or under something loud enough to bury it. Nothing about
     * that is visible from here: the headset is connected, the routing is accepted, the player
     * says it is playing, and the person hears nothing. The vibration is no help either; a phone
     * on a desk is a phone nobody is touching.
     *
     * So the routing is a *first* answer rather than the only one. Twenty seconds is long enough
     * for somebody actually wearing them to have heard it and reached for the screen, and short
     * enough to be well inside the minute the noise is allowed to last at all
     * ([VibrationLimits.LONGEST]) — the handover leaves forty seconds of alarm out loud, which is
     * an alarm.
     */
    const val HEADPHONES_GRACE_MS: Long = 20_000L

    /**
     * Move a sound already playing to the phone's own speaker. See [HEADPHONES_GRACE_MS].
     *
     * The speaker is named outright rather than the preference simply being cleared. Clearing it
     * hands the choice back to the platform, and the platform's answer for an alarm with A2DP
     * connected is not the same on every phone — which is the exact uncertainty this exists to
     * end. A phone with no speaker device to find falls back to clearing it, which is still
     * better than staying where it was.
     */
    fun toSpeaker(context: Context, player: MediaPlayer) {
        val audio = context.getSystemService(AudioManager::class.java)
        val outputs = audio?.let { runCatching { it.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull() }
        val speaker = outputs?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        runCatching { player.preferredDevice = speaker }
            .onFailure { Log.w(TAG, "could not move the sound to the speaker", it) }
    }

    /**
     * A sound played **once**, at [gain] of the alarm volume, that lets go of itself.
     *
     * The other way of making a noise in this app. [AlertRinger] builds a player somebody
     * holds — a screen is up, and it stops when the screen is answered — whereas this is
     * reached from a broadcast with no screen behind it and nothing that will ever call stop:
     * the safety net's word. So it releases the player and gives the audio back on its own,
     * when the tone ends and when anything goes wrong, or the app would hold transient focus
     * over somebody's music for ever the first time a file would not open.
     *
     * [gain] is amplitude, which is what a volume control is: 0.5 is half of the alarm, said in
     * the only unit a sound has. The stream is the alarm's like everything else here, because
     * "half of what is configured" only means anything against the slider the sound was chosen
     * with — and being unheard is the one failure this is trying to prevent.
     */
    fun playOnce(context: Context, uri: Uri, gain: Float, toHeadphones: Boolean) {
        val focus = duckOthers(context)
        val give = { player: MediaPlayer ->
            runCatching { player.release() }
            release(context, focus)
        }
        runCatching {
            MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(attributes())
                routeTo(context, this, toHeadphones)
                setVolume(gain, gain)
                isLooping = false
                setOnCompletionListener(give)
                // A player that errors after start() never completes, and a listener that is
                // not there is the difference between letting go and holding the focus for ever.
                setOnErrorListener { player, _, _ -> give(player); true }
                prepare()
                start()
            }
        }.onFailure {
            Log.w(TAG, "could not play the tone once", it)
            release(context, focus)
        }
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
