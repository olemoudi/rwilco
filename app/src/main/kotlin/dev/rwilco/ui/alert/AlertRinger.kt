package dev.rwilco.ui.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import dev.rwilco.notify.AlertAudio
import dev.rwilco.notify.Sounds
import java.time.Duration
import dev.rwilco.model.AlertSound
import dev.rwilco.model.VibrationLimits
import dev.rwilco.model.VibrationPattern
import dev.rwilco.model.loopsOnScreen
import dev.rwilco.model.waveformFor

/**
 * The noise a full-screen alert makes while it is on screen.
 *
 * It is the alert screen's own doing (see [FiringPlan.notificationSound]): the notification that
 * carried it stays quiet so the two never overlap.
 *
 * **Round and round only if that is what was asked for.** A takeover that rings once and then
 * sits there silently is a screen you can sleep through, which is why looping was the whole of
 * it — but "sonido" and "hasta que reciba caso" are two different promises about how many times
 * somebody is going to hear the same tone, and the loop made them the same thing on the one
 * surface where it is loudest. See [loopsOnScreen].
 */
class AlertRinger(private val context: Context) {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var focus: AudioFocusRequest? = null

    /** The handover to the speaker, waiting to happen; see [AlertAudio.HEADPHONES_GRACE_MS]. */
    private val clock = Handler(Looper.getMainLooper())
    private var handover: Runnable? = null

    /** [limit] is how long the buzz may last; the default is the only one an alarm ever gets. */
    fun start(
        sound: Boolean,
        vibrate: Boolean,
        pattern: VibrationPattern = VibrationPattern(),
        limit: Duration = VibrationLimits.LONGEST,
        tone: AlertSound = AlertSound.System,
        /** Send it to the headphones when any are connected; see [AlertAudio.routeTo]. */
        toHeadphones: Boolean = true,
        /** Round and round, or once and done: see [loopsOnScreen]. */
        looping: Boolean = true,
    ) {
        if (sound) startSound(tone, toHeadphones, looping)
        if (vibrate) startVibration(pattern, limit)
    }

    fun stop() {
        soundOver()
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    /**
     * The sound is over — it finished, it failed, or it is being stopped. The player goes, the
     * audio is handed back, and the handover to the speaker is called off.
     *
     * **The buzz is not this.** A vibration has its own minute to run ([VibrationLimits.LONGEST])
     * and outlives a two-second tone by fifty-eight of them, so ending one must not end the
     * other; only [stop] ends both.
     */
    private fun soundOver() {
        handover?.let(clock::removeCallbacks)
        handover = null
        runCatching {
            player?.apply {
                if (isPlaying) stop()
                release()
            }
        }
        player = null
        AlertAudio.release(context, focus)
        focus = null
    }

    private fun startSound(tone: AlertSound, toHeadphones: Boolean, looping: Boolean) {
        val uri = Sounds.uri(context, tone) ?: return
        // Everything else drops a few decibels and carries on underneath; see AlertAudio.
        focus = AlertAudio.duckOthers(context)
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(AlertAudio.attributes())
                AlertAudio.routeTo(context, this, toHeadphones)
                isLooping = looping
                // **A tone said once hands the audio back the moment it ends.**
                //
                // Ducking is not a volume this app sets: it is every other app being asked to
                // drop a few decibels and stay there until we let go of the focus
                // ([AlertAudio.duckOthers]). Nothing here ever watched a one-shot tone finish,
                // so the letting go waited for [stop] — the alert answered, or the minute
                // running out — and two seconds of chime kept somebody's music down for the
                // rest of that minute. The same seam as the silence step: a sound that says
                // itself once has an end nobody was looking at. A looping tone has no
                // completion to listen for and rightly holds the focus until it is silenced.
                if (!looping) {
                    setOnCompletionListener { done -> if (player === done) soundOver() }
                    setOnErrorListener { failed, _, _ -> if (player === failed) soundOver(); true }
                }
                prepare()
                start()
            }
        }.onFailure {
            Log.w(TAG, "could not ring", it)
            // Asked for the audio and never got a sound out of it: give it straight back, or
            // the podcast stays ducked until something else happens to call stop().
            soundOver()
        }
        // Only where there is a sound to hand over: a player that never started has already
        // given the audio back, and this would put the handover it just cancelled back on the queue.
        if (player != null && toHeadphones && AlertAudio.headsetConnected(context)) handOverToSpeaker()
    }

    /**
     * Twenty seconds unanswered, and the sound comes out of the phone instead of the earbuds.
     *
     * Only where it was sent to earbuds in the first place, and only until something stops it:
     * [stop] takes it off the queue, so answering the alert, silencing it, or the minute running
     * out all cancel the handover rather than moving a sound that is no longer playing. The
     * reason it exists at all is in [AlertAudio.HEADPHONES_GRACE_MS] — headphones connected are
     * not headphones being listened through.
     */
    private fun handOverToSpeaker() {
        val move = Runnable {
            handover = null
            player?.let { AlertAudio.toSpeaker(context, it) }
        }
        handover = move
        clock.postDelayed(move, AlertAudio.HEADPHONES_GRACE_MS)
    }

    /**
     * The buzz, built whole and handed over: a minute of it at most, and no repeat count.
     *
     * It used to repeat from index 0 and be stopped by hand, which works right up until the
     * hand is not there — a killed process, a crash, a stop() that never ran — and then the
     * motor is buzzing until somebody reboots. A finite waveform cannot outlive the app, and a
     * minute is as long as a motor should be asked to work in a stretch anyway (see
     * [VibrationLimits.LONGEST]). The alert screen still stops it when it is answered; this is
     * what happens when nothing does.
     */
    private fun startVibration(pattern: VibrationPattern, limit: Duration) {
        val service = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        vibrator = service
        val waveform = waveformFor(pattern, limit)
        runCatching {
            val timings = waveform.timings.toLongArray()
            // Amplitude is a thing only some motors can do. Where it cannot, gentle and strong
            // are the same vibration — the phone has one setting and this is it.
            val effect = if (service.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(timings, waveform.amplitudes.toIntArray(), NO_REPEAT)
            } else {
                VibrationEffect.createWaveform(timings, NO_REPEAT)
            }
            // As an alarm, in so many words: a buzz with no usage is the first thing the ringer
            // switch and Do Not Disturb drop, and the one thing a screen that rings for itself
            // must not lose.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                service.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                service.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
        }.onFailure { Log.w(TAG, "could not vibrate", it) }
    }

    private companion object {
        const val TAG = "RwilcoAlarms"

        /** Play the pattern once and stop. It is already as long as it is allowed to be. */
        const val NO_REPEAT = -1
    }
}
