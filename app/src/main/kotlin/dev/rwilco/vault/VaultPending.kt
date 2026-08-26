package dev.rwilco.vault

import dev.rwilco.data.ReminderEntity
import java.time.Instant

/** The settings blob, as one line, so "has it changed since the last copy" is one comparison. */
fun settingsHash(settingsJson: String): String = VaultCrypto.sha256Hex(settingsJson.toByteArray(Charsets.UTF_8))

/**
 * How much is waiting to be copied, as a number somebody can read off a badge.
 *
 * The fingerprint is the truth about *whether* anything is waiting — it is what the run itself
 * decides by — and this counts what: reminders written or edited since the last copy, plus one
 * for the settings (a preset, a place, a sound) if those moved. A deletion changes the
 * fingerprint and leaves nothing to count, so anything the fingerprint calls a change is worth
 * at least one: a badge that says nothing while a copy is owed is worse than one that says "1".
 */
fun pendingChanges(rows: List<ReminderEntity>, settingsJson: String, state: VaultState): Int {
    if (!state.enabled) return 0
    if (fingerprint(rows, settingsJson) == state.lastUploadedFingerprint) return 0
    val since = state.lastUploadedAt ?: return maxOf(1, rows.size)
    val edited = rows.count { Instant.ofEpochMilli(it.updatedAt).isAfter(since) }
    val settingsMoved = state.lastUploadedSettingsHash != null && settingsHash(settingsJson) != state.lastUploadedSettingsHash
    return maxOf(1, edited + if (settingsMoved) 1 else 0)
}
