package dev.rwilco.vault

import dev.rwilco.model.passphraseIsStrongEnough

/** What one backup run does next, once it knows everything it can learn without the network. */
enum class VaultStep {
    DISABLED,
    /** The content is what was last uploaded: no call, no commit. */
    NOTHING_CHANGED,
    UPLOAD,
}

fun nextVaultStep(enabled: Boolean, fingerprint: String, lastUploaded: String?): VaultStep = when {
    !enabled -> VaultStep.DISABLED
    fingerprint == lastUploaded -> VaultStep.NOTHING_CHANGED
    else -> VaultStep.UPLOAD
}

/**
 * Whether a part of the copy has gone from something to nothing: the one shape a run must not
 * carry up on its own.
 *
 * A run decides what to send by comparing fingerprints, and a fingerprint cannot tell a reminder
 * deleted on purpose from every reminder gone at once. Things can empty this phone without
 * anybody asking: a settings file the platform replaced with an empty one because it would not
 * parse, app data cleared, and until 0.167.0 a database dropped by
 * `fallbackToDestructiveMigrationOnDowngrade` when an older build was installed by hand over a
 * newer one. Either way the next run would copy the emptiness
 * faithfully over the one copy that still had everything.
 *
 * **Only nothing, never "less".** Zero is not somewhere ordinary use gets to — a reminder dealt
 * with stays as a row, and the settings blob is rewritten on the first launch of every build — so
 * there is no honest number between "fewer" and "gone" to put here, and a fraction would only buy
 * a class of false alarms. [was] null is a copy that never carried this part, which nothing can be
 * said about: the guard is inert until the first upload that writes the size down.
 */
fun wentEmpty(was: Int?, now: Int): Boolean = was != null && was > 0 && now == 0

/**
 * The vault pointed at [owner]/[repo] with [pat].
 *
 * The same repository — a new token, or the same name typed in another case, which GitHub does
 * not tell apart — keeps every cursor: the copy there is the one this phone last wrote. A
 * different one is a new home. What it holds now ([remoteSha], null when it is empty) is what
 * the next upload replaces, and nothing of this phone has been copied there yet, so the next run
 * uploads everything (0.166.0). Before, the old repository's sha and fingerprint came along: a run
 * with nothing changed called the new one up to date while it stayed empty, and the first change
 * went out carrying a sha the new repository had never seen. The sizes [wentEmpty] compares
 * against are kept: they are about this phone, not about where it copies to.
 */
fun VaultState.movedTo(owner: String, repo: String, pat: String, remoteSha: String?): VaultState {
    val pointed = copy(owner = owner, repo = repo, pat = pat, lastOutcome = null)
    return if (isRepository(owner, repo)) pointed else pointed.copy(remoteSha = remoteSha, lastUploadedFingerprint = null, lastAttemptSha = null)
}

/** Whether [owner]/[repo] is where this vault copies to now; GitHub names do not tell case apart. */
fun VaultState.isRepository(owner: String, repo: String): Boolean =
    owner.equals(this.owner, ignoreCase = true) && repo.equals(this.repo, ignoreCase = true)

/**
 * Whether "replace it with this phone" may be offered over a copy that is already there.
 *
 * Never from an empty phone: that is a new phone before its restore, and the tap would put
 * nothing over the one copy that has everything. And a copy the passphrase did not open is
 * replaced by a new vault under that passphrase, so the passphrase has to be one a new vault is
 * allowed — the rule is only waived for opening a copy that is already there.
 */
fun mayReplaceExisting(localRows: Int, opened: Boolean, passphrase: String): Boolean =
    localRows > 0 && (opened || passphraseIsStrongEnough(passphrase))

/**
 * What a refused upload means. The remote's blob sha is `git hash-object` of the bytes, which the
 * phone computed before sending them: equal to this attempt's means the PUT landed after its
 * reply was lost (OkHttp sent it twice); equal to the *previous run's* attempt means that run's
 * PUT landed after its reply was lost — every seal is fresh bytes, so the two never agree — and
 * the file is ours to write over; anything else means somebody else wrote the file.
 */
enum class ConflictVerdict { OURS_LANDED, EARLIER_LANDED, OTHER_WRITER }

fun judgeConflict(remoteSha: String?, lastAttemptSha: String?, earlierAttemptSha: String? = null): ConflictVerdict = when {
    remoteSha == null -> ConflictVerdict.OTHER_WRITER
    remoteSha == lastAttemptSha -> ConflictVerdict.OURS_LANDED
    remoteSha == earlierAttemptSha -> ConflictVerdict.EARLIER_LANDED
    else -> ConflictVerdict.OTHER_WRITER
}

/** How a GitHub reply that is not a success is to be taken. */
enum class TransportFailure {
    /** The token is wrong, expired, or cannot write this repository. Retrying changes nothing. */
    AUTH,
    /** No such repository (or it moved). Retrying changes nothing either. */
    REPO_MISSING,
    /** The file changed under us: read it and decide (see [judgeConflict]). */
    CONFLICT,
    /** Rate limit, server trouble, network: worth a retry with backoff. */
    TRANSIENT,
}

/**
 * Status to meaning. A 403 is the one that needs the headers: GitHub answers it both for a
 * spent rate limit (come back later) and for a fine-grained token without `contents: write`
 * (come back never). Redirects are not followed — a bearer token must not travel — so a 3xx is
 * a repository that moved.
 */
fun classifyGitHubStatus(code: Int, rateLimitRemaining: String?, retryAfter: String?): TransportFailure? = when {
    code in 200..299 -> null
    code in 300..399 -> TransportFailure.REPO_MISSING
    code == 401 -> TransportFailure.AUTH
    code == 403 -> if (rateLimitRemaining == "0" || retryAfter != null) TransportFailure.TRANSIENT else TransportFailure.AUTH
    code == 404 -> TransportFailure.REPO_MISSING
    code == 409 || code == 422 -> TransportFailure.CONFLICT
    else -> TransportFailure.TRANSIENT
}

private val REPO_NAME = Regex("[A-Za-z0-9._-]{1,100}")

/** An owner or repository name GitHub could have: what goes into a path segment. */
fun isRepoName(name: String): Boolean = REPO_NAME.matches(name) && name != "." && name != ".."
