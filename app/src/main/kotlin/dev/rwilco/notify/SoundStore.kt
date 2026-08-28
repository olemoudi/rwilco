package dev.rwilco.notify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import dev.rwilco.model.AlertSound
import dev.rwilco.model.AppSettings
import java.io.File

/**
 * The app's own copy of a sound somebody chose.
 *
 * A picked file belongs to whoever picked it: it can be deleted, moved, renamed, emptied from a
 * downloads folder, or live in an app that is uninstalled — and the persistable permission that
 * lets us read it goes with any of those. An alarm whose tone quietly stops existing is the
 * failure this app exists not to have, so the file is copied in here the moment it is chosen and
 * the reminder points at our copy from then on. What somebody does with the original afterwards
 * is their business.
 *
 * The copy is handed out through a [FileProvider], not as a bare path, because the notification
 * *channel's* sound is played by the system and the system cannot read anything in an app's own
 * files. The read is granted to the two packages that do that playing, and re-granted every time
 * the channels are ensured — a grant does not survive a reboot.
 */
object SoundStore {

    private const val TAG = "RwilcoAlarms"
    private const val DIR = "sounds"

    /** Where our copies live. Created on demand; never anybody else's business. */
    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** The authority in the manifest, which is the package plus a word. */
    private fun authority(context: Context): String = "${context.packageName}.sounds"

    /** Whether this is one of ours, which is the only kind we can promise will still be there. */
    fun isOurs(context: Context, sound: AlertSound): Boolean =
        sound is AlertSound.Custom && sound.uri.startsWith("content://${authority(context)}/")

    /**
     * Copy [source] in and hand back the sound that points at the copy, or null when the file
     * cannot be read at all — which is the one case where there is nothing to keep.
     *
     * The name carries no meaning on purpose: two files called "alarm.mp3" are two sounds, and a
     * name somebody typed is not a thing to build a file path out of. The label is what is shown.
     */
    fun keep(context: Context, source: Uri, label: String): AlertSound.Custom? = runCatching {
        val extension = context.contentResolver.getType(source)?.substringAfterLast('/')?.take(EXTENSION_MAX)
        val file = File(dir(context), "sound-${System.currentTimeMillis()}${extension?.let { ".$it" } ?: ""}")
        context.contentResolver.openInputStream(source)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        if (file.length() == 0L) {
            file.delete()
            return null
        }
        AlertSound.Custom(uriOf(context, file).toString(), label)
    }.onFailure { Log.w(TAG, "could not keep a copy of $source", it) }.getOrNull()

    private fun uriOf(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    /**
     * Let the system read one of our copies, so a notification channel made with it actually
     * makes a noise.
     *
     * Two packages, because which of them does the playing has moved between versions of Android
     * and neither grant costs anything. It is re-done on every [AlertNotifications.ensureChannels]
     * rather than once at the pick: a grant is not persisted, so a reboot would otherwise leave a
     * channel pointing at a file the system had stopped being allowed to open.
     */
    fun grantToSystem(context: Context, uri: Uri) {
        if (uri.authority != authority(context)) return
        for (who in SYSTEM_PLAYERS) {
            runCatching { context.grantUriPermission(who, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                .onFailure { Log.w(TAG, "could not let $who read $uri", it) }
        }
    }

    /**
     * The settings with both tones settled: ours kept, somebody else's adopted, and anything
     * that cannot be read at all sent back to the phone's own alarm.
     *
     * Run at launch and after a restore, which are the two moments a stored sound can have
     * stopped being true. A restore is the one that matters most — a vault from another phone
     * names a file that was never on this one — and falling back to the default is the whole of
     * what it should do about that: a reminder that rings with the wrong tone is a reminder that
     * rings.
     */
    fun settle(context: Context, settings: AppSettings): AppSettings = settings.copy(
        alertSound = settle(context, settings.alertSound) ?: AlertSound.System,
        insistentSound = settings.insistentSound?.let { settle(context, it) ?: AlertSound.System },
    )

    private fun settle(context: Context, sound: AlertSound): AlertSound? {
        if (sound !is AlertSound.Custom) return sound
        val uri = runCatching { Uri.parse(sound.uri) }.getOrNull() ?: return null
        if (!Sounds.readable(context, uri)) return null
        // Already ours: nothing to do but keep it.
        if (isOurs(context, sound)) return sound
        // Somebody else's, and still readable — take a copy now, while it is still there. This
        // is what carries a sound chosen before the app kept its own copies.
        return keep(context, uri, sound.label) ?: sound
    }

    /**
     * Copies nothing points at any more, deleted.
     *
     * Called with the settled settings, so the two tones in use are safe by construction. A
     * restore's undo is the one thing this can cost: taking back a restore that changed the
     * sound leaves the old copy gone and the tone back at the phone's own, which is the same
     * answer a missing sound gets everywhere else.
     */
    fun sweep(context: Context, settings: AppSettings) {
        val keep = listOfNotNull(settings.alertSound, settings.insistentSound)
            .filterIsInstance<AlertSound.Custom>()
            .mapNotNull { runCatching { Uri.parse(it.uri).lastPathSegment }.getOrNull() }
            .toSet()
        runCatching {
            dir(context).listFiles()?.forEach { file ->
                if (file.name !in keep) {
                    Log.i(TAG, "dropping an unused sound copy: ${file.name}")
                    file.delete()
                }
            }
        }.onFailure { Log.w(TAG, "could not sweep the sound copies", it) }
    }

    /** The packages that play a notification channel's tone, across the versions that matter. */
    private val SYSTEM_PLAYERS = listOf("com.android.systemui", "android")

    /** Enough for "mpeg", "ogg", "wav": a type nobody recognises simply gets no suffix. */
    private const val EXTENSION_MAX = 5
}
