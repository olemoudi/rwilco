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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import dev.rwilco.ui.components.FutureMomentSheet
import dev.rwilco.ui.components.TagChip
import dev.rwilco.ui.components.TagLabel
import dev.rwilco.ui.components.rememberNow
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.countdownText
import dev.rwilco.ui.format.dayWord
import dev.rwilco.ui.format.rememberWords
import dev.rwilco.ui.home.ReminderActionsMenu
import dev.rwilco.ui.home.SearchField
import dev.rwilco.ui.home.SwipeableCard
import dev.rwilco.ui.home.UndoDeleteRow
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.tagColor
import java.time.Clock
import java.time.Duration
import java.time.Instant

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
    /** The routine to bring into view: a row tapped on Home names one (see `RoutinesLine.kt`). */
    focus: String? = null,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onClone: (String) -> Unit,
    onKeepAsPreset: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()
    val snoozeCustomMinutes by viewModel.snoozeCustomMinutes.collectAsStateWithLifecycle()
    val defaultTime by viewModel.defaultTime.collectAsStateWithLifecycle()
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
    // And the one a calendar is open for: "posponer · a una fecha concreta" asks a second
    // question, so the menu closes and the sheet takes over.
    var pickingDateFor by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    // The magnifier in the bar opens a field in its place, the way Home's does — the same
    // control, so the gesture and the keyboard behave the same on both screens.
    var searching by rememberSaveable { mutableStateOf(false) }
    // A routine arrived at from its own row on Home is scrolled to, once: the list is rebuilt
    // every minute (the counts move), and a scroll on every rebuild would fight the thumb.
    var landed by rememberSaveable { mutableStateOf(false) }

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

    val filtersShown = state.loaded && !state.failed && state.total > 0
    LaunchedEffect(focus, state.loaded, state.rows.size) {
        if (focus == null || landed || !state.loaded) return@LaunchedEffect
        val row = state.rows.indexOfFirst { it.id == focus }
        if (row < 0) return@LaunchedEffect
        // Everything the list draws above the rows, in the order it draws them.
        val leading = (if (filtersShown) 1 else 0) + (if (pendingDelete != null) 1 else 0)
        listState.animateScrollToItem(leading + row)
        landed = true
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (searching) {
                // The field is Home's, and Home's header handles its own insets before drawing
                // it; here it is the top bar itself, so it takes the status bar's room the way
                // [RwilcoTopBar] does — without it the placeholder sits under the clock.
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.statusBarsPadding().padding(horizontal = spacing.sm, vertical = spacing.xs)) {
                        SearchField(
                            query = state.query,
                            onQueryChange = viewModel::search,
                            onClose = { searching = false; viewModel.search("") },
                        )
                    }
                }
            } else {
                RwilcoTopBar(
                    title = stringResource(R.string.routines_title),
                    onBack = onBack,
                    action = {
                        val haptics = Tokens.haptics
                        IconButton(onClick = { haptics.perform(HapticFeedbackType.ContextClick); searching = true }) {
                            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.home_search))
                        }
                    },
                )
            }
        },
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
            state = listState,
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
            if (filtersShown) {
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
                        // Two different nothings: no routine answers these words, or none is
                        // under this chip. The way out is a different one for each.
                        title = stringResource(if (state.query.isNotBlank()) R.string.home_search_none_title else R.string.routines_filter_none_title),
                        body = stringResource(if (state.query.isNotBlank()) R.string.home_search_none_body else R.string.routines_filter_none_body),
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
                        onPause = { viewModel.togglePause(row.id, row.paused) },
                        onMore = { actingOn = row.id },
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
            onSnoozeToDate = { actingOn = null; pickingDateFor = held.id },
            onCancelSnooze = { actingOn = null; viewModel.cancelSnooze(held.id) },
            onClone = { actingOn = null; onClone(held.id) },
            onKeepAsPreset = { actingOn = null; onKeepAsPreset(held.id) },
            onDismiss = { actingOn = null },
        )
    }

    pickingDateFor?.let { id ->
        FutureMomentSheet(
            now = clock.instant().atZone(zone),
            defaultTime = defaultTime,
            onConfirm = { until -> pickingDateFor = null; viewModel.snoozeUntil(id, until) },
            onDismiss = { pickingDateFor = null },
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
    now: Instant,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onMore: () -> Unit,
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
    // A routine whose count has not begun says so instead ("empieza el martes"): "hace" is a
    // word about time that has passed, and none of it has.
    val elapsed = when {
        row.startsLater -> stringResource(R.string.routines_starts, countdownText(partsBetween(now, row.anchor)))
        Duration.between(row.anchor, now) < Duration.ofMinutes(1) -> stringResource(R.string.countdown_just_now)
        else -> countdownText(partsBetween(now, row.anchor))
    }
    val due = countdownText(partsBetween(now, row.deadline))
    val dueLine = elapsed + stringResource(R.string.common_separator) +
        stringResource(if (row.done) R.string.routines_due else R.string.routines_overdue, due)
    // How far through the span it is: a full track is a "No".
    val progress = if (row.span.isZero) 1f else (Duration.between(row.anchor, now).toMillis().toFloat() / row.span.toMillis()).coerceIn(0f, 1f)
    val haptics = Tokens.haptics
    RwilcoCard(onClick = onOpen, onLongClick = onMore, longClickLabel = longClickLabel) {
        Column(Modifier.padding(start = spacing.lg, end = spacing.lg, top = spacing.lg, bottom = spacing.sm)) {
            // **The words get the whole width.** Boxed in beside three buttons the question
            // broke over three lines with a column of air down the right; the controls go to
            // the footer instead, which is where a card's furniture lives (see HeroCard).
            Text(
                text = buildAnnotatedString {
                    append(question)
                    append("  →  ")
                    withStyle(SpanStyle(color = answerInk, fontWeight = FontWeight.Bold)) { append(answer) }
                },
                style = MaterialTheme.typography.titleLarge,
                color = ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(spacing.sm))
            // **The count and its track are furniture, not the alarm.** The answer at the end
            // of the question is what says "No", in the error ink and in bold; a second line of
            // red under it and a red bar under that was the same word said three times. So the
            // line is the quiet ink whatever the answer is, and the track is a hairline.
            Text(
                text = dueLine,
                style = MonoStyles.date,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.md))
            LinearProgressIndicator(
                progress = { progress },
                color = if (row.done || row.paused) scheme.outline else scheme.error.copy(alpha = OVERDUE_TRACK_ALPHA),
                trackColor = scheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().height(Tokens.strokes.strong),
            )
            // The footer: the tags on the left, the three things a card can be told to do on
            // the right — the same three a reminder's card carries, and the same menu behind
            // the "⋯" that the held press opens.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = spacing.xs)) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs), modifier = Modifier.weight(1f)) {
                    for (tag in row.tags.take(3)) TagLabel(tag)
                }
                IconButton(onClick = { haptics.perform(HapticFeedbackType.ContextClick); onPause() }) {
                    Icon(
                        imageVector = if (row.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        contentDescription = stringResource(if (row.paused) R.string.card_resume else R.string.card_pause),
                        tint = scheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { haptics.perform(HapticFeedbackType.ContextClick); onOpen() }) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.card_edit, row.text),
                        tint = scheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { haptics.perform(HapticFeedbackType.ContextClick); onMore() }) {
                    Icon(
                        imageVector = Icons.Outlined.MoreHoriz,
                        contentDescription = stringResource(R.string.card_more, row.text),
                        tint = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A full track on an overdue routine still says so, at the volume a line under the words wants. */
private const val OVERDUE_TRACK_ALPHA = 0.55f
