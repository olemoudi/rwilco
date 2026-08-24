package dev.rwilco.ui.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

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

    fun start(sound: Boolean, vibrate: Boolean) {
        if (sound) startSound()
        if (vibrate) startVibration()
    }

    fun stop() {
        runCatching {
            player?.apply {
                if (isPlaying) stop()
                release()
            }
        }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    private fun startSound() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { Log.w(TAG, "could not ring", it) }
    }

    private fun startVibration() {
        val service = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        vibrator = service
        runCatching {
            // Repeat from index 0: buzz, pause, buzz, until stop().
            service.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 800), 0))
        }.onFailure { Log.w(TAG, "could not vibrate", it) }
    }

    private companion object {
        const val TAG = "RwilcoAlarms"
    }
}
