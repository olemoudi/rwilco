package dev.rwilco.ui.settings

import android.net.Uri
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.BackupCadence
import dev.rwilco.model.passphraseIsStrongEnough
import dev.rwilco.vault.GitHubVault
import dev.rwilco.vault.KDF_ITERATIONS
import dev.rwilco.vault.OpenedVault
import dev.rwilco.vault.TransportFailure
import dev.rwilco.vault.VaultCenter
import dev.rwilco.vault.VaultCrypto
import dev.rwilco.vault.VaultException
import dev.rwilco.vault.VaultNotifications
import dev.rwilco.vault.VaultOutcome
import dev.rwilco.vault.VaultRestore
import dev.rwilco.vault.VaultState
import dev.rwilco.vault.VaultTransport
import dev.rwilco.vault.VaultTransportException
import dev.rwilco.vault.VaultWorker
import dev.rwilco.vault.fingerprint
import dev.rwilco.vault.isRepoName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.rwilco.ui.format.words
import java.time.LocalDate
import java.util.UUID

/** What the person typed on the setup form. Lives here so a rotation does not empty it. */
data class BackupForm(val repo: String = "", val token: String = "", val passphrase: String = "", val again: String = "")

/** A GitHub repository and the token that opens it. */
data class Credentials(val owner: String, val repo: String, val pat: String)

/** Where a vault about to be restored came from; decides what the phone keeps afterwards. */
sealed interface RestoreSource {
    /** The remote: this phone adopts its key and, when given, the credentials that reached it. */
    data class Remote(val sha: String, val credentials: Credentials?) : RestoreSource
    /** A file somebody picked; the vault's own credentials and key are left alone. */
    data object File : RestoreSource
    /** The copy kept before the last restore. */
    data object Undo : RestoreSource
    /** A rehearsal: open it, say what is in it, change nothing. */
    data object Probe : RestoreSource
}

/** The one thing happening on the Backup screen, if anything: what its dialogs are made of. */
sealed interface BackupPhase {
    data object Idle : BackupPhase
    data class Busy(@StringRes val message: Int) : BackupPhase
    /** Enabling found a vault already there. [opened] is null when the passphrase did not open it. */
    data class Existing(val opened: OpenedVault?, val remoteSha: String, val credentials: Credentials, val passphrase: String) : BackupPhase
    data class Confirm(val opened: OpenedVault, val source: RestoreSource) : BackupPhase
    /** A vault this phone's key does not open: ask for the passphrase it was sealed with. */
    data class AskPassphrase(val bytes: ByteArray, val source: RestoreSource) : BackupPhase
    /** An export with no vault on: the file needs a passphrase of its own. */
    data class AskExportPassphrase(val uri: Uri) : BackupPhase
    /** A file opened to see whether it would work, and nothing else. */
    data class DryRun(val summary: dev.rwilco.vault.VaultSummary) : BackupPhase
    data class Failed(@StringRes val message: Int, val arg: String? = null) : BackupPhase
    data class Done(@StringRes val message: Int, val arg: String? = null) : BackupPhase
}

class BackupViewModel(private val app: RwilcoApplication) : ViewModel() {

    private val restore = VaultRestore(app)

    val state: StateFlow<VaultState?> = app.vaultStore.state
        .map<VaultState, VaultState?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val working: StateFlow<Boolean> = VaultCenter.activity
        .map { it.working }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** How many reminders a restore would replace. */
    val localCount: StateFlow<Int> = app.repository.rows
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val mutablePhase = MutableStateFlow<BackupPhase>(BackupPhase.Idle)
    val phase: StateFlow<BackupPhase> = mutablePhase

    private val mutableHasUndo = MutableStateFlow(restore.hasUndoCopy())
    val hasUndo: StateFlow<Boolean> = mutableHasUndo

    val form = MutableStateFlow(BackupForm())

    fun edit(transform: (BackupForm) -> BackupForm) = form.update(transform)

    fun dismiss() {
        mutablePhase.value = BackupPhase.Idle
    }

    /** The setup form's one button: check the repository, then create the vault or find one there. */
    fun enable() {
        val f = form.value
        val credentials = credentialsOf(f.repo, f.token) ?: return
        if (!passphraseIsStrongEnough(f.passphrase)) return fail(R.string.vault_error_passphrase_short)
        if (f.passphrase != f.again) return fail(R.string.vault_error_passphrase_mismatch)
        viewModelScope.launch {
            val transport = transportFor(credentials)
            val remote = probeAndRead(transport) ?: return@launch
            busy(R.string.vault_busy_deriving)
            if (remote.value == null) {
                val salt = VaultCrypto.newSalt()
                val key = derive(f.passphrase, salt, KDF_ITERATIONS)
                app.vaultStore.update {
                    VaultState(
                        enabled = true, owner = credentials.owner, repo = credentials.repo, pat = credentials.pat,
                        key = VaultState.encode(key), salt = VaultState.encode(salt), iterations = KDF_ITERATIONS,
                        deviceId = UUID.randomUUID().toString(),
                    )
                }
                form.value = BackupForm()
                VaultWorker.runNow(app)
                done(R.string.vault_done_enabled)
            } else {
                val opened = try {
                    restore.open(remote.value.bytes, f.passphrase)
                } catch (e: VaultException.WrongPassphrase) {
                    null
                } catch (e: VaultException) {
                    return@launch fail(messageOf(e))
                }
                mutablePhase.value = BackupPhase.Existing(opened, remote.value.sha, credentials, f.passphrase)
            }
        }
    }

    /** From [BackupPhase.Existing]: bring the copy that is there onto this phone. */
    fun restoreExisting() {
        val existing = mutablePhase.value as? BackupPhase.Existing ?: return
        val opened = existing.opened ?: return
        mutablePhase.value = BackupPhase.Confirm(opened, RestoreSource.Remote(existing.remoteSha, existing.credentials))
    }

    /** From [BackupPhase.Existing]: the copy there is replaced by this phone's data on the first upload. */
    fun replaceExisting() {
        val existing = mutablePhase.value as? BackupPhase.Existing ?: return
        viewModelScope.launch {
            busy(R.string.vault_busy_deriving)
            // The same passphrase opened it: keep its salt, so the file stays one vault. Otherwise
            // it is a new vault under a new salt, and the old one lives on in the history only.
            val salt = existing.opened?.salt ?: VaultCrypto.newSalt()
            val iterations = existing.opened?.iterations ?: KDF_ITERATIONS
            val key = existing.opened?.key ?: derive(existing.passphrase, salt, iterations)
            val credentials = existing.credentials
            app.vaultStore.update {
                VaultState(
                    enabled = true, owner = credentials.owner, repo = credentials.repo, pat = credentials.pat,
                    key = VaultState.encode(key), salt = VaultState.encode(salt), iterations = iterations,
                    deviceId = UUID.randomUUID().toString(), remoteSha = existing.remoteSha,
                )
            }
            form.value = BackupForm()
            VaultWorker.runNow(app)
            done(R.string.vault_done_enabled)
        }
    }

    /** From [BackupPhase.Confirm]: this phone becomes the copy. */
    fun confirmRestore() {
        val confirm = mutablePhase.value as? BackupPhase.Confirm ?: return
        viewModelScope.launch {
            busy(R.string.vault_busy_restoring)
            val opened = confirm.opened
            val now = app.clock.instant()
            try {
                restore.apply(opened) { state ->
                    when (val source = confirm.source) {
                        is RestoreSource.Remote -> {
                            val withCredentials = source.credentials?.let { state.copy(enabled = true, owner = it.owner, repo = it.repo, pat = it.pat) } ?: state
                            withCredentials.copy(
                                key = VaultState.encode(opened.key), salt = VaultState.encode(opened.salt), iterations = opened.iterations,
                                deviceId = state.deviceId.ifEmpty { UUID.randomUUID().toString() },
                                remoteSha = source.sha, lastAttemptSha = null,
                                lastUploadedFingerprint = opened.snapshot.fingerprint(), lastUploadedAt = state.lastUploadedAt,
                                lastOutcome = VaultOutcome.UP_TO_DATE, lastOutcomeAt = now,
                            )
                        }
                        // The content changed under the vault; the next run uploads it.
                        RestoreSource.File, RestoreSource.Undo, RestoreSource.Probe -> state.copy(lastUploadedFingerprint = null)
                    }
                }
            } catch (e: Exception) {
                return@launch fail(R.string.vault_error_restore)
            }
            form.value = BackupForm()
            mutableHasUndo.value = restore.hasUndoCopy()
            VaultNotifications.cancel(app)
            done(R.string.vault_done_restored)
        }
    }

    /** From [BackupPhase.AskPassphrase]: try the passphrase on the bytes waiting. */
    fun openWith(passphrase: String) {
        val ask = mutablePhase.value as? BackupPhase.AskPassphrase ?: return
        viewModelScope.launch {
            busy(R.string.vault_busy_deriving)
            val opened = try {
                restore.open(ask.bytes, passphrase)
            } catch (e: VaultException) {
                return@launch fail(messageOf(e))
            }
            mutablePhase.value = phaseFor(opened, ask.source)
        }
    }

    /** A rehearsal stops at what it found; anything else goes on to ask before it replaces. */
    private fun phaseFor(opened: OpenedVault, source: RestoreSource): BackupPhase =
        if (source == RestoreSource.Probe) BackupPhase.DryRun(opened.summary) else BackupPhase.Confirm(opened, source)

    fun backupNow() = VaultWorker.runNow(app)

    /** How often a copy is made when nobody asks; the next one is re-booked from the last good run. */
    fun setCadence(cadence: BackupCadence) {
        viewModelScope.launch { app.vaultStore.update { it.copy(cadence = cadence) } }
    }

    fun setWifiOnly(only: Boolean) {
        viewModelScope.launch { app.vaultStore.update { it.copy(wifiOnly = only) } }
    }

    /**
     * Does the repository exist, is it private, can this token write to it, and is there a copy
     * in it already — asked without writing a byte. The one thing somebody wants before they
     * hand an app a token and a passphrase: proof that the boring half works.
     */
    fun testConnection() {
        val current = state.value
        val credentials = if (current != null && current.enabled) {
            Credentials(current.owner, current.repo, current.pat)
        } else {
            val f = form.value
            credentialsOf(f.repo, f.token) ?: return
        }
        viewModelScope.launch {
            val remote = probeAndRead(transportFor(credentials)) ?: return@launch
            val bytes = remote.value?.bytes?.size?.toLong()
            if (bytes == null) {
                done(R.string.vault_test_ok_empty)
            } else {
                done(R.string.vault_test_ok_existing, Formatter.formatShortFileSize(app, bytes))
            }
        }
    }

    /** A rehearsal of an import: the file is opened and described, and nothing on the phone moves. */
    fun dryRunImport(uri: Uri) {
        viewModelScope.launch {
            val bytes = readFile(uri) ?: return@launch fail(R.string.vault_error_file)
            offer(bytes, RestoreSource.Probe, state.value)
        }
    }

    /** The vault on: read the copy from GitHub and offer it. */
    fun restoreFromRemote() {
        val state = state.value ?: return
        viewModelScope.launch {
            busy(R.string.vault_busy_probing)
            val remote = try {
                transportFor(Credentials(state.owner, state.repo, state.pat)).read()
            } catch (e: VaultTransportException) {
                return@launch fail(messageOf(e.failure))
            } ?: return@launch fail(R.string.vault_error_no_remote)
            offer(remote.bytes, RestoreSource.Remote(remote.sha, null), state)
        }
    }

    /** The vault on and stopped by a conflict: the next upload replaces whatever is there now. */
    fun overwriteRemote() {
        val state = state.value ?: return
        viewModelScope.launch {
            busy(R.string.vault_busy_probing)
            val remote = try {
                transportFor(Credentials(state.owner, state.repo, state.pat)).read()
            } catch (e: VaultTransportException) {
                return@launch fail(messageOf(e.failure))
            }
            app.vaultStore.update { it.copy(remoteSha = remote?.sha, lastUploadedFingerprint = null, lastAttemptSha = null, lastOutcome = null) }
            VaultNotifications.cancel(app)
            VaultWorker.runNow(app)
            dismiss()
        }
    }

    /** A new token, or a repository that moved: checked before it is kept. */
    fun updateCredentials(repo: String, token: String) {
        val credentials = credentialsOf(repo, token) ?: return
        viewModelScope.launch {
            probeAndRead(transportFor(credentials)) ?: return@launch
            app.vaultStore.update { it.copy(owner = credentials.owner, repo = credentials.repo, pat = credentials.pat, lastOutcome = null) }
            VaultNotifications.cancel(app)
            VaultWorker.runNow(app)
            dismiss()
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            val bytes = readFile(uri) ?: return@launch fail(R.string.vault_error_file)
            offer(bytes, RestoreSource.File, state.value)
        }
    }

    fun undoRestore() {
        viewModelScope.launch {
            val bytes = restore.readUndoCopy() ?: return@launch fail(R.string.vault_error_file)
            offer(bytes, RestoreSource.Undo, state.value)
        }
    }

    fun exportTo(uri: Uri) {
        val state = state.value
        if (state == null || !state.hasKey) {
            mutablePhase.value = BackupPhase.AskExportPassphrase(uri)
            return
        }
        viewModelScope.launch { export(uri, state.keyBytes(), state.saltBytes(), state.iterations) }
    }

    /** The plain-text copy, built fresh: what the phone holds, in its own sentences. */
    suspend fun readableExportText(): String {
        val settings = app.settingsStore.settings.first()
        val today = LocalDate.now(app.clock.zone)
        return readableExport(app.words(), app.repository.allNow(), today, settings.defaultTime, app.clock.zone)
    }

    /** Written as text where the picker said; no key, no salt, nothing to remember. */
    fun exportTextTo(uri: Uri) {
        viewModelScope.launch {
            val text = readableExportText()
            val written = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null }.getOrDefault(false)
            }
            if (written) done(R.string.vault_done_exported) else fail(R.string.vault_error_file)
        }
    }

    /** From [BackupPhase.AskExportPassphrase]: a file of its own, under its own salt. */
    fun exportWith(passphrase: String) {
        val ask = mutablePhase.value as? BackupPhase.AskExportPassphrase ?: return
        if (!passphraseIsStrongEnough(passphrase)) return fail(R.string.vault_error_passphrase_short)
        viewModelScope.launch {
            busy(R.string.vault_busy_deriving)
            val salt = VaultCrypto.newSalt()
            export(ask.uri, derive(passphrase, salt, KDF_ITERATIONS), salt, KDF_ITERATIONS)
        }
    }

    fun disable() {
        viewModelScope.launch {
            app.vaultStore.clear()
            VaultNotifications.cancel(app)
            form.value = BackupForm()
        }
    }

    /** Open [bytes] with the key on this phone if it fits, else ask for the passphrase. */
    private suspend fun offer(bytes: ByteArray, source: RestoreSource, state: VaultState?) {
        if (state != null && state.hasKey) {
            busy(R.string.vault_busy_deriving)
            val opened = try {
                restore.openWithKey(bytes, state)
            } catch (e: VaultException.WrongPassphrase) {
                null
            } catch (e: VaultException) {
                return fail(messageOf(e))
            }
            if (opened != null) {
                mutablePhase.value = phaseFor(opened, source)
                return
            }
        }
        mutablePhase.value = BackupPhase.AskPassphrase(bytes, source)
    }

    private suspend fun export(uri: Uri, key: ByteArray, salt: ByteArray, iterations: Int) {
        busy(R.string.vault_busy_sealing)
        val bytes = restore.sealNow(key, salt, iterations)
        val written = withContext(Dispatchers.IO) {
            runCatching { app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null }.getOrDefault(false)
        }
        if (written) done(R.string.vault_done_exported) else fail(R.string.vault_error_file)
    }

    private suspend fun readFile(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            app.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytes(MAX_IMPORT_BYTES + 1)
                bytes.takeIf { it.size <= MAX_IMPORT_BYTES }
            }
        }.getOrNull()
    }

    /** The repository checked before anything is written to it: private, and ours to write. Null after a [fail]. */
    private suspend fun probeAndRead(transport: VaultTransport): Holder? {
        busy(R.string.vault_busy_probing)
        return try {
            val info = transport.probe()
            if (!info.isPrivate) return fail(R.string.vault_error_not_private).let { null }
            if (!info.canPush) return fail(R.string.vault_error_no_push).let { null }
            Holder(transport.read())
        } catch (e: VaultTransportException) {
            fail(messageOf(e.failure))
            null
        }
    }

    /** A nullable wrapped, so "checked, and there is no file" reads apart from "the check failed". */
    private class Holder(val value: dev.rwilco.vault.RemoteVault?)

    private fun credentialsOf(repo: String, token: String): Credentials? {
        val parts = repo.trim().removePrefix("https://github.com/").removeSuffix(".git").split('/')
        if (parts.size != 2 || !isRepoName(parts[0]) || !isRepoName(parts[1])) {
            fail(R.string.vault_error_repo_name)
            return null
        }
        if (token.isBlank()) {
            fail(R.string.vault_error_token)
            return null
        }
        return Credentials(parts[0], parts[1], token.trim())
    }

    private fun transportFor(credentials: Credentials): VaultTransport =
        GitHubVault(credentials.owner, credentials.repo, credentials.pat, RwilcoApplication.USER_AGENT)

    private suspend fun derive(passphrase: String, salt: ByteArray, iterations: Int): ByteArray =
        withContext(Dispatchers.Default) { VaultCrypto.deriveKey(passphrase, salt, iterations) }

    private fun busy(@StringRes message: Int) {
        mutablePhase.value = BackupPhase.Busy(message)
    }

    private fun fail(@StringRes message: Int) {
        mutablePhase.value = BackupPhase.Failed(message)
    }

    private fun done(@StringRes message: Int, arg: String? = null) {
        mutablePhase.value = BackupPhase.Done(message, arg)
    }

    @StringRes
    private fun messageOf(failure: TransportFailure): Int = when (failure) {
        TransportFailure.AUTH -> R.string.vault_error_auth
        TransportFailure.REPO_MISSING -> R.string.vault_error_repo_missing
        TransportFailure.CONFLICT, TransportFailure.TRANSIENT -> R.string.vault_error_network
    }

    @StringRes
    private fun messageOf(e: VaultException): Int = when (e) {
        is VaultException.WrongPassphrase -> R.string.vault_error_wrong_passphrase
        is VaultException.Corrupt -> R.string.vault_error_corrupt
        is VaultException.NewerThanThisApp -> R.string.vault_error_newer
    }

    class Factory(private val app: RwilcoApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BackupViewModel(app) as T
    }

    private companion object {
        /** A vault is kilobytes; anything past this is not one. */
        const val MAX_IMPORT_BYTES = 64 * 1024 * 1024
    }
}
