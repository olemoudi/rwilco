package dev.rwilco.voice

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.voiceDataStore: DataStore<Preferences> by preferencesDataStore(
    "rwilco_voice",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Whether Gemini may be asked, and with which key. */
data class GeminiSetup(val enabled: Boolean = false, val key: String = "") {
    /** Asked only when switched on *and* there is a key to ask with. */
    val usable: Boolean get() = enabled && key.isNotBlank()
}

/**
 * Gemini's key and its switch (0.178.0), in a file of their own — as the vault's token is, and for
 * the same reasons: the settings travel in the backup and are replaced by a restore, and a key is
 * not something to send anywhere or to lose to a restore. App-private storage is encrypted at
 * rest by the phone and `allowBackup` is off; the key is not wrapped further, like the vault's.
 * A file that will not parse reads as no key: Gemini is simply off until it is set again.
 */
class GeminiStore(private val context: Context) {

    private val enabledKey = booleanPreferencesKey("gemini_enabled")
    private val apiKey = stringPreferencesKey("gemini_key")

    val setup: Flow<GeminiSetup> = context.voiceDataStore.data.map { prefs ->
        GeminiSetup(enabled = prefs[enabledKey] ?: false, key = prefs[apiKey].orEmpty())
    }

    suspend fun read(): GeminiSetup = setup.first()

    suspend fun setEnabled(enabled: Boolean) {
        context.voiceDataStore.edit { it[enabledKey] = enabled }
    }

    suspend fun setKey(key: String) {
        context.voiceDataStore.edit { it[apiKey] = geminiKeyOf(key) }
    }

    /** The key gone, and the switch with it. */
    suspend fun clear() {
        context.voiceDataStore.edit { prefs ->
            prefs.remove(apiKey)
            prefs.remove(enabledKey)
        }
    }
}

/**
 * A key as it can travel in a header: printable ASCII and nothing else. A key pasted with a
 * non-breaking or zero-width space in it, or typed with an "ñ", would otherwise be refused by the
 * HTTP layer itself on every ask — loudly, and with the key in the message.
 */
fun geminiKeyOf(raw: String): String = raw.filter { it.code in 0x21..0x7e }

