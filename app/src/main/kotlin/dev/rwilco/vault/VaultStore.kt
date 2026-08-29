package dev.rwilco.vault

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// A file that will not parse is replaced by an empty one: the backup reads as off and says
// so on its screen, which is a loss — the token and the key go with it — where a read that
// threw on every attempt crashed that screen and stopped the copies without a word.
private val Context.vaultDataStore: DataStore<Preferences> by preferencesDataStore(
    "rwilco_vault",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The vault's own store: its credentials, its key and its cursors, as one JSON blob. Its own
 * file because the settings are part of what it backs up — a restore replaces them whole, and
 * must not replace the credentials that made the restore possible — and because a run writes
 * here every couple of hours. App-private storage is encrypted at rest by the phone (FBE) and
 * `allowBackup` is off; the key is not wrapped further on purpose, so that the one way a backup
 * can stop is somebody turning it off.
 */
class VaultStore(private val context: Context) : VaultStateStore {

    private val key = stringPreferencesKey("vault_json")

    val state: Flow<VaultState> = context.vaultDataStore.data.map { prefs -> prefs[key]?.let(::decode) ?: VaultState() }

    override suspend fun read(): VaultState = state.first()

    override suspend fun update(transform: (VaultState) -> VaultState) {
        context.vaultDataStore.edit { prefs ->
            val current = prefs[key]?.let(::decode) ?: VaultState()
            prefs[key] = vaultJson.encodeToString(VaultState.serializer(), transform(current))
        }
    }

    /** Off: everything goes, the key and the token included. The remote file is not ours to touch. */
    suspend fun clear() {
        context.vaultDataStore.edit { prefs -> prefs.remove(key) }
    }

    private fun decode(raw: String): VaultState =
        runCatching { vaultJson.decodeFromString(VaultState.serializer(), raw) }.getOrDefault(VaultState())
}
