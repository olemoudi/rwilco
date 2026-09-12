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
import dev.rwilco.model.BackupFreshness
import dev.rwilco.model.backupFreshness
import dev.rwilco.ui.components.rememberNow
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
    // **Behind, and nothing would have said so.** The failure this happens under is a network
    // one, which every run is right to retry in silence — and the row went on reading "the last
    // attempt failed; it will try again" for as long as that lasted, which could be a month.
    // Asked of the state alone: a run that came to nothing left what it was carrying waiting,
    // which is what `pending` means here, rather than hashing every reminder again for one line
    // in a list (Home's badge has the real count and hands it the same question).
    val now by rememberNow(60_000, app.clock)
    val freshness = current?.let {
        backupFreshness(
            enabled = it.enabled,
            lastRunAt = it.lastRunAt,
            pending = if (it.lastOutcome == VaultOutcome.UPLOADED || it.lastOutcome == VaultOutcome.UP_TO_DATE) 0 else 1,
            cadence = it.cadence,
            now = now,
        )
    } ?: BackupFreshness.OFF

    SettingsLinkRow(
        title = stringResource(R.string.vault_card_title),
        summary = if (current == null) "" else vaultStatusText(current, activity.working, freshness),
        icon = Icons.Outlined.Lock,
        attention = current?.needsAttention == true || freshness == BackupFreshness.STALE,
        // It sits in the index beside the folding groups, so it is set like one of them.
        topLevel = true,
        onClick = onOpen,
        modifier = modifier,
    )
}

/** Off / working / stopped and why / when the last copy was made. Shared by the row and the screen. */
@Composable
internal fun vaultStatusText(
    state: VaultState,
    working: Boolean,
    freshness: BackupFreshness = BackupFreshness.FRESH,
): String = when {
    !state.enabled -> stringResource(R.string.vault_card_off)
    working -> stringResource(R.string.vault_card_working)
    state.lastOutcome == VaultOutcome.AUTH -> stringResource(R.string.vault_card_attention_auth)
    state.lastOutcome == VaultOutcome.REPO_MISSING -> stringResource(R.string.vault_card_attention_repo)
    state.lastOutcome == VaultOutcome.CONFLICT -> stringResource(R.string.vault_card_attention_conflict)
    // Before "the last attempt failed; it will try again", which is true and which somebody can
    // read for a month without it ever meaning anything. This says how long it has been.
    freshness == BackupFreshness.STALE && state.lastRunAt != null ->
        stringResource(R.string.vault_card_stale, dateTimeText(state.lastRunAt))
    state.lastOutcome == VaultOutcome.TRANSIENT && state.lastUploadedAt == null -> stringResource(R.string.vault_card_transient)
    state.lastUploadedAt == null -> stringResource(R.string.vault_card_never)
    else -> stringResource(R.string.vault_card_last, dateTimeText(state.lastUploadedAt))
}

/** "26/8/26, 10:15" in the phone's own words: the mono face is for the reminders, not for this. */
@Composable
internal fun dateTimeText(at: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(at.atZone(ZoneId.systemDefault()))
