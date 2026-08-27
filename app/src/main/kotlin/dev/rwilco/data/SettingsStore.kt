package dev.rwilco.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.rwilco.model.AppSettings
import dev.rwilco.model.ReminderCodec
import dev.rwilco.model.foldRepeats
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
     * The blob, with the presets folded like the reminders are (`foldRepeats`): a preset written
     * when a repeating time was a trigger holds one as a rule, and the editor has no tile left to
     * open it with. Here rather than in the codec because it wants a zone, and here rather than
     * once at launch because a preset can also arrive from a restored backup.
     */
    private fun Preferences.decode(): AppSettings {
        val settings = this[key]?.let(ReminderCodec::decodeSettings) ?: return AppSettings()
        val zone = ZoneId.systemDefault()
        return settings.copy(presets = settings.presets.map { it.foldRepeats(zone) })
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
