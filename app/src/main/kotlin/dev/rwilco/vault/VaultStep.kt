package dev.rwilco.vault

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
