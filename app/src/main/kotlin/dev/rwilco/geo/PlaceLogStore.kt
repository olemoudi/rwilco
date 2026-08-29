package dev.rwilco.geo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.rwilco.model.ReminderCodec
import dev.rwilco.model.WatchLog
import dev.rwilco.model.WatchNote
import dev.rwilco.model.noting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant

// A file that will not parse is replaced by an empty one: this is the one thing in the app
// that is fine to lose, and it must not take the watch down with it.
private val Context.placeLogDataStore: DataStore<Preferences> by preferencesDataStore(
    "rwilco_place_log",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * What the place watch did and why, written down: one line a look, two hundred lines kept.
 *
 * Its own store again, and for a stronger reason than [PlaceWatchStore]'s: this is diagnostic,
 * it is the one thing in the app that is fine to lose, and nothing else should ever be one
 * corrupted blob away from going with it. Two hundred lines is about twenty-five kilobytes
 * rewritten per look, which is nothing next to the fix the same look just paid for.
 */
class PlaceLogStore(private val context: Context) {

    private val key = stringPreferencesKey("place_log_json")

    val log: Flow<WatchLog> = context.placeLogDataStore.data.map { prefs ->
        prefs[key]?.let(ReminderCodec::decodeWatchLog) ?: WatchLog()
    }

    suspend fun read(): WatchLog = log.first()

    /** Append one line, drop the oldest past the cap, and hand back what the log now says. */
    suspend fun note(note: WatchNote): WatchLog {
        var written = WatchLog()
        context.placeLogDataStore.edit { prefs ->
            val current = prefs[key]?.let(ReminderCodec::decodeWatchLog) ?: WatchLog()
            written = current.noting(note)
            prefs[key] = ReminderCodec.encodeWatchLog(written)
        }
        return written
    }

    /** Remember that the "looking too often" notice has gone out, so it does not go out again. */
    suspend fun noticed(at: Instant) {
        context.placeLogDataStore.edit { prefs ->
            val current = prefs[key]?.let(ReminderCodec::decodeWatchLog) ?: WatchLog()
            prefs[key] = ReminderCodec.encodeWatchLog(current.copy(lastNoticeAt = at))
        }
    }

    suspend fun clear() {
        context.placeLogDataStore.edit { prefs -> prefs.remove(key) }
    }
}
