package dev.rwilco.vault

import dev.rwilco.data.FiringEventEntity
import dev.rwilco.data.ReminderEntity
import dev.rwilco.model.ReminderCodec
import dev.rwilco.model.backupFreshness
import dev.rwilco.model.holdsNothingOfTheirOwn
import dev.rwilco.model.backupNoticeDue
import java.time.Instant
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
 * path included — is a JVM test. A run that finds nothing changed uploads nothing and only looks
 * at the repository ([stillThere]); one that uploads writes the blob sha down *before* sending, so a reply lost on the way back is
 * recognised on the next attempt instead of being read as somebody else's write. And a run whose
 * snapshot has gone empty where the last copy was not stops without a call at all ([wentEmpty]):
 * the copy is the thing that outlives the phone, so a phone that has lost everything must not be
 * able to say so on its own.
 */
class VaultBackup(
    private val store: VaultStateStore,
    private val rows: suspend () -> List<ReminderEntity>,
    /** Every reminder's history, which travels with the rows (see [VaultSnapshot.events]). */
    private val events: suspend () -> List<FiringEventEntity>,
    private val settingsJson: suspend () -> String?,
    private val transportFor: (VaultState) -> VaultTransport,
    private val clock: Clock,
    private val appVersionCode: Int,
    private val dbVersion: Int,
    /** Something happened that a run cannot fix — say so where the person will see it. */
    private val onAttention: (VaultOutcome) -> Unit = {},
    /** The last upload went through: whatever was being said can come down. */
    private val onResolved: () -> Unit = {},
    /**
     * Nothing has gone through for a long time and changes are waiting. Not an outcome — the one
     * this happens under is a network failure, which every single run is right to retry in
     * silence; what is worth saying is that the silence has gone on. [Instant] is when the remote
     * last had this phone's data.
     */
    private val onStale: (Instant) -> Unit = {},
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
        val settings = settingsJson().orEmpty()
        val bare = buildSnapshot(rows(), settings, clock.instant(), state.deviceId, appVersionCode, dbVersion)
        val print = bare.fingerprint()
        when (nextVaultStep(state.enabled, print, state.lastUploadedFingerprint)) {
            VaultStep.DISABLED -> return VaultRunResult.DONE
            VaultStep.NOTHING_CHANGED -> return stillThere(transportFor(state), state)
            VaultStep.UPLOAD -> Unit
        }
        // Null when the blob will not read at all: it travels as it is, which keeps it, and there
        // is nothing to say about what is in it.
        val own = ReminderCodec.decodeSettingsOrNull(settings)?.let { !it.holdsNothingOfTheirOwn() }
        val sent = Sent(print, settingsHash(settings), bare.reminders.size, settings.length, own)
        // Something became nothing. A fingerprint cannot tell that from a deletion, so this is
        // the only place it can be caught — before the bytes are sealed, and before the one copy
        // that still has everything is replaced by the phone that has lost it.
        if (wentEmpty(state.lastUploadedRows, sent.rows) || wentEmpty(state.lastUploadedSettingsLength, sent.settingsLength) ||
            settingsWentBare(state.lastUploadedSettingsOwn, bareNow = own == false)
        ) {
            log("what this phone holds went empty (${state.lastUploadedRows} rows to ${sent.rows}, ${state.lastUploadedSettingsLength} to ${sent.settingsLength} of settings, own ${state.lastUploadedSettingsOwn} to $own); not copying that up")
            return attention(VaultOutcome.COLLAPSED)
        }
        // The history only for a copy that is going: it does not decide whether one goes (see
        // [fingerprint]), and a look that finds nothing to copy has no use for a thousand lines a reminder.
        val snapshot = bare.copy(events = events().sortedBy { it.id })
        VaultCenter.report(working = true)
        try {
            val bytes = VaultCrypto.seal(encodeSnapshot(snapshot), state.keyBytes(), state.saltBytes(), state.iterations)
            val sha = VaultCrypto.gitBlobSha(bytes)
            // The previous run's attempt, kept for the conflict below: overwritten before it was
            // ever compared, a PUT whose reply was lost read as somebody else's write next time.
            val earlier = state.lastAttemptSha
            store.update { if (it.enabled) it.copy(lastAttemptSha = sha) else it }
            return upload(transportFor(state), bytes, sha, state.remoteSha, sent, earlier)
        } finally {
            VaultCenter.report(working = false)
        }
    }

    /**
     * Nothing to copy, and one look at the repository anyway (0.166.0): the one GET that says it
     * is still there and still ours to write. Without it a repository taken away — deleted, the
     * account closed, the token expired — went unnoticed for as long as nothing on the phone
     * changed, with the screen saying "up to date" over a copy that no longer existed.
     *
     * Only the refusals mean anything here. A look that could not get through is not a copy that
     * failed — nothing was waiting to go — so it is written down as the look that found nothing
     * to copy, which is what the cadence counts from, and no "has not gone through" is said.
     * A look that passes after a refusal takes the refusal's notice down.
     */
    private suspend fun stillThere(transport: VaultTransport, state: VaultState): VaultRunResult {
        val info = try {
            transport.probe()
        } catch (e: VaultTransportException) {
            log("the look at the repository was refused: ${e.failure} (${e.message})")
            return when (e.failure) {
                TransportFailure.AUTH -> attention(VaultOutcome.AUTH)
                TransportFailure.REPO_MISSING -> attention(VaultOutcome.REPO_MISSING)
                TransportFailure.CONFLICT, TransportFailure.TRANSIENT -> {
                    // Whatever was said before stands: a look that did not get through has
                    // nothing to add to a refusal, nor anything to take back from one.
                    record(state.lastOutcome ?: VaultOutcome.UP_TO_DATE, ran = true)
                    VaultRunResult.DONE
                }
            }
        }
        if (!info.canPush) return attention(VaultOutcome.AUTH)
        // A look that found nothing to copy is a run that worked: the cadence counts from it, or
        // an untouched phone would ask again every few minutes for ever.
        record(VaultOutcome.UP_TO_DATE, ran = true)
        if (state.lastOutcome == VaultOutcome.AUTH || state.lastOutcome == VaultOutcome.REPO_MISSING) onResolved()
        return VaultRunResult.DONE
    }

    private suspend fun upload(transport: VaultTransport, bytes: ByteArray, sha: String, replacing: String?, sent: Sent, earlier: String?): VaultRunResult {
        try {
            val stored = transport.write(bytes, replacing)
            if (stored != sha) {
                // GitHub hashed something other than what was sent. Not a state anybody can act
                // on, and not one to build on either: the next run sends the bytes again.
                log("stored sha $stored is not the sent $sha")
                record(VaultOutcome.TRANSIENT)
                return VaultRunResult.RETRY
            }
            uploaded(sent, stored, bytes.size.toLong())
            return VaultRunResult.DONE
        } catch (e: VaultTransportException) {
            log("upload refused: ${e.failure} (${e.message})")
            return when (e.failure) {
                TransportFailure.CONFLICT -> conflict(transport, bytes, sha, earlier, sent)
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
    private suspend fun conflict(transport: VaultTransport, bytes: ByteArray, sha: String, earlier: String?, sent: Sent): VaultRunResult {
        val remote = try {
            transport.read()
        } catch (e: VaultTransportException) {
            log("conflict, and the remote would not read: ${e.failure}")
            record(VaultOutcome.TRANSIENT)
            return VaultRunResult.RETRY
        }
        return when (judgeConflict(remote?.sha, sha, earlier)) {
            ConflictVerdict.OURS_LANDED -> {
                log("conflict was our own earlier upload landing; adopting it")
                uploaded(sent, remote!!.sha, bytes.size.toLong())
                VaultRunResult.DONE
            }
            ConflictVerdict.EARLIER_LANDED -> {
                // The previous run's bytes are up there, not these: the file is ours to write
                // over, once, with the sha it actually has. Written down first, so a reply lost
                // on THIS attempt is still judged against the right remote next time.
                log("conflict was the previous run's upload landing after its reply was lost; writing over it")
                val remoteSha = remote!!.sha
                store.update { if (it.enabled) it.copy(remoteSha = remoteSha) else it }
                upload(transport, bytes, sha, remoteSha, sent, earlier = null)
            }
            ConflictVerdict.OTHER_WRITER -> attention(VaultOutcome.CONFLICT)
        }
    }

    private suspend fun uploaded(sent: Sent, sha: String, bytes: Long) {
        val now = clock.instant()
        store.update {
            if (!it.enabled) it
            else it.copy(
                lastUploadedFingerprint = sent.print,
                lastUploadedAt = now,
                lastRunAt = now,
                lastUploadedBytes = bytes,
                lastUploadedSettingsHash = sent.settingsHash,
                lastUploadedRows = sent.rows,
                lastUploadedSettingsLength = sent.settingsLength,
                lastUploadedSettingsOwn = sent.settingsOwn,
                remoteSha = sha,
                lastOutcome = VaultOutcome.UPLOADED,
                lastOutcomeAt = now,
            )
        }
        VaultCenter.succeeded()
        onResolved()
    }

    private suspend fun attention(outcome: VaultOutcome): VaultRunResult {
        record(outcome)
        onAttention(outcome)
        return VaultRunResult.FAILED
    }

    /** [ran] is a run that came to something: what the cadence counts from. */
    private suspend fun record(outcome: VaultOutcome, ran: Boolean = false) {
        val now = clock.instant()
        // A run finishing after "off" must not write a cursor into an empty store.
        store.update {
            if (!it.enabled) it
            else it.copy(lastOutcome = outcome, lastOutcomeAt = now, lastRunAt = if (ran) now else it.lastRunAt)
        }
        if (ran) return
        // A run that came to nothing leaves changes waiting — it only got this far because the
        // fingerprint said there were some — so this is the one place that can see how long they
        // have been waiting. Said once per window, and written down so it stays once.
        val state = store.read()
        if (!state.enabled) return
        val freshness = backupFreshness(state.enabled, state.lastRunAt, pending = 1, cadence = state.cadence, now = now)
        if (!backupNoticeDue(freshness, state.lastStaleNoticeAt, state.cadence, now)) return
        store.update { if (it.enabled) it.copy(lastStaleNoticeAt = now) else it }
        onStale(state.lastRunAt ?: now)
    }

    /** What one run is carrying: everything a successful upload writes down about the content. */
    private class Sent(val print: String, val settingsHash: String, val rows: Int, val settingsLength: Int, val settingsOwn: Boolean?)

    companion object {
        /** Process-wide: runs come from the worker, the button and a restore, and must not overlap. */
        internal val lock = Mutex()
    }
}
