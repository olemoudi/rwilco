package dev.rwilco.cheer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.rwilco.model.CheerShown
import dev.rwilco.model.remembering
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.cheerDataStore: DataStore<Preferences> by preferencesDataStore(
    "rwilco_cheers",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The lines of encouragement already said, so the next one is fresh (`pickCheer`). Its own store
 * and not the settings': it is written up to three times a day, and the settings blob is what the
 * backup watches — a line on Home is not worth a copy to GitHub. Unreadable is empty: forgetting
 * what was said costs, at worst, a line said again.
 */
class CheerStore(private val context: Context) {

    private val key = stringPreferencesKey("shown_json")
    private val serializer = ListSerializer(CheerShown.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun shown(): List<CheerShown> = decode(context.cheerDataStore.data.first()[key])

    suspend fun remember(said: CheerShown) {
        context.cheerDataStore.edit { prefs -> prefs[key] = json.encodeToString(serializer, decode(prefs[key]).remembering(said)) }
    }

    private fun decode(text: String?): List<CheerShown> =
        text?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
}
