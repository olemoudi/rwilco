package dev.rwilco.vault

import dev.rwilco.data.ReminderEntity
import kotlinx.coroutines.sync.Mutex
import java.time.Clock

/** How one run ended, for the worker to turn into a WorkManager answer. */
enum class VaultRunResult {
    DONE,
    /** Network or server trouble: worth trying again with backoff. */
    RETRY,
    /** Somebody has to act (see [VaultState.needsAttention]); trying again changes nothing. */
    FAILED,
    /** Another run holds the lock; this one stood aside. */
    BUSY,
}

/**
 * One backup run: snapshot, compare, seal, upload, and write down what happened.
 *
 * Everything it touches comes in through the constructor so the whole run — the conflict
 * path included — is a JVM test. A run that finds nothing changed makes no call at all; one
 * that uploads writes the blob sha down *before* sending, so a reply lost on the way back is
 * recognised on the next attempt instead of being read as somebody else's write.
 */
class VaultBackup(
    private val store: VaultStateStore,
    private val rows: suspend () -> List<ReminderEntity>,
    private val settingsJson: suspend () -> String?,
    private val transportFor: (VaultState) -> VaultTransport,
    private val clock: Clock,
    private val appVersionCode: Int,
    private val dbVersion: Int,
    /** Something happened that a run cannot fix — say so where the person will see it. */
    private val onAttention: (VaultOutcome) -> Unit = {},
    /** The last upload went through: whatever was being said can come down. */
    private val onResolved: () -> Unit = {},
    private val log: (String) -> Unit = {},
) {

    suspend fun run(): VaultRunResult {
        if (!lock.tryLock()) return VaultRunResult.BUSY
        try {
            return doRun()
        } finally {
            lock.unlock()
        }
    }

    private suspend fun doRun(): VaultRunResult {
        val state = store.read()
        if (!state.enabled || !state.hasKey) return VaultRunResult.DONE
        val snapshot = buildSnapshot(rows(), settingsJson().orEmpty(), clock.instant(), state.deviceId, appVersionCode, dbVersion)
        val print = snapshot.fingerprint()
        when (nextVaultStep(state.enabled, print, state.lastUploadedFingerprint)) {
            VaultStep.DISABLED -> return VaultRunResult.DONE
            VaultStep.NOTHING_CHANGED -> {
                record(VaultOutcome.UP_TO_DATE)
                return VaultRunResult.DONE
            }
            VaultStep.UPLOAD -> Unit
        }
        VaultCenter.report(working = true)
        try {
            val bytes = VaultCrypto.seal(encodeSnapshot(snapshot), state.keyBytes(), state.saltBytes(), state.iterations)
            val sha = VaultCrypto.gitBlobSha(bytes)
            store.update { if (it.enabled) it.copy(lastAttemptSha = sha) else it }
            return upload(transportFor(state), bytes, sha, state.remoteSha, print)
        } finally {
            VaultCenter.report(working = false)
        }
    }

    private suspend fun upload(transport: VaultTransport, bytes: ByteArray, sha: String, replacing: String?, print: String): VaultRunResult {
        try {
            val stored = transport.write(bytes, replacing)
            if (stored != sha) {
                // GitHub hashed something other than what was sent. Not a state anybody can act
                // on, and not one to build on either: the next run sends the bytes again.
                log("stored sha $stored is not the sent $sha")
                record(VaultOutcome.TRANSIENT)
                return VaultRunResult.RETRY
            }
            uploaded(print, stored)
            return VaultRunResult.DONE
        } catch (e: VaultTransportException) {
            log("upload refused: ${e.failure} (${e.message})")
            return when (e.failure) {
                TransportFailure.CONFLICT -> conflict(transport, sha, print)
                TransportFailure.AUTH -> attention(VaultOutcome.AUTH)
                TransportFailure.REPO_MISSING -> attention(VaultOutcome.REPO_MISSING)
                TransportFailure.TRANSIENT -> {
                    record(VaultOutcome.TRANSIENT)
                    VaultRunResult.RETRY
                }
            }
        }
    }

    /** The file moved under us. Ours after all, or somebody else's — the sha says which. */
    private suspend fun conflict(transport: VaultTransport, sha: String, print: String): VaultRunResult {
        val remote = try {
            transport.read()
        } catch (e: VaultTransportException) {
            log("conflict, and the remote would not read: ${e.failure}")
            record(VaultOutcome.TRANSIENT)
            return VaultRunResult.RETRY
        }
        return when (judgeConflict(remote?.sha, sha)) {
            ConflictVerdict.OURS_LANDED -> {
                log("conflict was our own earlier upload landing; adopting it")
                uploaded(print, remote!!.sha)
                VaultRunResult.DONE
            }
            ConflictVerdict.OTHER_WRITER -> attention(VaultOutcome.CONFLICT)
        }
    }

    private suspend fun uploaded(print: String, sha: String) {
        val now = clock.instant()
        store.update {
            if (!it.enabled) it
            else it.copy(lastUploadedFingerprint = print, lastUploadedAt = now, remoteSha = sha, lastOutcome = VaultOutcome.UPLOADED, lastOutcomeAt = now)
        }
        onResolved()
    }

    private suspend fun attention(outcome: VaultOutcome): VaultRunResult {
        record(outcome)
        onAttention(outcome)
        return VaultRunResult.FAILED
    }

    private suspend fun record(outcome: VaultOutcome) {
        val now = clock.instant()
        // A run finishing after "off" must not write a cursor into an empty store.
        store.update { if (it.enabled) it.copy(lastOutcome = outcome, lastOutcomeAt = now) else it }
    }

    companion object {
        /** Process-wide: runs come from the worker, the button and a restore, and must not overlap. */
        private val lock = Mutex()
    }
}
