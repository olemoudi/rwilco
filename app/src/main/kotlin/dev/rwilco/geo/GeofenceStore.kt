package dev.rwilco.geo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.geofenceDataStore: DataStore<Preferences> by preferencesDataStore(
    "rwilco_geofences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The one thing [GeofenceManager] remembers between two syncs: the fingerprint of what it last
 * registered with Play Services, so a sync that would register the same thing again can leave
 * the fences where they are. Its own store, because it is the process-independent memory of
 * something Play Services will not show us and nothing else in the app needs; a file that will
 * not parse reads as "nothing registered", which costs one wholesale registration and nothing
 * else.
 */
class GeofenceStore(private val context: Context) {

    private val key = stringPreferencesKey("registered_fingerprint")

    suspend fun read(): String? = context.geofenceDataStore.data.map { it[key] }.first()

    suspend fun write(fingerprint: String?) {
        context.geofenceDataStore.edit { prefs -> if (fingerprint == null) prefs.remove(key) else prefs[key] = fingerprint }
    }
}
