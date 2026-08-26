package dev.rwilco.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether a run is sealing or uploading right now, for the Backup card. Everything else the
 * card says — the last copy, the last outcome — is in [VaultState] and survives the process;
 * this is the one thing that must not, because a process that died mid-run is not working.
 */
object VaultCenter {
    private val mutable = MutableStateFlow(VaultActivity())
    val activity: StateFlow<VaultActivity> = mutable

    internal fun report(working: Boolean) {
        mutable.value = mutable.value.copy(working = working)
    }

    /** A copy went up. The count is what a screen watches to show its tick once per copy. */
    internal fun succeeded() {
        mutable.value = mutable.value.copy(copies = mutable.value.copies + 1)
    }
}

/** What the backup is doing right now, and how many copies this process has seen go up. */
data class VaultActivity(val working: Boolean = false, val copies: Int = 0)
