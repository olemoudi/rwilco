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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.rwilco.ui.home.SearchField
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.platform.LocalLayoutDirection
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
import dev.rwilco.ui.components.RwilcoTopBar
import dev.rwilco.ui.settings.ClearDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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
    // What was done is searched here, not on Home (0.146.0): the magnifier swaps the title for
    // the same field Home and Settings use, and Back closes it before it leaves the screen.
    var searching by rememberSaveable { mutableStateOf(false) }
    val found by viewModel.found.collectAsStateWithLifecycle()
    val closeSearch = {
        searching = false
        viewModel.setQuery("")
    }
    BackHandler(enabled = searching) { closeSearch() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (searching) {
                // The field paints its own background and keeps clear of the status bar, as
                // Settings' does: the top bar it replaces was doing both.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding()
                        .padding(horizontal = spacing.sm),
                ) {
                    SearchField(
                        query = "",
                        onQueryChange = viewModel::setQuery,
                        onClose = closeSearch,
                        hint = stringResource(R.string.done_search_hint),
                    )
                }
            } else {
                RwilcoTopBar(
                    title = stringResource(R.string.done_title),
                    onBack = onBack,
                    action = if ((view?.total ?: 0) > 0) {
                        {
                            Row {
                                IconButton(onClick = { searching = true }) {
                                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.done_search))
                                }
                                IconButton(onClick = { confirmingPurge = true }) {
                                    Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.done_purge))
                                }
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        },
    ) { padding ->
        val shown = view
        val words = rememberWords()
        // One row, for the bands and the results alike.
        val entry: @Composable LazyItemScope.(Reminder) -> Unit = { reminder ->
            DoneCard(
                // A row brought back closes up over its place, as Home's rows do.
                modifier = Modifier.animateItem(),
                reminder = reminder,
                doneLabel = reminder.doneAt?.let { doneAt ->
                    val at = doneAt.atZone(clock.zone)
                    dayWord(words, at.toLocalDate(), today) + " · " + TimeText.time(at.toLocalTime(), is24h, locale)
                },
                onOpen = { onOpen(reminder.id) },
                onRestore = {
                    viewModel.restore(reminder.id)
                    // Said, and undoable: a mis-tap put a reminder back on Home without a word.
                    snackbar.show(restoredMessage, undoLabel) { viewModel.undoRestore(reminder) }
                },
            )
        }
        // Both sides too (0.93.0): the Scaffold is asked for the safe area and only the top and
        // bottom of its answer were read, so a phone on its side put cards under the cutout.
        val direction = LocalLayoutDirection.current
        // What a search with something typed found; the chart, the bands and the note step aside for it.
        val results = found?.takeIf { searching }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction) + spacing.screen,
                end = padding.calculateEndPadding(direction) + spacing.screen,
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
                    // Centred in the screen, as the watch log's is (0.94.0); it floated near
                    // the top of a tall phone.
                    Box(Modifier.fillParentMaxHeight(0.7f), contentAlignment = Alignment.Center) {
                        EmptyState(
                            title = stringResource(R.string.done_empty_title),
                            body = stringResource(R.string.done_empty_body),
                            icon = Icons.Outlined.TaskAlt,
                        )
                    }
                }
            }
            // The screen's own face, before the bands: how many this week, and the shape of the
            // fortnight behind it. A list of what got done answers "did I do it?"; this answers
            // "how is it going?", which is the question somebody opens this screen with and
            // which no amount of scrolling was ever going to answer.
            if (shown != null && shown.total > 0 && results == null) {
                item(key = "chart") {
                    DoneHeadline(counts = shown.bars, week = shown.bars.takeLast(DAYS_IN_A_WEEK).sum())
                }
            }
            // While a search has something typed, its results are the list: best first, the same
            // rows, the same way back for each.
            if (results != null) {
                items(results, key = { it.id }) { reminder -> entry(reminder) }
                if (results.isEmpty()) {
                    item(key = "search-empty") {
                        EmptyState(
                            title = stringResource(R.string.home_search_none_title),
                            body = stringResource(R.string.home_search_none_body),
                            icon = Icons.Outlined.SearchOff,
                        )
                    }
                }
            }
            // Three bands rather than one long list: what got done today, what got done this
            // week, and the rest — which is a place to look rather than a place to read.
            for ((section, reminders) in shown?.sections.orEmpty().takeIf { results == null }.orEmpty()) {
                item(key = "head-$section") {
                    SectionHeader(title = stringResource(section.titleRes), trailing = reminders.size.toString())
                }
                items(reminders, key = { it.id }) { reminder -> entry(reminder) }
            }
            // Said once, at the bottom, because a list that quietly forgets things is worse
            // than one that says how long it remembers for.
            if (shown != null && shown.total > 0 && results == null) {
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
        // The same question the two logs ask, and the dialog its KDoc always said this was.
        ClearDialog(
            titleRes = R.string.done_purge_title,
            bodyRes = R.string.done_purge_body,
            confirmRes = R.string.done_purge,
            onConfirm = {
                confirmingPurge = false
                viewModel.purge()
            },
            onDismiss = { confirmingPurge = false },
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
        // Which end is today. Fourteen bars with nothing under them were a shape without a
        // direction: the eye could not tell the week that just happened from the one before.
        Spacer(Modifier.height(spacing.xs))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = stringResource(R.string.done_chart_start, counts.size - 1),
                style = MonoStyles.tally,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.done_chart_today),
                style = MonoStyles.tally,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DoneCard(reminder: Reminder, doneLabel: String?, onOpen: () -> Unit, onRestore: () -> Unit, modifier: Modifier = Modifier) {
    RwilcoCard(onClick = onOpen, modifier = modifier) {
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
                // Named after its own reminder, the way the strips' "Ver" is: twenty cards were
                // twenty identical "Recuperar"s to a screen reader (0.93.0).
                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(R.string.done_restore_named, reminder.text), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
