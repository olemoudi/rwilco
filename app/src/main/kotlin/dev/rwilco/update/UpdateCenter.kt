package dev.rwilco.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the self-update machinery is doing right now, for the settings UI. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val installedVersionCode: Int) : UpdateUiState
    data class Downloading(val target: UpdateInfo) : UpdateUiState
    /** The bytes are here and checked; the install session is being committed. */
    data class Installing(val target: UpdateInfo) : UpdateUiState
    /** Waiting for the person to accept the system install dialog. */
    data class PendingConfirmation(val target: UpdateInfo?) : UpdateUiState
    data class Failed(val step: String) : UpdateUiState
}

/**
 * Process-wide update status. [Updater] and [InstallReceiver] write; the settings UI reads.
 * A plain singleton (no DI): update checks run from several entry points (launch, periodic
 * worker, the button) and all should feed the same status line.
 */
object UpdateCenter {
    private val mutable = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = mutable

    /**
     * The build the current attempt is aiming at. [InstallReceiver] is told a status and nothing
     * else — not even which session it belongs to — so without this the "waiting for you to
     * confirm" line could not name the version the person is being asked about.
     */
    @Volatile
    private var target: UpdateInfo? = null

    /**
     * Whether the run in flight was asked for by hand (the button, or "install what is here").
     * Only then does [InstallReceiver] bring the system's install dialog up by itself: the
     * periodic check used to drop it on top of the editor mid-sentence (0.68.0). In the
     * background the notification asks, and the tap is the person choosing the moment.
     */
    @Volatile
    var manual: Boolean = false

    internal fun report(state: UpdateUiState) {
        targetOf(state)?.let { target = it }
        mutable.value = state
    }

    internal fun lastTarget(): UpdateInfo? = target

    private fun targetOf(state: UpdateUiState): UpdateInfo? = when (state) {
        is UpdateUiState.Downloading -> state.target
        is UpdateUiState.Installing -> state.target
        is UpdateUiState.PendingConfirmation -> state.target
        else -> null
    }
}
