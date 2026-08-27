package dev.rwilco.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.vault.VaultCenter
import dev.rwilco.vault.VaultOutcome
import dev.rwilco.vault.VaultState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * One line on the state of the backup, and the way to the screen that manages it. It is the one
 * thing on this screen that folds into nothing — a group whose whole content is a single link
 * is a fold that costs a tap and hides one row — so it stays a row of its own.
 */
@Composable
fun BackupCard(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as RwilcoApplication
    val state by app.vaultStore.state.collectAsStateWithLifecycle(initialValue = null)
    val activity by VaultCenter.activity.collectAsStateWithLifecycle()
    val current = state

    SettingsLinkRow(
        title = stringResource(R.string.vault_card_title),
        summary = if (current == null) "" else vaultStatusText(current, activity.working),
        icon = Icons.Outlined.Lock,
        attention = current?.needsAttention == true,
        onClick = onOpen,
        modifier = modifier,
    )
}

/** Off / working / stopped and why / when the last copy was made. Shared by the row and the screen. */
@Composable
internal fun vaultStatusText(state: VaultState, working: Boolean): String = when {
    !state.enabled -> stringResource(R.string.vault_card_off)
    working -> stringResource(R.string.vault_card_working)
    state.lastOutcome == VaultOutcome.AUTH -> stringResource(R.string.vault_card_attention_auth)
    state.lastOutcome == VaultOutcome.REPO_MISSING -> stringResource(R.string.vault_card_attention_repo)
    state.lastOutcome == VaultOutcome.CONFLICT -> stringResource(R.string.vault_card_attention_conflict)
    state.lastOutcome == VaultOutcome.TRANSIENT && state.lastUploadedAt == null -> stringResource(R.string.vault_card_transient)
    state.lastUploadedAt == null -> stringResource(R.string.vault_card_never)
    else -> stringResource(R.string.vault_card_last, dateTimeText(state.lastUploadedAt))
}

/** "26/8/26, 10:15" in the phone's own words: the mono face is for the reminders, not for this. */
@Composable
internal fun dateTimeText(at: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(at.atZone(ZoneId.systemDefault()))
