package dev.rwilco.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.vault.VaultCenter
import dev.rwilco.vault.VaultOutcome
import dev.rwilco.vault.VaultState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** One line on the state of the backup, and the way to the screen that manages it. */
@Composable
fun BackupCard(onOpen: () -> Unit) {
    val spacing = Tokens.spacing
    val app = LocalContext.current.applicationContext as RwilcoApplication
    val state by app.vaultStore.state.collectAsStateWithLifecycle(initialValue = null)
    val activity by VaultCenter.activity.collectAsStateWithLifecycle()

    RwilcoCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Tokens.sizes.touch)
                .clickable(onClick = onOpen)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.vault_card_title), style = MaterialTheme.typography.titleMedium)
                val current = state
                Text(
                    text = if (current == null) "" else vaultStatusText(current, activity.working),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (current?.needsAttention == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Off / working / stopped and why / when the last copy was made. Shared by the card and the screen. */
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
