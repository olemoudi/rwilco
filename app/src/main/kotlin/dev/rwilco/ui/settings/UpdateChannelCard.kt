package dev.rwilco.ui.settings

import androidx.annotation.StringRes
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

    // What the chosen channel currently serves, read when the card appears and whenever the
    // choice changes. Three states and not two, because "the manifest says nothing is published
    // here" and "we could not read the manifest" are different sentences to somebody who has
    // just tapped a control and is looking for evidence that it did anything.
    var reading by remember { mutableStateOf(true) }
    var offer by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(channel) {
        reading = true
        offer = withContext(Dispatchers.IO) { runCatching { Updater(context).fetchInfo(channel) }.getOrNull() }
        reading = false
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
            // Where this phone stands against the channel it follows, in one line, always.
            //
            // Always is the point (0.89.0). This used to paint one case out of four — being
            // ahead of beta — so choosing a channel that had nothing published on it yet, which
            // is what alpha was on the day the channels shipped, changed the segmented control
            // and said nothing else. From the outside that is a dead control: the version above
            // does not move, no update arrives, and nothing on the screen explains why.
            val standing = channelStanding(reading, offer, BuildConfig.VERSION_CODE)
            if (standing != null) {
                Spacer(Modifier.height(spacing.sm))
                Text(
                    text = standing.argument?.let { stringResource(standing.textRes, it) }
                        ?: stringResource(standing.textRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (standing.alarming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (standing.alarming) {
                    Spacer(Modifier.height(spacing.xs))
                    Text(
                        text = stringResource(R.string.settings_channel_waiting_manual),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

/** One line about where this phone stands, and whether it is the kind that wants red. */
internal data class Standing(
    @StringRes val textRes: Int,
    val argument: String? = null,
    val alarming: Boolean = false,
)

/**
 * Which line, out of what the card knows: whether the manifest has been read at all, what it
 * said, and what this build is.
 *
 * Pure, and split out from the card for the reason the rest of the update decisions are: this is
 * the part that was wrong, it was wrong by *omission*, and an omission is only visible in a
 * table you can read all the rows of. Null while the read is in flight — a line that flickers
 * "could not read this channel" for a moment on every visit is worse than a beat of silence.
 */
internal fun channelStanding(reading: Boolean, offer: UpdateInfo?, installedVersionCode: Int): Standing? {
    if (reading) return null
    // Read and empty, or not read at all: two different sentences to somebody who has just
    // tapped a control and is looking for evidence that it did anything.
    if (offer == null) return Standing(R.string.settings_channel_unreachable)
    val switch = channelSwitch(
        installedVersionCode = installedVersionCode,
        channelVersionCode = offer.versionCode,
        channelVersionName = offer.versionName,
    )
    return when (switch) {
        is ChannelSwitch.Unknown -> Standing(R.string.settings_channel_empty)
        is ChannelSwitch.Immediate -> Standing(R.string.settings_channel_coming, switch.versionName)
        is ChannelSwitch.AlreadyOnIt -> Standing(R.string.settings_channel_current)
        // The one thing the choice cannot make true on its own, so it is the one said in red
        // and the only one with a way out under it.
        is ChannelSwitch.WaitsForNextRelease ->
            Standing(R.string.settings_channel_waiting, switch.channelVersionName, alarming = true)
    }
}

private fun channelHint(channel: UpdateChannel) = when (channel) {
    UpdateChannel.BETA -> R.string.settings_channel_beta_hint
    UpdateChannel.ALPHA -> R.string.settings_channel_alpha_hint
}
