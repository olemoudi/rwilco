package dev.rwilco.vault

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

// A file that will not parse is replaced by an empty one: the backup reads as off and says
// so on its screen, which is a loss — the token and the key go with it — where a read that
// threw on every attempt crashed that screen and stopped the copies without a word. What the
// empty store cannot say is that it was ever anything else; [VaultStore.wasEnabled] can.
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
        var enabled = false
        context.vaultDataStore.edit { prefs ->
            val current = prefs[key]?.let(::decode) ?: VaultState()
            val next = transform(current)
            enabled = next.enabled
            prefs[key] = vaultJson.encodeToString(VaultState.serializer(), next)
        }
        mark(enabled)
    }

    /** Off: everything goes, the key and the token included. The remote file is not ours to touch. */
    suspend fun clear() {
        context.vaultDataStore.edit { prefs -> prefs.remove(key) }
        mark(false)
    }

    /**
     * Was the backup on, whatever the store says now? An off state with this still here is not a
     * backup somebody turned off: it is one whose store was replaced because it would not parse,
     * and the screen owes a word about it rather than the blank setup form it would otherwise be.
     */
    suspend fun wasEnabled(): Boolean = withContext(Dispatchers.IO) { marker().isFile }

    /**
     * The one fact kept outside the store, because the store is the thing that can be lost. Zero
     * bytes: its existence is the whole of what it says.
     */
    private suspend fun mark(enabled: Boolean) = withContext(Dispatchers.IO) {
        val file = marker()
        runCatching {
            if (enabled) {
                file.parentFile?.mkdirs()
                if (!file.isFile) file.createNewFile()
            } else {
                file.delete()
            }
        }
        Unit
    }

    private fun marker(): File = File(context.filesDir, "vault/$MARKER_FILE")

    private fun decode(raw: String): VaultState =
        runCatching { vaultJson.decodeFromString(VaultState.serializer(), raw) }.getOrDefault(VaultState())

    private companion object {
        const val MARKER_FILE = "on"
    }
}
