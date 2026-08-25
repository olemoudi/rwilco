package dev.rwilco.notify

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import dev.rwilco.R
import dev.rwilco.model.AlertSound
import dev.rwilco.model.Chime

/** Where a chosen sound actually lives, and what to fall back to when it does not. */
object Sounds {

    val Chime.raw: Int
        get() = when (this) {
            Chime.ALERT -> R.raw.chime_alert
            Chime.TWO_TONE -> R.raw.chime_two_tone
            Chime.LOW -> R.raw.chime_low
            Chime.SOFT -> R.raw.chime_soft
        }

    /**
     * The Uri to play, or null when the phone has nothing to play at all.
     *
     * A custom sound is a file somebody else owns: it can be deleted, moved, or on a card that
     * is out of the phone, and the permission to read it can be revoked. None of that may end
     * with a silent alarm, so anything that will not resolve falls back to the phone's own
     * alarm tone. Better the wrong sound than no sound.
     */
    fun uri(context: Context, sound: AlertSound): Uri? = when (sound) {
        AlertSound.System -> systemAlarm(context)
        is AlertSound.Bundled -> bundled(context, sound.chime)
        is AlertSound.Custom -> runCatching { Uri.parse(sound.uri) }.getOrNull()?.takeIf { readable(context, it) }
            ?: systemAlarm(context)
    }

    fun bundled(context: Context, chime: Chime): Uri =
        Uri.parse("android.resource://${context.packageName}/${chime.raw}")

    /** Whether the file behind a Uri can still be opened. Cheap, and asked before it matters. */
    fun readable(context: Context, uri: Uri): Boolean =
        runCatching { context.contentResolver.openInputStream(uri)?.use { true } ?: false }.getOrDefault(false)

    private fun systemAlarm(context: Context): Uri? =
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
}
