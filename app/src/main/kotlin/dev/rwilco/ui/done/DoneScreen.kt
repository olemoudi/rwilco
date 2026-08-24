package dev.rwilco.ui.done

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rwilco.R
import dev.rwilco.model.Reminder
import dev.rwilco.ui.components.EmptyState
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.TagLabel
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.format.dayWord
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import java.time.Clock

@Composable
fun DoneScreen(viewModel: DoneViewModel, clock: Clock, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val done by viewModel.done.collectAsStateWithLifecycle()
    var confirmingPurge by rememberSaveable { mutableStateOf(false) }
    val spacing = Tokens.spacing
    val today = clock.instant().atZone(clock.zone).toLocalDate()

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
                        text = stringResource(R.string.done_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = spacing.sm),
                    )
                    if (!done.isNullOrEmpty()) {
                        IconButton(onClick = { confirmingPurge = true }) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.done_purge))
                        }
                    }
                }
            }
        },
    ) { padding ->
        val list = done
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.screen,
                end = spacing.screen,
                top = padding.calculateTopPadding() + spacing.sm,
                bottom = padding.calculateBottomPadding() + spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            if (list != null && list.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(R.string.done_empty_title),
                        body = stringResource(R.string.done_empty_body),
                    )
                }
            }
            items(list.orEmpty(), key = { it.id }) { reminder ->
                DoneCard(
                    reminder = reminder,
                    doneLabel = reminder.doneAt?.let { doneAt ->
                        val date = doneAt.atZone(clock.zone).toLocalDate()
                        dayWord(date, today, currentLocale()) + " · " + TimeText.time(doneAt.atZone(clock.zone).toLocalTime(), true, currentLocale())
                    },
                    onOpen = { onOpen(reminder.id) },
                    onRestore = { viewModel.restore(reminder.id) },
                )
            }
        }
    }

    if (confirmingPurge) {
        AlertDialog(
            onDismissRequest = { confirmingPurge = false },
            title = { Text(stringResource(R.string.done_purge_title)) },
            text = { Text(stringResource(R.string.done_purge_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingPurge = false
                    viewModel.purge()
                }) { Text(stringResource(R.string.done_purge), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingPurge = false }) { Text(stringResource(R.string.sheet_cancel)) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge,
        )
    }
}

@Composable
private fun DoneCard(reminder: Reminder, doneLabel: String?, onOpen: () -> Unit, onRestore: () -> Unit) {
    RwilcoCard(onClick = onOpen) {
        Row(
            modifier = Modifier.padding(start = Tokens.spacing.lg, top = Tokens.spacing.md, bottom = Tokens.spacing.md, end = Tokens.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = reminder.text,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (doneLabel != null) {
                    Spacer(Modifier.height(Tokens.spacing.xs))
                    Text(text = doneLabel, style = MonoStyles.date, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (reminder.tags.isNotEmpty()) {
                    Spacer(Modifier.height(Tokens.spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.xs)) {
                        for (tag in reminder.tags.take(3)) TagLabel(tag)
                    }
                }
            }
            IconButton(onClick = onRestore) {
                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(R.string.done_restore), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
