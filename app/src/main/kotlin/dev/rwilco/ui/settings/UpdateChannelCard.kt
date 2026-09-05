package dev.rwilco.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.rwilco.BuildConfig
import dev.rwilco.R
import dev.rwilco.model.ChannelSwitch
import dev.rwilco.model.UpdateChannel
import dev.rwilco.model.channelSwitch
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.SegmentedChoice
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.update.UpdateInfo
import dev.rwilco.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Which stream of builds this phone follows, and what choosing the other one would really do.
 *
 * A segmented choice rather than a switch, because neither option is the absence of the other:
 * "off" would have to mean beta, and a switch labelled with one channel cannot say what the
 * other one is.
 *
 * The line underneath is the honest part. Going to alpha happens at the next check; coming back
 * does not — Android will not install an older version over a newer one and there is no way to
 * ask it nicely, so the phone rejoins beta when beta passes it. Said before the choice rather
 * than discovered after it, and said again afterwards for as long as it is true, or the control
 * looks dead for the days it takes.
 */
@Composable
fun UpdateChannelCard(channel: UpdateChannel, onChannel: (UpdateChannel) -> Unit) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    var confirming by rememberSaveable { mutableStateOf(false) }

    // What the chosen channel currently serves. Read when the card appears and whenever the
    // choice changes: it is one small document, and without it the notice below cannot be
    // told apart from "no idea yet" — which is what ChannelSwitch.Unknown says instead.
    var offer by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(channel) {
        offer = withContext(Dispatchers.IO) { runCatching { Updater(context).fetchInfo(channel) }.getOrNull() }
    }

    RwilcoCard {
        Column(Modifier.padding(spacing.lg)) {
            SettingTitle(
                title = stringResource(R.string.settings_channel),
                info = stringResource(channelHint(channel)),
            )
            Spacer(Modifier.height(spacing.sm))
            SegmentedChoice(
                options = listOf(
                    stringResource(R.string.settings_channel_beta),
                    stringResource(R.string.settings_channel_alpha),
                ),
                selectedIndex = UpdateChannel.entries.indexOf(channel),
                // Only the way in asks. Choosing beta is choosing the safer of the two, and
                // interrupting that would be a dialog for its own sake.
                onSelect = { index ->
                    val chosen = UpdateChannel.entries[index]
                    if (chosen == UpdateChannel.ALPHA) confirming = true else onChannel(chosen)
                },
            )
            val switch = channelSwitch(
                installedVersionCode = BuildConfig.VERSION_CODE,
                channelVersionCode = offer?.versionCode ?: 0,
                channelVersionName = offer?.versionName.orEmpty(),
            )
            // The one case the choice cannot make true on its own: a phone on an alpha build
            // that has asked to come back.
            if (channel == UpdateChannel.BETA && switch is ChannelSwitch.WaitsForNextRelease) {
                Spacer(Modifier.height(spacing.sm))
                Text(
                    text = stringResource(R.string.settings_channel_waiting, switch.channelVersionName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(spacing.xs))
                Text(
                    text = stringResource(R.string.settings_channel_waiting_manual),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.settings_channel_warn_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_channel_warn_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(spacing.md))
                    // The asymmetry, said before the choice rather than discovered after it.
                    Text(
                        text = stringResource(R.string.settings_channel_warn_back),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            // Coloured like the app's other dialogs rather than left at Material's default,
            // which paints both of these amber — and amber here means one thing: what fires next.
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onChannel(UpdateChannel.ALPHA)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                ) { Text(stringResource(R.string.settings_channel_warn_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirming = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) { Text(stringResource(R.string.sheet_cancel)) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge,
        )
    }
}

private fun channelHint(channel: UpdateChannel) = when (channel) {
    UpdateChannel.BETA -> R.string.settings_channel_beta_hint
    UpdateChannel.ALPHA -> R.string.settings_channel_alpha_hint
}
