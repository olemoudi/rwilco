package dev.rwilco.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.rwilco.model.Action
import dev.rwilco.model.AppSettings
import dev.rwilco.model.ReminderCodec
import dev.rwilco.model.foldRepeats
import dev.rwilco.model.offered
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.ZoneId

// A file that will not parse is replaced by an empty one: the settings come back as defaults,
// which is a loss, where a read that throws on every attempt — from the firing, from the
// scheduler, from every collector in the app — was the whole app going quiet.
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    "rwilco_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The settings as one JSON blob under one key. Additive changes to AppSettings need no
 * migration: missing fields take their defaults and unknown ones are ignored on read.
 */
class SettingsStore(private val context: Context) {

    private val key = stringPreferencesKey("settings_json")

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs -> prefs.decode() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[key] = ReminderCodec.encodeSettings(transform(prefs.decode()))
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

    /** A restore: the blob becomes [json] as it is, read leniently like everything else. */
    suspend fun replaceRaw(json: String) {
        context.settingsDataStore.edit { prefs -> prefs[key] = json }
    }
}

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

