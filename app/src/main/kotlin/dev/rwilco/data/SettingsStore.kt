package dev.rwilco.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.rwilco.model.Action
import dev.rwilco.model.AppSettings
import dev.rwilco.model.ReminderCodec
import dev.rwilco.model.Unlocked
import dev.rwilco.model.mergeUnlocked
import dev.rwilco.model.foldRepeats
import dev.rwilco.model.offered
import dev.rwilco.model.withPlaceIds
import dev.rwilco.diag.Diag
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId

// A file that will not parse is replaced by an empty one: the settings come back as defaults,
// which is a loss, where a read that throws on every attempt — from the firing, from the
// scheduler, from every collector in the app — was the whole app going quiet. The empty one says
// when it was put there: nothing of the old file is left to keep aside, and the backup has to
// know the defaults it is about to see were not anybody's choice (see [SettingsStore.lostAt]).
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    "rwilco_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { preferencesOf(REPLACED_AT to System.currentTimeMillis()) },
)

/** When the settings file was replaced by an empty one because it would not parse. */
private val REPLACED_AT = longPreferencesKey("replaced_at")

/**
 * The settings as one JSON blob under one key. Additive changes to AppSettings need no
 * migration: missing fields take their defaults and unknown ones are ignored on read.
 */
class SettingsStore(private val context: Context) {

    private val key = stringPreferencesKey("settings_json")

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs -> prefs.decode() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            // Before anything is written over what would not read whole: see [keepAside].
            prefs[key]?.let { raw -> if (ReminderCodec.settingsLostOnRead(raw, ReminderCodec.decodeSettingsOrNull(raw))) keepAside(raw) }
            prefs[key] = ReminderCodec.encodeSettings(transform(prefs.decode()))
        }
    }

    /**
     * The milestones [derived] proves, added to the ones kept (`mergeUnlocked`): never one taken
     * away, and nothing written when nothing is new — the blob is what the backup watches.
     */
    suspend fun keepUnlocked(derived: List<Unlocked>) {
        if (derived.isEmpty()) return
        update { settings ->
            val merged = mergeUnlocked(settings.achievements, derived)
            if (merged === settings.achievements) settings else settings.copy(achievements = merged)
        }
    }

    /**
     * The blob, with the shapes that moved read as what they are now.
     *
     * The presets are folded like the reminders are (`foldRepeats`): one written when a repeating
     * time was a trigger holds one as a rule, and the editor has no tile left to open it with.
     * Here rather than in the codec because it wants a zone, and here rather than once at launch
     * because a preset can also arrive from a restored backup.
     *
     * And the favourite kind is read through [TriggerKind.offered], because a stored one that is
     * no longer a tile has to become the tile it turned into. `TriggerKindSheet` puts the
     * favourite at the top of the list and the rest behind it, so a favourite outside the list
     * came out as a *second* row — and once the date tile was renamed "Fecha y hora", that row
     * was word for word the one under it, wearing "el que sueles usar" and opening the same sheet.
     * A phantom the whole time; the rename is only what made it visible.
     */
    private fun Preferences.decode(): AppSettings {
        val settings = this[key]?.let(ReminderCodec::decodeSettings) ?: return AppSettings()
        val zone = ZoneId.systemDefault()
        return settings.copy(
            presets = settings.presets.map { it.foldRepeats(zone) },
            // A place kept before keys existed is named here, the same on every read, and the
            // next write stores the name (see withPlaceIds).
            savedPlaces = settings.savedPlaces.withPlaceIds(),
            defaultTriggerKind = settings.defaultTriggerKind?.offered(),
            defaultActions = withSound(settings.defaultActions),
            routineActions = withSound(settings.routineActions),
        )
    }

    /**
     * The blob as written, for the backup: copied whole rather than decoded and re-encoded, so a
     * setting this build does not know survives the round trip through a phone that has it.
     * Null until the first write.
     */
    val raw: Flow<String?> = context.settingsDataStore.data.map { prefs -> prefs[key] }

    suspend fun rawJson(): String? = raw.first()

    /**
     * Settings that would not read whole, kept aside before anything writes over them (0.167.0).
     * The defaults — or the lists without the element that would not read — are what the app
     * runs on, but the first write after that (the "what's new" sheet makes one on every launch)
     * used to make the loss permanent: presets, places, windows, the sounds. Here, on the write,
     * because that is the moment it becomes a loss, and it runs off the main thread (0.169.0; it
     * was on the read, which a screen collects on the main one). Each blob once, by its content,
     * with its time in the name: a second one — from a restored vault, a later bug — is kept too.
     */
    private fun keepAside(raw: String) {
        if (keptAside().any { runCatching { it.readText() == raw }.getOrDefault(false) }) return
        val file = File(context.filesDir, "$ASIDE_PREFIX${System.currentTimeMillis()}.json")
        runCatching { file.writeText(raw) }
            .onSuccess { Diag.note("settings", "settings that would not read whole are kept as ${file.name} (${raw.length} chars)") }
            .onFailure {
                Log.w(TAG, "could not keep the unreadable settings aside", it)
                Diag.note("settings", "settings that would not read whole could not be kept aside: ${it::class.simpleName}")
            }
    }

    /**
     * When settings were last lost to a read — kept aside because they would not read whole, or
     * the file replaced because it would not parse — if ever. What tells the backup that settings
     * with nothing of the person's left in them were a reset and not a deletion (0.172.0).
     */
    suspend fun lostAt(): Instant? {
        val replaced = context.settingsDataStore.data.first()[REPLACED_AT]
        val aside = keptAside().maxOfOrNull { it.lastModified() }
        return listOfNotNull(replaced, aside).maxOrNull()?.let(Instant::ofEpochMilli)
    }

    private fun keptAside(): List<File> = context.filesDir.listFiles { file -> file.name.startsWith(ASIDE_PREFIX) }.orEmpty().toList()

    /**
     * Whether the sweep of the sound copies is held: settings that do not read whole now, or were
     * kept aside within [SWEEP_HOLD]. The defaults standing in for them point at no tone of their
     * own, and the sweep would take the copies the kept settings name — so it waits, a month, and
     * then the copies nothing asks for go as they always did (it was held for good in 0.167.0).
     */
    suspend fun sweepHeld(now: Instant): Boolean {
        val raw = rawJson()
        if (raw != null && ReminderCodec.settingsLostOnRead(raw, ReminderCodec.decodeSettingsOrNull(raw))) return true
        val since = now.minus(SWEEP_HOLD).toEpochMilli()
        return keptAside().any { it.lastModified() > since }
    }

    /** A restore: the blob becomes [json] as it is, read leniently like everything else. */
    suspend fun replaceRaw(json: String) {
        context.settingsDataStore.edit { prefs -> prefs[key] = json }
    }
}

private const val TAG = "RwilcoSettings"

/** Settings that would not read whole are kept as `<this><epoch millis>.json` in the app's own files. */
const val ASIDE_PREFIX = "settings-unreadable-"

/** How long settings kept aside hold the sweep of the sound copies; see [SettingsStore.sweepHeld]. */
private val SWEEP_HOLD: java.time.Duration = java.time.Duration.ofDays(30)

/** What every version of this app before 0.109.0 switched on for a new reminder. */
private val OLD_DEFAULT_ACTIONS = setOf(Action.NOTIFICATION, Action.VIBRATE)

/**
 * The one-off that carries [DEFAULT_ACTIONS]'s new sound onto a phone that already has
 * settings written.
 *
 * The blob is encoded with every field ([ReminderCodec] sets `encodeDefaults`), and it is
 * written on the first launch of every build (`lastSeenVersionCode` lives in it), so every
 * phone in use has `defaultActions` on disk and a change to the constant alone would have
 * reached nobody but a fresh install.
 *
 * **Only the old default set, exactly.** Anything else is somebody's answer and is left
 * alone: a set with the insistent sound in it, a set with nothing in it (which means "let
 * the moment pass in silence" and is a real choice), a set with the full screen. What it
 * costs is the one person who chose exactly a card and a buzz on purpose, once — and the
 * tile to put it back is where they set it.
 */
internal fun withSound(actions: Set<Action>): Set<Action> =
    if (actions == OLD_DEFAULT_ACTIONS) actions + Action.SOUND else actions

