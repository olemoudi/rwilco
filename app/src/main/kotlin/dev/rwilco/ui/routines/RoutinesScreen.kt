package dev.rwilco.ui.routines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
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
import dev.rwilco.ui.home.SnoozedRow
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.BackHandler
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.vector.ImageVector
import dev.rwilco.R
import dev.rwilco.model.ContactKind
import dev.rwilco.model.RoutineFilter
import dev.rwilco.model.partsBetween
import dev.rwilco.ui.components.EmptyState
import dev.rwilco.ui.components.ListPlaceholder
import dev.rwilco.ui.components.LocalSnackbar
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.RwilcoTopBar
import dev.rwilco.ui.components.MomentSheet
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
import dev.rwilco.ui.theme.routineColor
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
    onNew: (ContactKind?) -> Unit,
    onClone: (String) -> Unit,
    onKeepAsPreset: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val compact by viewModel.compact.collectAsStateWithLifecycle()
    val flippedRows by viewModel.flippedRows.collectAsStateWithLifecycle()
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
    val deletedMessage = stringResource(R.string.routines_deleted)
    val pausedMessage = stringResource(R.string.routines_paused_snack)
    val resumedMessage = stringResource(R.string.routines_resumed)
    val snoozeCancelledMessage = stringResource(R.string.home_snooze_cancelled)
    val undoLabel = stringResource(R.string.common_undo)
    val actionsLabel = stringResource(R.string.routines_card_actions)
    // The row being held, and so the one the actions menu is about.
    var actingOn by rememberSaveable { mutableStateOf<String?>(null) }
    // And the one a calendar is open for: "posponer · a una fecha concreta" asks a second
    // question, so the menu closes and the sheet takes over.
    var pickingDateFor by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    // The magnifier in the bar opens a field in its place, the way Home's does — the same
    // control, so the gesture and the keyboard behave the same on both screens.
    // Three things can be made from here now, so the + asks which rather than assuming.
    var choosing by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    // A routine arrived at from its own row on Home is scrolled to, once: the list is rebuilt
    // every minute (the counts move), and a scroll on every rebuild would fight the thumb.
    var landed by rememberSaveable { mutableStateOf(false) }
    // Back closes the search first and then the chip, as on Home: both are states somebody put
    // the screen in, and Back used to leave the screen with the list still narrowed.
    BackHandler(enabled = searching) { searching = false; viewModel.search("") }
    BackHandler(enabled = !searching && state.filter != RoutineFilter.All) { viewModel.selectFilter(RoutineFilter.All) }

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
        // And open, whatever the mode: Home's line named this one, so it is the one somebody
        // came here to read.
        viewModel.expandRow(focus)
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
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                // Above "Nueva rutina", and the smaller of the two: this one is about *reading*
                // the list and that one is the screen's job. The same button Home carries, in
                // the same corner, saying the same words.
                SmallFloatingActionButton(
                    onClick = { haptics.perform(HapticFeedbackType.ContextClick); viewModel.setCompact(!compact) },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.sizeIn(minWidth = Tokens.sizes.touch, minHeight = Tokens.sizes.touch),
                ) {
                    Icon(
                        imageVector = if (compact) Icons.Outlined.UnfoldMore else Icons.Outlined.UnfoldLess,
                        contentDescription = stringResource(if (compact) R.string.home_compact_off else R.string.home_compact_on),
                    )
                }
                // The screen's one primary action, in the thumb zone, wearing the inverted
                // neutral every primary in the app wears: amber is what fires next, and this
                // is a door.
                ExtendedFloatingActionButton(
                    onClick = { haptics.perform(HapticFeedbackType.Confirm); choosing = true },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.routines_new), style = MaterialTheme.typography.titleMedium) },
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.heightIn(min = Tokens.sizes.primary),
                )
            }
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
                        title = stringResource(R.string.routines_failed_title),
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
                            onAction = { choosing = true },
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
            items(
                state.rows,
                key = { it.id },
                // Two shapes, two content types: a folded row and an open one recycle nothing
                // useful from each other.
                contentType = { if (compact != (it.id in flippedRows)) "routine-compact" else "routine" },
            ) { row ->
                SwipeableCard(
                    onDone = { viewModel.markDone(row.id) },
                    onDelete = { viewModel.delete(row.id) },
                    modifier = Modifier.animateItem(),
                ) {
                    RoutineCard(
                        row = row,
                        now = now,
                        compact = compact != (row.id in flippedRows),
                        onToggleCompact = { viewModel.flipRow(row.id) },
                        onOpen = { onOpen(row.id) },
                        onPause = { viewModel.togglePause(row.id, row.paused) },
                        onMore = { actingOn = row.id },
                        onDone = { viewModel.markDone(row.id) },
                        zone = zone,
                        longClickLabel = actionsLabel,
                    )
                }
            }
        }
    }

    if (choosing) {
        NewRoutineChooser(
            onPick = { kind -> choosing = false; onNew(kind) },
            onDismiss = { choosing = false },
        )
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
            routine = true,
        )
    }

    pickingDateFor?.let { id ->
        MomentSheet(
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
                    RoutineFilter.Paused -> stringResource(R.string.routines_filter_paused)
                    RoutineFilter.Waiting -> stringResource(R.string.routines_filter_waiting)
                    is RoutineFilter.Kind -> stringResource(
                        if (filter.kind == ContactKind.WORK) R.string.routines_filter_work else R.string.routines_filter_personal,
                    )
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
        RoutineFilter.Paused -> "rwilco-paused"
        RoutineFilter.Waiting -> "rwilco-waiting"
        is RoutineFilter.Kind -> "rwilco-kind-${kind.name}"
        is RoutineFilter.Tag -> "tag-$tag"
    }

/**
 * One routine: the question, the answer, the count, the track, the tags. The answer is set in
 * the words' own type at the end of the question, because it is part of the sentence; "No"
 * wears the error ink, which is what "the span ran out" looks like everywhere in this app.
 *
 * **[compact] is the same routine as its words and its track** — no count, no controls, no
 * tags. What a folded list answers is "how much is owed, and how badly", and the track answers
 * exactly that: how far through the plazo each one is, in a column you read down rather than
 * across. The tap is the fold, both ways, as it is on Home ([dev.rwilco.ui.home.ReminderCard]);
 * the form stays behind the pencil on the open card, which is somewhere a thumb arrives on
 * purpose.
 *
 * **The colour is the routines' own** ([routineColor]): the band down the leading edge, and the
 * track while the plazo is still running. A screen made of grey cards with a grey hairline
 * under each said nothing until you read it — and the one thing worth seeing without reading is
 * which of them have run out, which is the track turning red.
 */
@Composable
private fun RoutineCard(
    row: RoutineRowUi,
    now: Instant,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onMore: () -> Unit,
    longClickLabel: String,
    /** "Sí, lo he hecho": the swipe's door, on a button. */
    onDone: () -> Unit = {},
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    compact: Boolean = false,
    /** The tap, on a card of either height: out on a folded one, away on an open one. */
    onToggleCompact: () -> Unit = {},
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
    // Resting or put off, the answer is neither: the state is said in its place (see below).
    val answer = when {
        row.paused -> stringResource(R.string.routines_paused)
        row.putOff -> stringResource(R.string.routines_put_off)
        row.done -> stringResource(R.string.routines_yes)
        else -> stringResource(R.string.routines_no)
    }
    // Under a minute it is "ahora mismo": a "hecho" given a moment ago sits a few seconds either
    // side of the last minute pulse, and neither "en 5 s" nor "hace 0 s" is how long it has been.
    // A routine whose count has not begun says so instead ("empieza el martes"): "hace" is a
    // word about time that has passed, and none of it has.
    // A paused routine's count is read against the moment it was paused, not the wall clock:
    // the time it rests is not time that passed (Reminder.routineClock).
    val clock = row.pausedAt ?: now
    val elapsed = when {
        row.startsLater -> stringResource(R.string.routines_starts, countdownText(partsBetween(clock, row.anchor)))
        Duration.between(row.anchor, clock) < Duration.ofMinutes(1) -> stringResource(R.string.countdown_just_now)
        else -> countdownText(partsBetween(clock, row.anchor))
    }
    val due = countdownText(partsBetween(clock, row.deadline))
    val separator = stringResource(R.string.common_separator)
    // A contact is not late, it is queued: what it is waiting for is its turn, and saying
    // "vencida hace 3 semanas" over somebody the budget is pacing would be a lie the screen
    // tells about its own arithmetic. Told about and unanswered, it reads like anything overdue.
    val dueLine = when {
        row.contactKind == null -> elapsed + separator + stringResource(if (row.done) R.string.routines_due else R.string.routines_overdue, due)
        !row.done -> elapsed + separator + stringResource(R.string.routines_contact_unanswered)
        row.turnAt == null -> elapsed + separator + stringResource(R.string.routines_contact_no_turn)
        else -> elapsed + separator + stringResource(R.string.routines_contact_turn, due)
    }
    // How far through the span it is: a full track is a "No".
    val progress = if (row.span.isZero) 1f else (Duration.between(row.anchor, clock).toMillis().toFloat() / row.span.toMillis()).coerceIn(0f, 1f)
    val haptics = Tokens.haptics
    // **An overdue routine is red from the edge in.** A paused one owes nothing while it rests,
    // so it drops the colour with the rest of it; a routine still inside its plazo wears the
    // routines' own; and one whose plazo has run out wears the error ink in all three places a
    // card has to say it — the band down the edge, the wash under the whole card, and the
    // track. Folded away there is no "No" to read and no count: the colour is the only thing
    // left to notice, so it has to be impossible to miss rather than tasteful.
    // Put off is an answer given: the row is not owed, and says so instead of "No".
    val overdue = !row.done && !row.paused && !row.putOff
    val accent = when {
        row.paused -> scheme.onSurfaceVariant
        overdue -> scheme.error
        else -> routineColor()
    }
    val cardColour = if (overdue) scheme.errorContainer.copy(alpha = OVERDUE_WASH_ALPHA).compositeOver(scheme.surfaceContainer) else scheme.surfaceContainer
    val track: @Composable (Modifier, Dp) -> Unit = { trackModifier, weight ->
        LinearProgressIndicator(
            progress = { progress },
            color = if (row.paused) scheme.outline else accent,
            trackColor = scheme.surfaceContainerHighest,
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
            modifier = trackModifier.fillMaxWidth().height(weight),
        )
    }
    if (compact) {
        RwilcoCard(
            onClick = onToggleCompact,
            onLongClick = onMore,
            longClickLabel = longClickLabel,
            clickLabel = stringResource(R.string.card_expand),
            color = cardColour,
            rail = accent,
            // Folded, the state lives in the colour and the track; a screen reader hears it
            // here instead — "Sí · hace 1 d · vence en 19 d".
            modifier = Modifier.semantics { stateDescription = answer + separator + dueLine },
        ) {
            Column(modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = row.text,
                        style = MaterialTheme.typography.titleMedium,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // The word itself, on the one that has run out. Colour says "look here" and
                    // this says what it is — and only on a "No": a column of "Sí"s down the
                    // right-hand side would be six answers to a question nobody asked, on the
                    // shape whose whole point is that it is the words and the track.
                    if (overdue) {
                        Spacer(Modifier.width(spacing.sm))
                        Text(
                            text = stringResource(R.string.routines_no),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = scheme.error,
                        )
                    }
                    // Folded, a routine put off or resting says so with one word, where the
                    // "No" would go: the state is the one thing the fold keeps.
                    if (row.putOff || row.paused) {
                        Spacer(Modifier.width(spacing.sm))
                        Text(
                            text = stringResource(if (row.paused) R.string.routines_paused else R.string.routines_put_off),
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                // Thicker than the open card's, because here it is the card's whole answer.
                track(Modifier.padding(top = spacing.sm), Tokens.strokes.track)
            }
        }
        return
    }
    RwilcoCard(
        onClick = onToggleCompact,
        onLongClick = onMore,
        longClickLabel = longClickLabel,
        clickLabel = stringResource(R.string.card_compact),
        color = cardColour,
        rail = accent,
    ) {
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
                // The arrow is for the eye; a screen reader gets the sentence.
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$question $answer" },
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
            // Put off until a clock: the same row Home's card carries, so a routine answered
            // "tomorrow" does not look identical to one nobody answered.
            row.snoozedUntil?.let { until ->
                Spacer(Modifier.height(spacing.sm))
                SnoozedRow(until = until, today = now.atZone(zone).toLocalDate(), zone = zone, muted = true)
            }
            track(Modifier.padding(top = spacing.md), Tokens.strokes.strong)
            // The footer: the tags on the left, the three things a card can be told to do on
            // the right — the same three a reminder's card carries, and the same menu behind
            // the "⋯" that the held press opens.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = spacing.xs)) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs), modifier = Modifier.weight(1f)) {
                    for (tag in row.tags.take(3)) TagLabel(tag)
                }
                // "Sí" — the answer to the card's own question — first among the controls,
                // and a button: it was a swipe-and-hold, a held menu and a screen-reader
                // action, and none of those is a thing a thumb finds. Not while it rests: a
                // "hecho" on a paused routine would move a count that is not running.
                // Nor before the count begins: a "sí" on "empieza el 1 de octubre" would throw
                // the start away for a "hecho" nobody meant.
                if (!row.paused && !row.startsLater) {
                    val markDone = stringResource(R.string.routines_mark_done, row.text)
                    OutlinedButton(
                        onClick = { haptics.perform(HapticFeedbackType.Confirm); onDone() },
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(Tokens.strokes.control, scheme.outline),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = scheme.surfaceContainerHigh, contentColor = scheme.onSurface),
                        contentPadding = PaddingValues(horizontal = spacing.md),
                        modifier = Modifier.heightIn(min = Tokens.sizes.touch).semantics { contentDescription = markDone },
                    ) {
                        Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(Tokens.sizes.glyphSmall))
                        Spacer(Modifier.width(spacing.xs))
                        Text(stringResource(R.string.routines_yes), style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.width(spacing.xs))
                }
                IconButton(onClick = { haptics.perform(HapticFeedbackType.ContextClick); onPause() }) {
                    Icon(
                        imageVector = if (row.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        contentDescription = stringResource(if (row.paused) R.string.routines_resume else R.string.routines_pause, row.text),
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
/** How much of the error container the card's wash carries: a tint, never a red card. */
private const val OVERDUE_WASH_ALPHA = 0.3f

/**
 * What the + on the routines screen makes: a routine, or a contact of either kind.
 *
 * A sheet rather than three buttons because two of the three are the same thing wearing a
 * different half of a life, and a row of three primaries would say they were three features.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewRoutineChooser(onPick: (ContactKind?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Tokens.spacing.screen)
                .padding(bottom = Tokens.spacing.xl)
                .navigationBarsPadding(),
        ) {
            ChooserRow(Icons.Outlined.Autorenew, stringResource(R.string.routines_new_routine), stringResource(R.string.routines_new_routine_hint), routineColor()) { onPick(null) }
            ChooserRow(Icons.Outlined.WorkOutline, stringResource(R.string.routines_new_contact_work), stringResource(R.string.routines_new_contact_work_hint), routineColor()) { onPick(ContactKind.WORK) }
            ChooserRow(Icons.Outlined.Person, stringResource(R.string.routines_new_contact_personal), stringResource(R.string.routines_new_contact_personal_hint), routineColor()) { onPick(ContactKind.PERSONAL) }
        }
    }
}

@Composable
private fun ChooserRow(icon: ImageVector, title: String, hint: String, accent: Color, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    RwilcoCard(onClick = onClick, rail = accent, modifier = Modifier.semantics { contentDescription = "$title. $hint" }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Tokens.sizes.primary)
                .padding(horizontal = Tokens.spacing.lg, vertical = Tokens.spacing.md),
        ) {
            Icon(icon, contentDescription = null, tint = accent)
            Spacer(Modifier.width(Tokens.spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}
