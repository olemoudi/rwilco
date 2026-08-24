package dev.rwilco.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rwilco.BuildConfig
import dev.rwilco.R
import dev.rwilco.ui.components.PermissionFixRow
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.update.UpdateCenter
import dev.rwilco.update.UpdateInfo
import dev.rwilco.update.UpdateUiState
import dev.rwilco.update.UpdateWorker
import dev.rwilco.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App version, manual update check and self-update diagnostics. When a permission blocks
 * self-updating, it names the problem and deep-links into the fix. It is also the way back when
 * an update was dismissed: the APK already on disk is offered, with no network needed.
 */
@Composable
fun AppUpdateCard() {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val updateState by UpdateCenter.state.collectAsStateWithLifecycle()

    // Re-check permissions when the person comes back from the settings screens we open.
    var canInstall by remember { mutableStateOf(true) }
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canInstall = context.packageManager.canRequestPackageInstalls()
                resumeTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The update already downloaded and waiting, if there is one. Re-read whenever the status
    // moves and whenever the screen comes back. Off the main thread: it parses a big archive.
    var staged by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(updateState, resumeTick) {
        staged = withContext(Dispatchers.IO) { Updater(context).stagedUpdate() }
    }

    RwilcoCard {
        Column(Modifier.padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        updateStatusText(updateState, staged),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!canInstall) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_install_missing),
                    action = stringResource(R.string.perm_install_fix),
                    onFix = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                        )
                    },
                )
            }

            staged?.let { pending ->
                Spacer(Modifier.size(spacing.md))
                Button(
                    onClick = { UpdateWorker.installStagedNow(context) },
                    enabled = updateState !is UpdateUiState.Installing,
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Tokens.sizes.control),
                ) { Text(stringResource(R.string.update_install_staged, pending.versionName)) }
            }

            Spacer(Modifier.size(spacing.md))
            OutlinedButton(
                onClick = { UpdateWorker.checkNow(context) },
                enabled = updateState !is UpdateUiState.Checking &&
                    updateState !is UpdateUiState.Downloading &&
                    updateState !is UpdateUiState.Installing,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.sizes.control),
            ) { Text(stringResource(R.string.update_check_now)) }
        }
    }
}

@Composable
private fun updateStatusText(state: UpdateUiState, staged: UpdateInfo?): String {
    // A downloaded-and-waiting update outranks the quiet states: "checked automatically" printed
    // directly above a button offering to install one reads as a contradiction.
    if (staged != null && (state is UpdateUiState.Idle || state is UpdateUiState.UpToDate)) {
        return stringResource(R.string.update_state_staged, staged.versionName)
    }
    return when (state) {
        is UpdateUiState.Idle -> stringResource(R.string.update_state_idle)
        is UpdateUiState.Checking -> stringResource(R.string.update_state_checking)
        is UpdateUiState.UpToDate -> stringResource(R.string.update_state_up_to_date)
        is UpdateUiState.Downloading -> stringResource(R.string.update_state_downloading, state.target.versionName)
        is UpdateUiState.Installing -> stringResource(R.string.update_state_installing, state.target.versionName)
        is UpdateUiState.PendingConfirmation -> stringResource(R.string.update_state_pending)
        is UpdateUiState.Failed -> stringResource(R.string.update_state_failed, state.step)
    }
}
