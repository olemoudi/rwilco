package dev.rwilco.geo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.rwilco.model.PlaceWatchState
import dev.rwilco.model.ReminderCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.placeWatchDataStore: DataStore<Preferences> by preferencesDataStore("rwilco_place_watch")

/**
 * What the place watch remembers between two checks — its last fix, which places it was
 * inside, how long it has been still — as one JSON blob. Its own store, not the settings':
 * it is written on every check, and a setting should not have to share a file with that.
 */
class PlaceWatchStore(private val context: Context) {

    private val key = stringPreferencesKey("place_watch_json")

    val state: Flow<PlaceWatchState> = context.placeWatchDataStore.data.map { prefs ->
        prefs[key]?.let(ReminderCodec::decodePlaceWatch) ?: PlaceWatchState()
    }

    suspend fun read(): PlaceWatchState = state.first()

    suspend fun write(state: PlaceWatchState) {
        context.placeWatchDataStore.edit { prefs -> prefs[key] = ReminderCodec.encodePlaceWatch(state) }
    }
}
