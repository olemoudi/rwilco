package dev.rwilco.vault

import dev.rwilco.model.BackupCadence
import dev.rwilco.model.DEFAULT_BACKUP_CADENCE
import dev.rwilco.model.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.Base64

/** How the last run ended; the three in the middle are the ones somebody has to act on. */
enum class VaultOutcome {
    UPLOADED,
    UP_TO_DATE,
    /** The token is wrong, expired, or cannot write the repository. */
    AUTH,
    /** The repository is gone, or moved. */
    REPO_MISSING,
    /** Somebody else wrote the vault: the phone stopped rather than write over it. */
    CONFLICT,
    /** Network or server trouble; the next run tries again. */
    TRANSIENT,
}

/**
 * Everything the vault knows about itself, in its own store — never inside the settings, which
 * are part of what it backs up. The passphrase is not here: only the key it derives to, next to
 * the salt it was derived with, so a run in the background can seal a snapshot without asking
 * and a new phone cannot open one without being asked.
 */
@Serializable
data class VaultState(
    val enabled: Boolean = false,
    val owner: String = "",
    val repo: String = "",
    val pat: String = "",
    /** The derived key, base64. */
    val key: String = "",
    /** The vault's salt, base64: minted with the vault, adopted from it on every other phone. */
    val salt: String = "",
    val iterations: Int = KDF_ITERATIONS,
    /** This install, for the preview on another phone; random, minted with the vault. */
    val deviceId: String = "",
    /** How often a copy is made when nobody asks for one; the clock starts at [lastRunAt]. */
    val cadence: BackupCadence = DEFAULT_BACKUP_CADENCE,
    /** Only over wifi: a copy is kilobytes, but it is somebody else's data plan to spend. */
    val wifiOnly: Boolean = false,
    val lastUploadedFingerprint: String? = null,
    @Serializable(with = InstantSerializer::class) val lastUploadedAt: Instant? = null,
    /**
     * The last run that came to something — a copy made, or a look that found nothing to copy.
     * What the cadence counts from, so a run that failed never moves the next one closer.
     */
    @Serializable(with = InstantSerializer::class) val lastRunAt: Instant? = null,
    /** What the last copy weighed, so the data it costs is a number somebody can see. */
    val lastUploadedBytes: Long? = null,
    /** The settings blob as it was in the last copy, for counting what has changed since. */
    val lastUploadedSettingsHash: String? = null,
    /** The blob sha the remote file had after our last successful write; what the next PUT replaces. */
    val remoteSha: String? = null,
    /** The blob sha of the bytes last sent, written down before sending (see [judgeConflict]). */
    val lastAttemptSha: String? = null,
    val lastOutcome: VaultOutcome? = null,
    @Serializable(with = InstantSerializer::class) val lastOutcomeAt: Instant? = null,
) {
    val hasKey: Boolean get() = key.isNotEmpty() && salt.isNotEmpty()

    /** Something a run cannot fix by running again. */
    val needsAttention: Boolean get() = lastOutcome == VaultOutcome.AUTH || lastOutcome == VaultOutcome.REPO_MISSING || lastOutcome == VaultOutcome.CONFLICT

    fun keyBytes(): ByteArray = Base64.getDecoder().decode(key)

    fun saltBytes(): ByteArray = Base64.getDecoder().decode(salt)

    companion object {
        fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    }
}

/** What [VaultBackup] needs of the store, so the run can be tested against memory. */
interface VaultStateStore {
    suspend fun read(): VaultState
    suspend fun update(transform: (VaultState) -> VaultState)
}
