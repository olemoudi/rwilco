package dev.rwilco.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether a run is sealing or uploading right now, for the Backup card. Everything else the
 * card says — the last copy, the last outcome — is in [VaultState] and survives the process;
 * this is the one thing that must not, because a process that died mid-run is not working.
 */
object VaultCenter {
    private val mutable = MutableStateFlow(false)
    val working: StateFlow<Boolean> = mutable

    internal fun report(working: Boolean) {
        mutable.value = working
    }
}
