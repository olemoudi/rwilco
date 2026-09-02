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
import androidx.compose.material.icons.outlined.TaskAlt
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.outlined.ErrorOutline
import dev.rwilco.ui.components.rememberNow
import dev.rwilco.R
import dev.rwilco.ui.components.SectionHeader
import dev.rwilco.model.DoneSection
import dev.rwilco.model.Reminder
import dev.rwilco.ui.components.DayBars
import dev.rwilco.ui.components.LocalSnackbar
import dev.rwilco.ui.components.EmptyState
import dev.rwilco.ui.components.ListPlaceholder
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.TagLabel
import dev.rwilco.ui.format.rememberWords
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.format.dayWord
import dev.rwilco.ui.format.rememberIs24h
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import java.time.Clock

/** The last seven bars of the fortnight: what "esta semana" means on this screen. */
private const val DAYS_IN_A_WEEK = 7

@Composable
fun DoneScreen(viewModel: DoneViewModel, clock: Clock, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val view by viewModel.view.collectAsStateWithLifecycle()
    var confirmingPurge by rememberSaveable { mutableStateOf(false) }
    val spacing = Tokens.spacing
    // By the minute, so a screen left open across midnight moves its "hoy" with the day.
    val now by rememberNow(60_000, clock)
    val today = now.atZone(clock.zone).toLocalDate()
    val locale = currentLocale()
    val is24h = rememberIs24h()
    val snackbar = LocalSnackbar.current
    val restoredMessage = stringResource(R.string.done_restored)
    val undoLabel = stringResource(R.string.common_undo)

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
                    if ((view?.total ?: 0) > 0) {
                        IconButton(onClick = { confirmingPurge = true }) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.done_purge))
                        }
                    }
                }
            }
        },
    ) { padding ->
        val shown = view
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
            if (shown == null) {
                item { ListPlaceholder() }
            }
            if (shown != null && shown.failed) {
                item {
                    EmptyState(
                        title = stringResource(R.string.home_failed_title),
                        body = stringResource(R.string.home_failed_body),
                        icon = Icons.Outlined.ErrorOutline,
                    )
                }
            }
            if (shown != null && !shown.failed && shown.total == 0) {
                item {
                    EmptyState(
                        title = stringResource(R.string.done_empty_title),
                        body = stringResource(R.string.done_empty_body),
                        icon = Icons.Outlined.TaskAlt,
                    )
                }
            }
            // The screen's own face, before the bands: how many this week, and the shape of the
            // fortnight behind it. A list of what got done answers "did I do it?"; this answers
            // "how is it going?", which is the question somebody opens this screen with and
            // which no amount of scrolling was ever going to answer.
            if (shown != null && shown.total > 0) {
                item(key = "chart") {
                    DoneHeadline(counts = shown.bars, week = shown.bars.takeLast(DAYS_IN_A_WEEK).sum())
                }
            }
            // Three bands rather than one long list: what got done today, what got done this
            // week, and the rest — which is a place to look rather than a place to read.
            for ((section, reminders) in shown?.sections.orEmpty()) {
                item(key = "head-$section") {
                    SectionHeader(title = stringResource(section.titleRes), trailing = reminders.size.toString())
                }
                items(reminders, key = { it.id }) { reminder ->
                    DoneCard(
                        reminder = reminder,
                        doneLabel = reminder.doneAt?.let { doneAt ->
                            val at = doneAt.atZone(clock.zone)
                            dayWord(rememberWords(), at.toLocalDate(), today) + " · " + TimeText.time(at.toLocalTime(), is24h, locale)
                        },
                        onOpen = { onOpen(reminder.id) },
                        onRestore = {
                            viewModel.restore(reminder.id)
                            // Said, and undoable: a mis-tap put a reminder back on Home without a word.
                            snackbar.show(restoredMessage, undoLabel) { viewModel.undoRestore(reminder) }
                        },
                    )
                }
            }
            // Said once, at the bottom, because a list that quietly forgets things is worse
            // than one that says how long it remembers for.
            if (shown != null && shown.total > 0) {
                item(key = "kept") {
                    Text(
                        text = stringResource(R.string.done_kept_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.lg),
                    )
                }
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

/**
 * The number, the words for it, and the fortnight under them.
 *
 * The count is in `displayLarge`, which is the one Material role this app sets in JetBrains
 * Mono — the size a number is read at when it is the only thing being said.
 */
@Composable
private fun DoneHeadline(counts: List<Int>, week: Int) {
    val spacing = Tokens.spacing
    Column(modifier = Modifier.padding(top = spacing.sm, bottom = spacing.md)) {
        Text(
            text = week.toString(),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.done_this_week),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(spacing.lg))
        DayBars(counts = counts, label = stringResource(R.string.done_chart_label, counts.size, counts.sum()))
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
            val restoreHaptics = Tokens.haptics
            IconButton(onClick = { restoreHaptics.perform(HapticFeedbackType.Confirm); onRestore() }) {
                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(R.string.done_restore), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val DoneSection.titleRes: Int
    get() = when (this) {
        DoneSection.TODAY -> R.string.done_section_today
        DoneSection.LAST_WEEK -> R.string.done_section_last_week
        DoneSection.EARLIER -> R.string.done_section_earlier
    }
