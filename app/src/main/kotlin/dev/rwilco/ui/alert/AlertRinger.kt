package dev.rwilco.ui.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import dev.rwilco.notify.Sounds
import java.time.Duration
import dev.rwilco.model.AlertSound
import dev.rwilco.model.VibrationLimits
import dev.rwilco.model.VibrationPattern
import dev.rwilco.model.waveformFor

/**
 * The noise a full-screen alert makes while it is on screen.
 *
 * It loops, unlike the notification's one-shot tone, because a takeover that rings once and
 * then sits there silently is a screen you sleep through. It is the alert screen's own doing
 * (see [FiringPlan.notificationSound]): the notification that carried it stays quiet so the two
 * never overlap.
 */
class AlertRinger(private val context: Context) {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var focus: AudioFocusRequest? = null

    /** [limit] is how long the buzz may last; the default is the only one an alarm ever gets. */
    fun start(
        sound: Boolean,
        vibrate: Boolean,
        pattern: VibrationPattern = VibrationPattern(),
        limit: Duration = VibrationLimits.LONGEST,
        tone: AlertSound = AlertSound.System,
    ) {
        if (sound) startSound(tone)
        if (vibrate) startVibration(pattern, limit)
    }

    fun stop() {
        runCatching {
            player?.apply {
                if (isPlaying) stop()
                release()
            }
        }
        player = null
        focus?.let { request -> runCatching { context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) } }
        focus = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    private fun startSound(tone: AlertSound) {
        val uri = Sounds.uri(context, tone) ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // Ask for the audio, exclusively: a podcast or the navigation kept talking over a chime
        // that is deliberately gentle, and the chime was heard as nothing. Alarm usage cannot
        // be ducked by anybody else, so nothing is given up in return.
        runCatching {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).setAudioAttributes(attributes).build()
            context.getSystemService(AudioManager::class.java)?.requestAudioFocus(request)
            focus = request
        }
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(attributes)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { Log.w(TAG, "could not ring", it) }
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
