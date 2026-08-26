package dev.rwilco.vault

import dev.rwilco.BuildConfig
import dev.rwilco.RwilcoApplication
import dev.rwilco.data.RwilcoDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A vault opened and understood, waiting for somebody to say yes to it. */
class OpenedVault(
    val snapshot: VaultSnapshot,
    val summary: VaultSummary,
    /** The key that opened it and the salt it was derived with: what a phone that restores it keeps. */
    val key: ByteArray,
    val salt: ByteArray,
    val iterations: Int,
)

/**
 * The way back in: from the remote, from a file, from the copy kept before the last restore.
 *
 * Opening is separate from applying, so the person sees what a vault holds — when, from which
 * phone, how many reminders — before anything on this phone is touched. Applying is idempotent
 * and in a fixed order (rows, settings, the vault's own state, then the alarms), so a process
 * that dies half-way is put right by running it again; the source is never touched.
 */
class VaultRestore(private val app: RwilcoApplication) {

    /** Open [envelope] with a passphrase, deriving with the salt the file carries. */
    suspend fun open(envelope: ByteArray, passphrase: String): OpenedVault = withContext(Dispatchers.Default) {
        val header = VaultCrypto.header(envelope)
        val key = VaultCrypto.deriveKey(passphrase, header.salt, header.iterations)
        opened(envelope, key, header)
    }

    /** Open [envelope] with the key already on this phone; fails as a wrong passphrase when the salts differ. */
    suspend fun openWithKey(envelope: ByteArray, state: VaultState): OpenedVault = withContext(Dispatchers.Default) {
        val header = VaultCrypto.header(envelope)
        if (!header.salt.contentEquals(state.saltBytes())) throw VaultException.WrongPassphrase()
        opened(envelope, state.keyBytes(), header)
    }

    private fun opened(envelope: ByteArray, key: ByteArray, header: VaultHeader): OpenedVault {
        val snapshot = decodeSnapshot(VaultCrypto.open(envelope, key))
        return OpenedVault(snapshot, snapshot.summary(BuildConfig.VERSION_CODE), key, header.salt, header.iterations)
    }

    /**
     * This phone becomes what [opened] holds. Whatever was here first goes into the undo copy,
     * sealed under the incoming key (the one this phone keeps from now on), unless there was
     * nothing here. [adopt] is what the vault's own state becomes — credentials, key, cursors —
     * and runs between the data and the alarms.
     */
    suspend fun apply(opened: OpenedVault, adopt: (VaultState) -> VaultState) {
        val before = app.repository.allRows()
        if (before.isNotEmpty()) {
            val snapshot = buildSnapshot(before, app.settingsStore.rawJson().orEmpty(), app.clock.instant(), app.vaultStore.read().deviceId, BuildConfig.VERSION_CODE, RwilcoDatabase.VERSION)
            writeUndoCopy(VaultCrypto.seal(encodeSnapshot(snapshot), opened.key, opened.salt, opened.iterations))
        }
        app.repository.replaceAll(opened.snapshot.reminders)
        app.settingsStore.replaceRaw(opened.snapshot.settingsJson)
        app.vaultStore.update(adopt)
        app.firing.rearmAndCatchUp()
        app.geofences.sync()
        app.placeWatcher.sync()
    }

    /** This phone's data now, sealed under [key]: the export, and what the backup uploads. */
    suspend fun sealNow(key: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val state = app.vaultStore.read()
        val snapshot = buildSnapshot(app.repository.allRows(), app.settingsStore.rawJson().orEmpty(), app.clock.instant(), state.deviceId, BuildConfig.VERSION_CODE, RwilcoDatabase.VERSION)
        return withContext(Dispatchers.Default) { VaultCrypto.seal(encodeSnapshot(snapshot), key, salt, iterations) }
    }

    fun hasUndoCopy(): Boolean = undoFile().isFile

    suspend fun readUndoCopy(): ByteArray? = withContext(Dispatchers.IO) { undoFile().takeIf { it.isFile }?.readBytes() }

    private suspend fun writeUndoCopy(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val file = undoFile()
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    /** App-private, so the file picker cannot see it; the Backup screen offers it as its own row. */
    private fun undoFile(): File = File(app.filesDir, "vault/$UNDO_FILE")

    companion object {
        const val UNDO_FILE = "before-restore.vault"
    }
}
