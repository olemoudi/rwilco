package dev.rwilco.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.text.format.Formatter
import android.content.Intent
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import dev.rwilco.R
import dev.rwilco.model.BackupCadence
import dev.rwilco.model.MIN_PASSPHRASE_LENGTH
import dev.rwilco.model.PassphraseStrength
import dev.rwilco.model.TriggerFamily
import dev.rwilco.model.passphraseStrength
import dev.rwilco.ui.components.LocalSnackbar
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.TagChip
import dev.rwilco.ui.theme.LocalDarkTheme
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.familyColor
import dev.rwilco.vault.VaultOutcome
import dev.rwilco.vault.VaultState
import dev.rwilco.vault.VaultSummary
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The encrypted backup, off by default. Off, it is a form and one button; on, it is a status
 * line and the handful of things somebody might do to a backup — make one now, bring one back,
 * take one away as a file, undo the last restore, turn it off. Everything that takes a
 * decision — a copy already there, a passphrase, "this replaces what is on the phone" — is a
 * dialog driven by [BackupPhase], so the screen underneath never has to change shape.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupScreen(viewModel: BackupViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val hasUndo by viewModel.hasUndo.collectAsStateWithLifecycle()
    val localCount by viewModel.localCount.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing
    val snackbar = LocalSnackbar.current

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let(viewModel::exportTo)
    }
    val exportTextLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let(viewModel::exportTextTo)
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareText = {
        scope.launch {
            val text = viewModel.readableExportText()
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
        }
        Unit
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importFrom)
    }
    val dryRunLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::dryRunImport)
    }

    // "Done" is a line at the bottom, not a dialog: there is nothing to decide about it.
    val current = phase
    if (current is BackupPhase.Done) {
        val message = current.arg?.let { stringResource(current.message, it) } ?: stringResource(current.message)
        LaunchedEffect(current) {
            snackbar.show(message)
            viewModel.dismiss()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = spacing.sm)
                        .heightIn(min = Tokens.sizes.control),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                    Text(
                        text = stringResource(R.string.vault_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = spacing.sm),
                    )
                }
            }
        },
    ) { padding ->
        val vault = state ?: return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen)
                .padding(top = spacing.lg, bottom = spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (!vault.enabled) {
                SetupCard(viewModel)
                FileRows(
                    hasKey = false,
                    hasUndo = hasUndo,
                    onExport = { exportLauncher.launch(exportName()) },
                    onImport = { importLauncher.launch(arrayOf("*/*")) },
                    onDryRun = { dryRunLauncher.launch(arrayOf("*/*")) },
                    onUndo = viewModel::undoRestore,
                    onExportText = { exportTextLauncher.launch(exportName().replace(".vault", ".txt")) },
                    onShareText = shareText,
                )
            } else {
                StatusCard(vault, working, viewModel)
                FileRows(
                    hasKey = true,
                    hasUndo = hasUndo,
                    onExport = { exportLauncher.launch(exportName()) },
                    onImport = { importLauncher.launch(arrayOf("*/*")) },
                    onDryRun = { dryRunLauncher.launch(arrayOf("*/*")) },
                    onUndo = viewModel::undoRestore,
                    onExportText = { exportTextLauncher.launch(exportName().replace(".vault", ".txt")) },
                    onShareText = shareText,
                )
                OffCard(viewModel::disable)
            }
        }
    }

    PhaseDialogs(phase, localCount, viewModel)
}

// With the hour: two exports on the same afternoon used to collide in the picker.
private fun exportName(): String = "rwilco-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"))}.vault"

/** Off: what it is, what the remote sees, the three things it needs, one button. */
@Composable
private fun SetupCard(viewModel: BackupViewModel) {
    val spacing = Tokens.spacing
    val form by viewModel.form.collectAsStateWithLifecycle()
    RwilcoCard {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            Text(stringResource(R.string.vault_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.vault_intro_warning), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(spacing.xs))
            PlainField(
                value = form.repo,
                onChange = { value -> viewModel.edit { it.copy(repo = value) } },
                label = stringResource(R.string.vault_field_repo),
                placeholder = stringResource(R.string.vault_field_repo_placeholder),
                hint = stringResource(R.string.vault_field_repo_hint),
            )
            SecretField(
                value = form.token,
                onChange = { value -> viewModel.edit { it.copy(token = value) } },
                label = stringResource(R.string.vault_field_token),
                hint = stringResource(R.string.vault_field_token_hint),
            )
            // Before a passphrase, before anything is written: proof that the boring half works.
            OutlinedButton(
                onClick = viewModel::testConnection,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.sizes.control),
            ) { Text(stringResource(R.string.vault_test)) }
            SecretField(
                value = form.passphrase,
                onChange = { value -> viewModel.edit { it.copy(passphrase = value) } },
                label = stringResource(R.string.vault_field_passphrase),
                strength = true,
            )
            SecretField(
                value = form.again,
                onChange = { value -> viewModel.edit { it.copy(again = value) } },
                label = stringResource(R.string.vault_field_passphrase_again),
            )
            PrimaryButton(text = stringResource(R.string.vault_enable), onClick = viewModel::enable)
        }
    }
}

/** On: where it goes, how it stands, and what to do about it when it has stopped. */
@Composable
private fun StatusCard(vault: VaultState, working: Boolean, viewModel: BackupViewModel) {
    val spacing = Tokens.spacing
    var editingCredentials by rememberSaveable { mutableStateOf(false) }
    RwilcoCard {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            Text(
                text = stringResource(R.string.vault_status_repo, "${vault.owner}/${vault.repo}"),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = vaultStatusText(vault, working),
                style = MaterialTheme.typography.bodyMedium,
                color = if (vault.needsAttention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (vault.lastOutcome) {
                VaultOutcome.CONFLICT -> {
                    Text(stringResource(R.string.vault_conflict_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = viewModel::restoreFromRemote, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.sizes.control)) {
                        Text(stringResource(R.string.vault_conflict_take_remote))
                    }
                    OutlinedButton(onClick = viewModel::overwriteRemote, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.sizes.control)) {
                        Text(stringResource(R.string.vault_conflict_keep_phone))
                    }
                }
                VaultOutcome.AUTH, VaultOutcome.REPO_MISSING -> {
                    OutlinedButton(onClick = { editingCredentials = true }, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.sizes.control)) {
                        Text(stringResource(R.string.vault_update_credentials))
                    }
                }
                else -> Unit
            }
            vault.lastUploadedBytes?.let { bytes ->
                Text(
                    text = stringResource(R.string.vault_status_size, Formatter.formatShortFileSize(LocalContext.current, bytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = viewModel::backupNow,
                enabled = !working,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.sizes.control),
            ) { Text(stringResource(R.string.vault_backup_now)) }
            if (vault.lastOutcome != VaultOutcome.CONFLICT) {
                NavRow(stringResource(R.string.vault_restore_remote), onClick = viewModel::restoreFromRemote)
            }
            NavRow(stringResource(R.string.vault_test), onClick = viewModel::testConnection)
        }
    }
    Spacer(Modifier.height(Tokens.spacing.sm))
    CadenceCard(vault, viewModel)
    if (editingCredentials) {
        CredentialsDialog(
            repo = "${vault.owner}/${vault.repo}",
            onSave = { repo, token ->
                editingCredentials = false
                viewModel.updateCredentials(repo, token)
            },
            onDismiss = { editingCredentials = false },
        )
    }
}

/** The file transport, on or off: the same envelope, carried by hand. */
@Composable
private fun FileRows(
    hasKey: Boolean,
    hasUndo: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDryRun: () -> Unit,
    onUndo: () -> Unit,
    onExportText: () -> Unit,
    onShareText: () -> Unit,
) {
    val spacing = Tokens.spacing
    RwilcoCard {
        Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm)) {
            NavRow(stringResource(R.string.vault_export), subtitle = if (hasKey) null else stringResource(R.string.vault_export_off_hint), onClick = onExport)
            NavRow(stringResource(R.string.vault_import), onClick = onImport)
            NavRow(stringResource(R.string.vault_dry_run), subtitle = stringResource(R.string.vault_dry_run_hint), onClick = onDryRun)
            if (hasUndo) NavRow(stringResource(R.string.vault_undo), subtitle = stringResource(R.string.vault_undo_hint), onClick = onUndo)
            // The copy anybody can read: the vault is the one that survives, this is the one
            // that can be looked at, pasted, sent — and it says so, because it is not sealed.
            NavRow(stringResource(R.string.vault_export_text), subtitle = stringResource(R.string.vault_export_text_hint), onClick = onExportText)
            NavRow(stringResource(R.string.vault_share_text), onClick = onShareText)
        }
    }
}

@Composable
private fun OffCard(onDisable: () -> Unit) {
    val spacing = Tokens.spacing
    var confirming by rememberSaveable { mutableStateOf(false) }
    RwilcoCard {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(stringResource(R.string.vault_disable_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { confirming = true }) {
                Text(stringResource(R.string.vault_disable), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.vault_disable_title)) },
            text = { Text(stringResource(R.string.vault_disable_hint)) },
            confirmButton = {
                TextButton(onClick = { confirming = false; onDisable() }) {
                    Text(stringResource(R.string.vault_disable), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.vault_cancel)) } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge,
        )
    }
}

/** Every decision the screen can ask for, one dialog each, driven by the phase. */
@Composable
private fun PhaseDialogs(phase: BackupPhase, localCount: Int, viewModel: BackupViewModel) {
    when (phase) {
        BackupPhase.Idle, is BackupPhase.Done -> Unit
        is BackupPhase.Busy -> BusyDialog(stringResource(phase.message))
        is BackupPhase.Failed -> MessageDialog(phase.arg?.let { stringResource(phase.message, it) } ?: stringResource(phase.message), onDismiss = viewModel::dismiss)
        is BackupPhase.DryRun -> DryRunDialog(phase.summary, viewModel::dismiss)
        is BackupPhase.Existing -> ExistingDialog(phase, viewModel)
        is BackupPhase.Confirm -> ConfirmDialog(phase.opened.summary, localCount, viewModel)
        is BackupPhase.AskPassphrase -> PassphraseDialog(
            title = stringResource(R.string.vault_passphrase_title),
            body = stringResource(R.string.vault_passphrase_body),
            action = stringResource(R.string.vault_open_action),
            onConfirm = viewModel::openWith,
            onDismiss = viewModel::dismiss,
        )
        is BackupPhase.AskExportPassphrase -> PassphraseDialog(
            title = stringResource(R.string.vault_export_passphrase_title),
            body = stringResource(R.string.vault_export_passphrase_body, MIN_PASSPHRASE_LENGTH),
            action = stringResource(R.string.vault_export),
            onConfirm = viewModel::exportWith,
            onDismiss = viewModel::dismiss,
        )
    }
}

/** How often, and over what: the two things that decide what the backup costs. */
@Composable
private fun CadenceCard(vault: VaultState, viewModel: BackupViewModel) {
    val spacing = Tokens.spacing
    val haptics = Tokens.haptics
    RwilcoCard {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            SettingTitle(
                title = stringResource(R.string.vault_cadence),
                info = stringResource(R.string.vault_cadence_hint),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                for (cadence in BackupCadence.entries) {
                    TagChip(
                        label = stringResource(cadence.labelRes),
                        selected = cadence == vault.cadence,
                        onClick = { viewModel.setCadence(cadence) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingTitle(
                    title = stringResource(R.string.vault_wifi_only),
                    info = stringResource(R.string.vault_wifi_only_hint),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(spacing.md))
                Switch(
                    checked = vault.wifiOnly,
                    onCheckedChange = { on ->
                        if (on) haptics.perform(HapticFeedbackType.ToggleOn)
                        viewModel.setWifiOnly(on)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.surface,
                        checkedTrackColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        }
    }
}

/** What the cadence chips say. */
private val BackupCadence.labelRes: Int
    get() = when (this) {
        BackupCadence.HOURLY -> R.string.vault_cadence_1h
        BackupCadence.EVERY_4_HOURS -> R.string.vault_cadence_4h
        BackupCadence.EVERY_8_HOURS -> R.string.vault_cadence_8h
        BackupCadence.DAILY -> R.string.vault_cadence_24h
        BackupCadence.EVERY_3_DAYS -> R.string.vault_cadence_72h
        BackupCadence.WEEKLY -> R.string.vault_cadence_week
    }

/** A rehearsal: what the file holds, and the fact that nothing on the phone moved. */
@Composable
private fun DryRunDialog(summary: VaultSummary, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_dry_run_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
                SummaryText(summary)
                Text(stringResource(R.string.vault_dry_run_ok), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_got_it)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
    )
}

@Composable
private fun BusyDialog(message: String) {
    AlertDialog(
        onDismissRequest = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.md)) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {},
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
    )
}

@Composable
private fun MessageDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_got_it)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
    )
}

@Composable
private fun ExistingDialog(phase: BackupPhase.Existing, viewModel: BackupViewModel) {
    val opened = phase.opened
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(stringResource(R.string.vault_existing_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
                if (opened != null) SummaryText(opened.summary) else Text(stringResource(R.string.vault_existing_locked), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (opened != null) {
                    TextButton(onClick = viewModel::restoreExisting) { Text(stringResource(R.string.vault_existing_restore)) }
                }
                TextButton(onClick = viewModel::replaceExisting) { Text(stringResource(R.string.vault_existing_replace), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.vault_cancel)) }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
    )
}

@Composable
private fun ConfirmDialog(summary: VaultSummary, localCount: Int, viewModel: BackupViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(stringResource(R.string.vault_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
                SummaryText(summary)
                Text(
                    text = if (localCount == 0) stringResource(R.string.vault_confirm_empty) else pluralStringResource(R.plurals.vault_confirm_replaces, localCount, localCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmRestore) { Text(stringResource(R.string.vault_confirm_yes), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.vault_cancel)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
    )
}

/** When, from where, how much — and the one warning worth a line of its own. */
@Composable
private fun SummaryText(summary: VaultSummary) {
    Text(
        text = stringResource(
            R.string.vault_summary,
            dateTimeText(summary.exportedAt),
            summary.deviceId.take(8),
            summary.active,
            summary.done,
            summary.presets,
            summary.places,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (summary.newerThanThisApp) {
        Text(stringResource(R.string.vault_summary_newer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun PassphraseDialog(title: String, body: String, action: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var passphrase by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.md)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                SecretField(value = passphrase, onChange = { passphrase = it }, label = stringResource(R.string.vault_field_passphrase))
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(passphrase) }, enabled = passphrase.isNotEmpty()) { Text(action) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.vault_cancel)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
    )
}

@Composable
private fun CredentialsDialog(repo: String, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var repoText by rememberSaveable { mutableStateOf(repo) }
    var token by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_update_credentials_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.md)) {
                PlainField(value = repoText, onChange = { repoText = it }, label = stringResource(R.string.vault_field_repo), placeholder = stringResource(R.string.vault_field_repo_placeholder))
                SecretField(value = token, onChange = { token = it }, label = stringResource(R.string.vault_field_token))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(repoText, token) }, enabled = token.isNotBlank()) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.vault_cancel)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
    )
}

@Composable
private fun PlainField(value: String, onChange: (String) -> Unit, label: String, placeholder: String? = null, hint: String? = null) {
    val scheme = MaterialTheme.colorScheme
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Uri),
            shape = MaterialTheme.shapes.small,
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = Tokens.spacing.xs))
        }
    }
}

/** A token or a passphrase: hidden by default, shown on request, never auto-corrected. */
@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String, hint: String? = null, strength: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    var shown by rememberSaveable { mutableStateOf(false) }
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { shown = !shown }) {
                    Icon(
                        imageVector = if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringResource(if (shown) R.string.vault_hide else R.string.vault_show),
                    )
                }
            },
            shape = MaterialTheme.shapes.small,
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (strength) StrengthBar(value)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = Tokens.spacing.xs))
        }
    }
}

/**
 * Four segments and one line of words: whether this passphrase is good enough yet, and how
 * much better than that it is. The floor is what the app refuses to go on without — twelve
 * characters with letters and digits in them — and the segments past it are encouragement,
 * because the thing this protects has no way back if it is guessed.
 */
@Composable
private fun StrengthBar(passphrase: String) {
    val scheme = MaterialTheme.colorScheme
    val spacing = Tokens.spacing
    val strength = remember(passphrase) { passphraseStrength(passphrase) }
    val filled = if (strength.meetsMinimum) familyColor(TriggerFamily.PLACE, LocalDarkTheme.current) else scheme.error
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
    ) {
        repeat(PassphraseStrength.LEVELS) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(Tokens.strokes.strong * 2)
                    .background(if (index < strength.level) filled else scheme.surfaceContainerHigh, CircleShape),
            )
        }
    }
    Text(
        text = when {
            passphrase.isEmpty() -> stringResource(R.string.vault_passphrase_rule, MIN_PASSPHRASE_LENGTH)
            !strength.meetsMinimum -> stringResource(R.string.vault_pass_not_yet, MIN_PASSPHRASE_LENGTH)
            strength.level >= 4 -> stringResource(R.string.vault_pass_strong)
            strength.level == 3 -> stringResource(R.string.vault_pass_good)
            else -> stringResource(R.string.vault_pass_enough)
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (passphrase.isNotEmpty() && !strength.meetsMinimum) scheme.error else scheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Tokens.spacing.xs),
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.outline,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    cursorColor = MaterialTheme.colorScheme.primary,
)

/** The one primary action of the form, inverted like every other "do it" in the app. */
@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.onSurface,
            contentColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.sizes.control),
    ) { Text(text) }
}

@Composable
private fun NavRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.sizes.touch)
            .clickable(onClick = onClick)
            .padding(vertical = Tokens.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
