package dev.rwilco.vault

/** The remote file as it stands: its bytes and the blob sha that identifies this version of it. */
class RemoteVault(val bytes: ByteArray, val sha: String)

/** What the repository looks like from here, before anything is written to it. */
class RepoInfo(val isPrivate: Boolean, val canPush: Boolean)

/** A refusal with its meaning attached; [TransportFailure] says whether trying again can help. */
class VaultTransportException(val failure: TransportFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Where the vault lives. One file, read and replaced whole, with optimistic concurrency on its
 * sha. GitHub is the first of these; a second would go behind the same three calls.
 */
interface VaultTransport {
    /** The file, or null when there is none yet. */
    suspend fun read(): RemoteVault?

    /**
     * Replace the file with [bytes], provided it still is the version [replacingSha] (null to
     * create). Returns the blob sha of what was stored — `git hash-object` of [bytes], so the
     * caller can check it — or throws [VaultTransportException] with [TransportFailure.CONFLICT]
     * when the file moved under it.
     */
    suspend fun write(bytes: ByteArray, replacingSha: String?): String

    suspend fun probe(): RepoInfo
}

/** The one file the app keeps at the remote. */
const val VAULT_FILE = "rwilco.vault"
