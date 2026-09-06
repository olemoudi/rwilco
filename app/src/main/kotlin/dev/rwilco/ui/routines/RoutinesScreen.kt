package dev.rwilco.ui.routines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rwilco.R
import dev.rwilco.model.RoutineFilter
import dev.rwilco.model.partsBetween
import dev.rwilco.ui.components.EmptyState
import dev.rwilco.ui.components.ListPlaceholder
import dev.rwilco.ui.components.LocalSnackbar
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.RwilcoTopBar
import dev.rwilco.ui.components.TagChip
import dev.rwilco.ui.components.TagLabel
import dev.rwilco.ui.components.rememberNow
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.countdownText
import dev.rwilco.ui.format.dayWord
import dev.rwilco.ui.format.rememberWords
import dev.rwilco.ui.home.ReminderActionsMenu
import dev.rwilco.ui.home.SwipeableCard
import dev.rwilco.ui.home.UndoDeleteRow
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.tagColor
import java.time.Clock
import java.time.Duration

/**
 * The routines: what counts time since the last time it was done (see `Routines.kt`).
 *
 * Every row is the question the routine is — «¿He hecho «mover el coche»? → Sí» — with the
 * answer at the end of the line: "Sí" while the span has not run out, "No" in the error ink
 * once it has. Under it, in mono, how long it has been and how long is left; under that, a
 * thin track of the span. **A swipe to the right, held, is "sí, la he hecho"** — the same
 * gesture Home's cards answer with, going through the same door — and a swipe to the left,
 * held, deletes with the same minute of undo. A tap opens the routine to edit; a held press
 * offers the rest of what a card offers. The overdue ones are always on top, and the chips
 * filter by them or by tag.
 */
@Composable
fun RoutinesScreen(
    viewModel: RoutinesViewModel,
    clock: Clock,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onClone: (String) -> Unit,
    onKeepAsPreset: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()
    val snoozeCustomMinutes by viewModel.snoozeCustomMinutes.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing
    // By the minute: "hace 10 d 3 h" moves, and a "Sí" becomes a "No" when the span runs out.
    val now by rememberNow(60_000, clock)
    val zone = clock.zone
    val words = rememberWords()
    val snackbar = LocalSnackbar.current
    val doneMessage = stringResource(R.string.routines_done)
    val deletedMessage = stringResource(R.string.home_deleted)
    val pausedMessage = stringResource(R.string.home_paused)
    val resumedMessage = stringResource(R.string.home_resumed)
    val snoozeCancelledMessage = stringResource(R.string.home_snooze_cancelled)
    val undoLabel = stringResource(R.string.common_undo)
    val actionsLabel = stringResource(R.string.home_card_actions)
    // The row being held, and so the one the actions menu is about.
    var actingOn by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is RoutinesEvent.Done -> snackbar.show(
                    message = event.comesBackAt?.let { back ->
                        val here = back.atZone(zone)
                        val todayHere = clock.instant().atZone(zone).toLocalDate()
                        words.get(R.string.routines_done_returns, dayWord(words, here.toLocalDate(), todayHere) + " " + TimeText.time(here.toLocalTime(), words.is24h, words.locale))
                    } ?: doneMessage,
                    undoLabel = undoLabel,
                    onUndo = { viewModel.undo(event.reminder) },
                )
                is RoutinesEvent.Deleted -> snackbar.show(deletedMessage, undoLabel) { viewModel.undo(event.reminder, event.history) }
                is RoutinesEvent.Paused -> snackbar.show(if (event.paused) pausedMessage else resumedMessage, undoLabel) { viewModel.undoPause(event) }
                is RoutinesEvent.Snoozed -> snackbar.show(
                    message = if (event.cancelled) snoozeCancelledMessage
                    else event.until?.let { until ->
                        val here = until.atZone(zone)
                        val todayHere = clock.instant().atZone(zone).toLocalDate()
                        words.get(R.string.home_snoozed_until, dayWord(words, here.toLocalDate(), todayHere) + " " + TimeText.time(here.toLocalTime(), words.is24h, words.locale))
                    } ?: snoozeCancelledMessage,
                    undoLabel = undoLabel,
                    onUndo = { viewModel.undo(event.reminder) },
                )
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { RwilcoTopBar(title = stringResource(R.string.routines_title), onBack = onBack) },
        floatingActionButton = {
            val haptics = Tokens.haptics
            // The screen's one primary action, in the thumb zone, wearing the inverted neutral
            // every primary in the app wears: amber is what fires next, and this is a door.
            ExtendedFloatingActionButton(
                onClick = { haptics.perform(HapticFeedbackType.Confirm); onNew() },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.routines_new), style = MaterialTheme.typography.titleMedium) },
                containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.heightIn(min = Tokens.sizes.primary),
            )
        },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction) + spacing.screen,
                end = padding.calculateEndPadding(direction) + spacing.screen,
                top = padding.calculateTopPadding() + spacing.sm,
                bottom = padding.calculateBottomPadding() + Tokens.sizes.primary + spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            if (!state.loaded) {
                item(key = "loading") { ListPlaceholder() }
            }
            if (state.failed) {
                item(key = "failed") {
                    EmptyState(
                        title = stringResource(R.string.home_failed_title),
                        body = stringResource(R.string.home_failed_body),
                        icon = Icons.Outlined.ErrorOutline,
                    )
                }
            }
            // The chips: "todas", the app's own "vencidas" while any is, then the tags the
            // routines wear — in the tags' own colours, as on Home.
            if (state.loaded && !state.failed && state.total > 0) {
                item(key = "filters", contentType = "filters") {
                    FilterRow(filters = state.filters, selected = state.filter, onSelect = viewModel::selectFilter)
                }
            }
            pendingDelete?.let { removed ->
                item(key = "undo-delete", contentType = "undo") {
                    UndoDeleteRow(
                        text = removed.reminder.text,
                        onUndo = { viewModel.undo(removed.reminder, removed.history) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (state.empty) {
                item(key = "empty") {
                    Box(Modifier.fillParentMaxHeight(0.7f), contentAlignment = Alignment.Center) {
                        EmptyState(
                            title = stringResource(R.string.routines_empty_title),
                            body = stringResource(R.string.routines_empty_body),
                            icon = Icons.Outlined.Autorenew,
                            actionLabel = stringResource(R.string.routines_new),
                            onAction = onNew,
                        )
                    }
                }
            } else if (state.loaded && !state.failed && state.rows.isEmpty()) {
                item(key = "none-under-filter") {
                    EmptyState(
                        title = stringResource(R.string.routines_filter_none_title),
                        body = stringResource(R.string.routines_filter_none_body),
                        icon = Icons.Outlined.SearchOff,
                    )
                }
            }
            items(state.rows, key = { it.id }, contentType = { "routine" }) { row ->
                SwipeableCard(
                    onDone = { viewModel.markDone(row.id) },
                    onDelete = { viewModel.delete(row.id) },
                    modifier = Modifier.animateItem(),
                ) {
                    RoutineCard(
                        row = row,
                        now = now,
                        onOpen = { onOpen(row.id) },
                        onLongClick = { actingOn = row.id },
                        longClickLabel = actionsLabel,
                    )
                }
            }
        }
    }

    actingOn?.let { id ->
        val held = state.rows.firstOrNull { it.id == id } ?: run { actingOn = null; return@let }
        ReminderActionsMenu(
            words = held.text,
            paused = held.paused,
            snoozeOffered = held.snoozeOffered,
            snoozed = held.snoozed,
            customMinutes = snoozeCustomMinutes,
            onDone = { actingOn = null; viewModel.markDone(held.id) },
            onPause = { actingOn = null; viewModel.togglePause(held.id, held.paused) },
            onDelete = { actingOn = null; viewModel.delete(held.id) },
            onSnooze = { snooze -> actingOn = null; viewModel.snooze(held.id, snooze) },
            onCancelSnooze = { actingOn = null; viewModel.cancelSnooze(held.id) },
            onClone = { actingOn = null; onClone(held.id) },
            onKeepAsPreset = { actingOn = null; onKeepAsPreset(held.id) },
            onDismiss = { actingOn = null },
        )
    }
}

@Composable
private fun FilterRow(filters: List<RoutineFilter>, selected: RoutineFilter, onSelect: (RoutineFilter) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm), modifier = Modifier.fillMaxWidth()) {
        item(key = "all") {
            TagChip(label = stringResource(R.string.routines_filter_all), selected = selected == RoutineFilter.All, onClick = { onSelect(RoutineFilter.All) })
        }
        items(filters, key = { it.key }) { filter ->
            TagChip(
                label = when (filter) {
                    is RoutineFilter.Tag -> filter.tag
                    else -> stringResource(R.string.routines_filter_overdue)
                },
                selected = filter == selected,
                onClick = { onSelect(filter) },
                // The app's own chip stays neutral: it is not somebody's word for something.
                tint = (filter as? RoutineFilter.Tag)?.let { tagColor(it.tag) },
            )
        }
    }
}

private val RoutineFilter.key: String
    get() = when (this) {
        RoutineFilter.All -> "all"
        RoutineFilter.Overdue -> "rwilco-overdue"
        is RoutineFilter.Tag -> "tag-$tag"
    }

/**
 * One routine: the question, the answer, the count, the track, the tags. The answer is set in
 * the words' own type at the end of the question, because it is part of the sentence; "No"
 * wears the error ink, which is what "the span ran out" looks like everywhere in this app.
 */
@Composable
private fun RoutineCard(
    row: RoutineRowUi,
    now: java.time.Instant,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    longClickLabel: String,
) {
    val scheme = MaterialTheme.colorScheme
    val spacing = Tokens.spacing
    val ink = if (row.paused) scheme.onSurfaceVariant else scheme.onSurface
    val answerInk = when {
        row.paused -> scheme.onSurfaceVariant
        row.done -> scheme.onSurfaceVariant
        else -> scheme.error
    }
    val question = stringResource(R.string.routines_question, row.text)
    val answer = stringResource(if (row.done) R.string.routines_yes else R.string.routines_no)
    // Under a minute it is "ahora mismo": a "hecho" given a moment ago sits a few seconds either
    // side of the last minute pulse, and neither "en 5 s" nor "hace 0 s" is how long it has been.
    val elapsed = if (Duration.between(row.anchor, now) < Duration.ofMinutes(1)) stringResource(R.string.countdown_just_now)
    else countdownText(partsBetween(now, row.anchor))
    val due = countdownText(partsBetween(now, row.deadline))
    val dueLine = elapsed + stringResource(R.string.common_separator) +
        stringResource(if (row.done) R.string.routines_due else R.string.routines_overdue, due)
    // How far through the span it is: a full track is a "No".
    val progress = if (row.span.isZero) 1f else (Duration.between(row.anchor, now).toMillis().toFloat() / row.span.toMillis()).coerceIn(0f, 1f)
    RwilcoCard(onClick = onOpen, onLongClick = onLongClick, longClickLabel = longClickLabel) {
        Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
            Text(
                text = buildAnnotatedString {
                    append(question)
                    append("  →  ")
                    withStyle(SpanStyle(color = answerInk, fontWeight = FontWeight.Bold)) { append(answer) }
                },
                style = MaterialTheme.typography.titleMedium,
                color = ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(spacing.xs))
            Text(
                text = dueLine,
                style = MonoStyles.date,
                color = if (row.done || row.paused) scheme.onSurfaceVariant else scheme.error,
            )
            Spacer(Modifier.height(spacing.sm))
            LinearProgressIndicator(
                progress = { progress },
                color = if (row.done || row.paused) scheme.onSurfaceVariant else scheme.error,
                trackColor = scheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth(),
            )
            if (row.tags.isNotEmpty()) {
                Spacer(Modifier.height(spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    for (tag in row.tags.take(3)) TagLabel(tag)
                }
            }
        }
    }
}
