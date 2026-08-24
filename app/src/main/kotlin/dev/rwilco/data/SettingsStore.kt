package dev.rwilco.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.rwilco.model.AppSettings
import dev.rwilco.model.ReminderCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("rwilco_settings")

/**
 * The settings as one JSON blob under one key. Additive changes to AppSettings need no
 * migration: missing fields take their defaults and unknown ones are ignored on read.
 */
class SettingsStore(private val context: Context) {

    private val key = stringPreferencesKey("settings_json")

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        prefs[key]?.let(ReminderCodec::decodeSettings) ?: AppSettings()
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[key]?.let(ReminderCodec::decodeSettings) ?: AppSettings()
            prefs[key] = ReminderCodec.encodeSettings(transform(current))
        }
    }
}
