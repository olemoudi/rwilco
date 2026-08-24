package dev.rwilco.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One snackbar at a time. A new message replaces the one still showing, so five quick swipes
 * do not queue five "Deleted" messages that outlive the list they talk about.
 */
@Stable
class SnackbarController(private val host: SnackbarHostState, private val scope: CoroutineScope) {
    fun show(message: String, undoLabel: String? = null, onUndo: (() -> Unit)? = null) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val result = host.showSnackbar(
                message = message,
                actionLabel = undoLabel,
                withDismissAction = undoLabel == null,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onUndo?.invoke()
        }
    }
}

val LocalSnackbar = staticCompositionLocalOf<SnackbarController> {
    error("No SnackbarController in scope: the screen is not inside RwilcoApp's host")
}

@Composable
fun rememberSnackbarController(host: SnackbarHostState): SnackbarController {
    val scope = rememberCoroutineScope()
    return remember(host, scope) { SnackbarController(host, scope) }
}
