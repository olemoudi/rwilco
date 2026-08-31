package dev.rwilco.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import dev.rwilco.shortcuts.PresetShortcuts
import dev.rwilco.R
import dev.rwilco.model.Section
import dev.rwilco.model.TagFilter
import dev.rwilco.ui.components.EmptyState
import dev.rwilco.ui.components.ListPlaceholder
import dev.rwilco.ui.components.LocalSnackbar
import dev.rwilco.ui.components.SectionHeader
import dev.rwilco.ui.components.TagChip
import dev.rwilco.ui.components.rememberNow
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.model.TriggerFamily
import dev.rwilco.ui.theme.LocalDarkTheme
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.familyColor
import dev.rwilco.ui.theme.tagColor
import dev.rwilco.ui.format.rememberWords
import dev.rwilco.ui.format.dayWord
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.settings.rememberAlertReadiness
import dev.rwilco.ui.settings.stripShows
import dev.rwilco.ui.format.snoozePlacePhrase

/** So a test can scroll the list itself; a lazy list does not compose what is off screen. */
const val HOME_LIST_TAG = "homeList"

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    /** A pinned preset a launcher shortcut asked for: written here, the way its button is. */
    requestedPreset: String? = null,
    onPresetConsumed: () -> Unit = {},
    onNew: () -> Unit,
    onNewFromPreset: (String) -> Unit,
    onEditPreset: (String) -> Unit,
    onNewPreset: () -> Unit,
    onOpen: (String) -> Unit,
    /** A new reminder shaped like this one, waiting for its own words. */
    onClone: (String) -> Unit,
    /** This reminder's shape, kept under a name: the preset form, already filled in. */
    onKeepAsPreset: (String) -> Unit,
    onDoneList: () -> Unit,
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val pinned by viewModel.pinnedPresets.collectAsStateWithLifecycle()
    val snoozeCustomMinutes by viewModel.snoozeCustomMinutes.collectAsStateWithLifecycle()
    val placeOffers by viewModel.placeOffers.collectAsStateWithLifecycle()
    val hereLabel = stringResource(R.string.snooze_here_label)
    val noFixMessage = stringResource(R.string.snooze_no_fix)
    // Whether this phone can ring at all, re-read on every resume; Settings has the detail.
    val readiness = rememberAlertReadiness()
    val dismissedProblems by viewModel.dismissedAlertProblems.collectAsStateWithLifecycle()
    LaunchedEffect(readiness) { viewModel.noteAlertReadiness(readiness) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    // **"Nuevo" asks which kind only while the answer is not already on the screen.** The
    // question ("en blanco" or "un preset") was asked the moment a single preset existed
    // anywhere, so from then on every blank reminder — which is most of them — paid a tap for
    // ever. A pinned preset is one tap away in the row above the list and one hold away on the
    // launcher icon, so with a row up there the dialog is asking something the screen has
    // already answered. With presets kept but none pinned it is still the only door to them,
    // and it still opens.
    val asksWhichKind = presets.isNotEmpty() && pinned.isEmpty()
    var managingPins by rememberSaveable { mutableStateOf(false) }
    // The preset whose words are being asked for before it can be written.
    var askingWordsFor by rememberSaveable { mutableStateOf<String?>(null) }
    // The card being held, and so the one the actions menu is about.
    var actingOn by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = LocalSnackbar.current
    val doneMessage = stringResource(R.string.home_marked_done)
    val deletedMessage = stringResource(R.string.home_deleted)
    val skippedMessage = stringResource(R.string.home_skipped)
    val undoLabel = stringResource(R.string.common_undo)
    val cardActionsLabel = stringResource(R.string.home_card_actions)
    val swipeDoneLabel = stringResource(R.string.card_swipe_done)
    val swipeDeleteLabel = stringResource(R.string.card_swipe_delete)
    val createdMessage = stringResource(R.string.home_created)
    val pausedMessage = stringResource(R.string.home_paused)
    val resumedMessage = stringResource(R.string.home_resumed)
    val snoozeCancelledMessage = stringResource(R.string.home_snooze_cancelled)
    val words = rememberWords()
    val zone = viewModel.clock.zone

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is HomeEvent.Removed -> snackbar.show(
                    message = when (event.kind) {
                        HomeEvent.Removed.Kind.DONE -> doneMessage
                        HomeEvent.Removed.Kind.DELETED -> deletedMessage
                        HomeEvent.Removed.Kind.SKIPPED -> skippedMessage
                    },
                    undoLabel = undoLabel,
                    onUndo = { viewModel.undo(event) },
                )
                is HomeEvent.Created -> snackbar.show(
                    message = createdMessage,
                    undoLabel = undoLabel,
                    onUndo = { viewModel.undoCreated(event.reminder, event.preset) },
                )
                is HomeEvent.Paused -> snackbar.show(
                    message = if (event.paused) pausedMessage else resumedMessage,
                    undoLabel = undoLabel,
                    onUndo = { viewModel.undoPause(event) },
                )
                is HomeEvent.Snoozed -> snackbar.show(
                    message = if (event.cancelled) snoozeCancelledMessage
                    else event.place?.let { words.get(R.string.home_snoozed_until, snoozePlacePhrase(words, it)) }
                        ?: event.until?.let { until ->
                            val here = until.atZone(zone)
                            val todayHere = viewModel.clock.instant().atZone(zone).toLocalDate()
                            words.get(R.string.home_snoozed_until, dayWord(words, here.toLocalDate(), todayHere) + " " + TimeText.time(here.toLocalTime(), words.is24h, words.locale))
                        }
                        ?: snoozeCancelledMessage,
                    undoLabel = undoLabel,
                    onUndo = { viewModel.undoSnooze(event) },
                )
                HomeEvent.NoFix -> snackbar.show(message = noFixMessage)
                // Something it carries has already passed: the form, not a silent overdue.
                is HomeEvent.NeedsEditor -> onNewFromPreset(event.presetId)
            }
        }
    }

    BackHandler(enabled = search.open) { viewModel.setSearching(false) }

    // The same two doors the pinned button has: words of its own, written on the spot; none,
    // asked for first. Waits for the presets to have arrived; a shortcut for a preset that no
    // longer exists is consumed and does nothing (the launcher's list is republished anyway).
    val context = LocalContext.current
    LaunchedEffect(requestedPreset, presets, state.loaded) {
        val id = requestedPreset ?: return@LaunchedEffect
        if (!state.loaded || presets.isEmpty()) return@LaunchedEffect
        val preset = presets.firstOrNull { it.id == id }
        if (preset != null) {
            PresetShortcuts.used(context, preset.id)
            if (preset.text.isBlank()) askingWordsFor = preset.id
            else viewModel.createFromPreset(preset, null, state.defaultTime, state.dayShape)
        }
        onPresetConsumed()
    }

    if (choosing) {
        NewReminderChooser(
            presets = presets,
            onBlank = {
                choosing = false
                onNew()
            },
            onPreset = { preset ->
                choosing = false
                viewModel.usePreset(preset)
                onNewFromPreset(preset.id)
            },
            onEditPreset = { preset ->
                choosing = false
                onEditPreset(preset.id)
            },
            onDismiss = { choosing = false },
        )
    }

    if (managingPins) {
        PinPresetsPanel(
            presets = presets,
            onTogglePin = viewModel::togglePin,
            onCreate = {
                managingPins = false
                onNewPreset()
            },
            onDismiss = { managingPins = false },
        )
    }
    askingWordsFor?.let { id ->
        presets.firstOrNull { it.id == id }?.let { preset ->
            PresetWordsDialog(
                preset = preset,
                onConfirm = { words, actions ->
                    askingWordsFor = null
                    viewModel.createFromPreset(preset, words, state.defaultTime, state.dayShape, actions)
                },
                onDismiss = { askingWordsFor = null },
            )
        }
    }

    // Held on a card: what can be done to that reminder. The words come off the card rather
    // than the id alone, so the menu can say which one it caught.
    actingOn?.let { id ->
        val held = state.hero?.card?.takeIf { it.id == id }
            ?: state.sections.firstNotNullOfOrNull { section -> section.cards.firstOrNull { it.id == id } }
        if (held == null) {
            actingOn = null
        } else {
            ReminderActionsMenu(
                words = held.text,
                paused = held.paused,
                snoozeOffered = held.snoozeOffered,
                snoozed = held.snoozedUntil != null || held.snoozedToPlace != null,
                customMinutes = snoozeCustomMinutes,
                places = placeOffers,
                onSnoozeToPlace = { offer ->
                    actingOn = null
                    viewModel.snoozeToPlace(held.id, offer, hereLabel)
                },
                // The moment that would be let pass, said the way the card says a moment.
                skipHint = held.skipsMoment?.let { moment ->
                    val here = moment.atZone(zone)
                    val todayHere = viewModel.clock.instant().atZone(zone).toLocalDate()
                    words.get(R.string.home_skip_hint, dayWord(words, here.toLocalDate(), todayHere) + " " + TimeText.time(here.toLocalTime(), words.is24h, words.locale))
                },
                onDone = {
                    actingOn = null
                    viewModel.markDone(held.id)
                },
                onSkip = {
                    actingOn = null
                    viewModel.skipNext(held.id)
                },
                onPause = {
                    actingOn = null
                    viewModel.togglePause(held.id, held.paused)
                },
                onDelete = {
                    actingOn = null
                    viewModel.delete(held.id)
                },
                onSnooze = { snooze ->
                    actingOn = null
                    viewModel.snooze(held.id, snooze)
                },
                onCancelSnooze = {
                    actingOn = null
                    viewModel.cancelSnooze(held.id)
                },
                onClone = {
                    actingOn = null
                    onClone(held.id)
                },
                onKeepAsPreset = {
                    actingOn = null
                    onKeepAsPreset(held.id)
                },
                onDismiss = { actingOn = null },
            )
        }
    }

    val spacing = Tokens.spacing
    // The row with the wordmark, the cog and the three icons is the screen's own, not the
    // list's first item: it leaves going down and comes back going up, so the way into
    // Settings is one flick away from anywhere in the list rather than a scroll to the top.
    // Pinned while searching — the field up there is what the thumb is aiming at.
    val headerScroll = rememberHeaderScroll(pinned = search.open)
    Scaffold(
        modifier = Modifier.nestedScroll(headerScroll.connection),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Moved, not re-laid-out: the height the Scaffold hands the list below
                    // stays what it was, so a drag costs one layer offset and no measuring.
                    .graphicsLayer { translationY = headerScroll.offsetPx }
                    // Opaque, because the cards travel under it rather than beside it.
                    .background(MaterialTheme.colorScheme.background)
                    .onSizeChanged { headerScroll.measured(it.height.toFloat()) }
                    // A top bar owes the status bar its own room; what the Scaffold hands the
                    // content below is this whole row, inset and all.
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(start = spacing.screen, end = spacing.screen, top = spacing.md),
            ) {
                if (search.open) {
                    SearchField(
                        query = search.query,
                        onQueryChange = viewModel::setQuery,
                        onClose = { viewModel.setSearching(false) },
                    )
                } else {
                    Header(
                        onSearch = { viewModel.setSearching(true) },
                        onDoneList = onDoneList,
                        onSettings = onSettings,
                        onDiagnostics = onDiagnostics,
                    )
                }
            }
        },
        floatingActionButton = {
            // While searching the thumb is on the keyboard, not on "New": the button would only
            // be covering a result.
            if (!search.open) {
                ExtendedFloatingActionButton(
                    // With nothing kept under a name there is no question to ask.
                    onClick = { if (asksWhichKind) choosing = true else onNew() },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.home_new), style = MaterialTheme.typography.titleMedium) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.heightIn(min = Tokens.sizes.primary),
                )
            }
        },
    ) { padding ->
        // The minute pulse for everything on this screen that is not the hero's live countdown.
        val now by rememberNow(60_000, viewModel.clock)
        val zone = viewModel.clock.zone
        val today = now.atZone(zone).toLocalDate()
        val defaultTime = state.defaultTime

        // **At the top of the list, the row is there.** It travels by what the list consumed,
        // which is what keeps it glued to the cards — and it means the list at rest at the very
        // top can consume nothing, so a row hidden while the list is up there cannot be brought
        // back by any gesture: the only way out is to scroll away and come back. Scrolling by
        // hand never gets there (the two move 1:1 and arrive together), but anything that moves
        // the list without scrolling it does — a jump to an index, which is also how the tour
        // reaches Settings. The top of the list is where the row lives, so it is put back there.
        val listState = rememberLazyListState()
        val atTop = remember {
            derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
        }
        LaunchedEffect(Unit) {
            snapshotFlow { atTop.value }.collect { if (it) headerScroll.show() }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag(HOME_LIST_TAG),
            contentPadding = PaddingValues(
                start = spacing.screen,
                end = spacing.screen,
                top = padding.calculateTopPadding() + spacing.md,
                bottom = padding.calculateBottomPadding() + Tokens.sizes.primary + spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // A fold in Settings may never hide a phone that will not ring, and neither may
            // Home: the one screen somebody actually looks at says so, once, until waved off.
            if (!search.open && stripShows(readiness, dismissedProblems)) {
                item(key = "readiness") {
                    ReadinessStrip(
                        // What is left to say, not the total: naming a problem somebody has
                        // already waved off is the strip arguing with them.
                        problems = (readiness.problemNames() - dismissedProblems).size,
                        onFix = onSettings,
                        onDismiss = { viewModel.dismissAlertStrip(readiness.problemNames()) },
                        modifier = Modifier.padding(bottom = spacing.sm),
                    )
                }
            }
            // Shown as soon as there is a shape worth a button — otherwise the "+" that adds
            // one would be hiding inside the row it is meant to fill.
            if (!search.open && presets.isNotEmpty()) {
                item(key = "pinned") {
                    PinnedPresetsRow(
                        presets = pinned,
                        onPick = { preset ->
                            // Words of its own: written on the spot. None: ask for them first.
                            if (preset.text.isBlank()) askingWordsFor = preset.id
                            else viewModel.createFromPreset(preset, null, state.defaultTime, state.dayShape)
                        },
                        onManage = { managingPins = true },
                    )
                }
            }
            if (search.open) {
                items(search.hits, key = { it.key }) { hit ->
                    SearchResultRow(
                        hit = hit,
                        onOpen = onOpen,
                        onFilterByTag = viewModel::filterByTag,
                        modifier = Modifier.animateItem(),
                    )
                }
                if (search.nothingFound) {
                    item(key = "search-empty") {
                        EmptyState(
                            title = stringResource(R.string.home_search_none_title),
                            body = stringResource(R.string.home_search_none_body),
                            icon = Icons.Outlined.SearchOff,
                        )
                    }
                }
            } else {
                // Before the first emission the list is unknown, not empty: card shapes, so the
                // screen never says "nothing to remember" about reminders it has not read yet.
                if (!state.loaded) {
                    item(key = "loading") { ListPlaceholder() }
                }
                if (state.tags.isNotEmpty()) {
                    item(key = "tags") {
                        TagFilterRow(tags = state.tags, selected = state.selectedTag, onSelect = viewModel::selectTag)
                    }
                }
                state.hero?.let { hero ->
                    // Keyed by the reminder and not by the slot: a swipe acts on release, and
                    // a hero that changed under a held thumb was a different reminder marked done.
                    val heroId = hero.card.id
                    item(key = "hero-$heroId") {
                        // Swipeable like every other card: it is a reminder, and the one that
                        // matters most is the last one that should be impossible to deal with.
                        SwipeableCard(
                            onDone = { viewModel.markDone(heroId) },
                            onDelete = { viewModel.delete(heroId) },
                            modifier = Modifier.animateItem(),
                        ) {
                            HeroCard(
                                hero = hero,
                                clock = viewModel.clock,
                                today = today,
                                defaultTime = state.defaultTime,
                                onClick = { onOpen(hero.card.id) },
                                onLongClick = { actingOn = hero.card.id },
                                longClickLabel = cardActionsLabel,
                                onTogglePause = { viewModel.togglePause(hero.card.id, hero.card.paused) },
                            )
                        }
                    }
                }
                for (section in state.sections) {
                    item(key = "section-${section.section}") {
                        SectionHeader(
                            title = stringResource(section.section.titleRes),
                            accent = if (section.section == Section.OVERDUE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            trailing = section.cards.size.toString(),
                        )
                    }
                    items(section.cards, key = { it.id }) { card ->
                        SwipeableCard(
                            onDone = { viewModel.markDone(card.id) },
                            onDelete = { viewModel.delete(card.id) },
                            modifier = Modifier.animateItem(),
                        ) {
                            ReminderCard(
                                card = card,
                                today = today,
                                defaultTime = defaultTime,
                                zone = zone,
                                onClick = { onOpen(card.id) },
                                onTogglePause = { viewModel.togglePause(card.id, card.paused) },
                                onLongClick = { actingOn = card.id },
                                longClickLabel = cardActionsLabel,
                                // What the swipes do, for whoever cannot swipe.
                                modifier = Modifier.semantics {
                                    customActions = listOf(
                                        CustomAccessibilityAction(swipeDoneLabel) { viewModel.markDone(card.id); true },
                                        CustomAccessibilityAction(swipeDeleteLabel) { viewModel.delete(card.id); true },
                                    )
                                },
                            )
                        }
                    }
                }
                if (state.empty) {
                    item(key = "empty") {
                        EmptyState(
                            title = stringResource(R.string.home_empty_title),
                            body = stringResource(R.string.home_empty_body),
                            icon = Icons.Outlined.Lightbulb,
                            actionLabel = stringResource(R.string.home_empty_action),
                            // The same door as "Nuevo": with nothing kept under a name, the form.
                            onAction = { if (asksWhichKind) choosing = true else onNew() },
                        )
                    }
                }
            }
        }
    }
}

private val Section.titleRes: Int
    get() = when (this) {
        Section.OVERDUE -> R.string.home_section_overdue
        Section.TODAY -> R.string.home_section_today
        Section.TOMORROW -> R.string.home_section_tomorrow
        Section.THIS_WEEK -> R.string.home_section_this_week
        Section.LATER -> R.string.home_section_later
        Section.WHENEVER -> R.string.home_section_whenever
        Section.NO_TRIGGER -> R.string.home_section_no_trigger
        Section.PAUSED -> R.string.home_section_paused
    }

/**
 * The name, the way into the settings, and the two lists this screen is not.
 *
 * It used to be "Hoy" over the date in mono, which is two lines saying something the phone's own
 * status bar says better and the sections below say again — "Vencidos", "Hoy", "Mañana" are
 * where the day actually lives. So the corner says whose screen this is instead, and the cog
 * that used to be the fourth of four icons in the far corner comes and stands next to it.
 *
 * **The name and the cog are one control**, not a label beside a button: there is nothing else
 * this corner has ever done, so anything a thumb lands on here means "ajustes" — a target the
 * width of the wordmark rather than 48 square points. The cog is drawn larger than the ordinary
 * glyph ([Sizes.cog]) so it reads as the way in rather than as one more thing in a row.
 */
@Composable
private fun Header(
    onSearch: () -> Unit,
    onDoneList: () -> Unit,
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val locale = currentLocale()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button, onClick = onSettings)
                .heightIn(min = Tokens.sizes.touch)
                .padding(end = Tokens.spacing.sm),
        ) {
            Text(
                // The launcher's own name, said the way a wordmark is said. Uppercased here
                // rather than stored twice, so there is one place the app is called anything.
                text = stringResource(R.string.app_name).uppercase(locale),
                style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 1.sp),
            )
            Spacer(Modifier.width(Tokens.spacing.sm))
            Icon(
                imageVector = Icons.Outlined.Settings,
                // The row merges its children, so this is what names the whole control: a
                // reader hears "RWILCO, ajustes, botón", which is the truth about it.
                contentDescription = stringResource(R.string.home_settings),
                modifier = Modifier.size(Tokens.sizes.cog),
            )
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Only while the encrypted copy has something waiting; see BackupBadge.
            BackupBadge()
            IconButton(onClick = onSearch) {
                Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.home_search))
            }
            IconButton(onClick = onDoneList) {
                Icon(Icons.Outlined.History, contentDescription = stringResource(R.string.home_done_list))
            }
            // One tap, and the report is on the clipboard. It has always been three screens
            // deep in the settings, which is the wrong depth for the thing somebody reaches for
            // at the exact moment the app has just done something inexplicable — by the time
            // they have found it, the log has moved on.
            IconButton(onClick = onDiagnostics) {
                // The same size as the two beside it: it is a way out of trouble, not a
                // feature, and it should sit quietly until somebody needs it.
                Icon(Icons.Outlined.BugReport, contentDescription = stringResource(R.string.home_diagnostics))
            }
        }
    }
}

/**
 * The row of chips: "todas", then the tags in their own colours, then the app's own two —
 * "sin etiqueta" and "en pausa" — neutral, at the end, and only while they have anything in
 * them. Housekeeping belongs after the filing, not in the middle of it.
 */
@Composable
private fun TagFilterRow(tags: List<TagFilter>, selected: TagFilter?, onSelect: (TagFilter?) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item(key = "all") {
            TagChip(label = stringResource(R.string.home_all_tags), selected = selected == null, onClick = { onSelect(null) })
        }
        items(tags, key = { it.key }) { tag ->
            TagChip(
                label = tag.label(),
                selected = tag == selected,
                onClick = { onSelect(tag) },
                // The places wear the family's own colour and its pin, and no word at all: a
                // pin is what a place looks like everywhere else in this app, and the hue is one
                // no tag can have (the tag circle has the three family hues cut out of it), so
                // it cannot be read as somebody's word for something.
                tint = when (tag) {
                    is TagFilter.Named -> tagColor(tag.tag)
                    TagFilter.Place -> familyColor(TriggerFamily.PLACE, LocalDarkTheme.current)
                    else -> null
                },
                icon = Icons.Outlined.Place.takeIf { tag == TagFilter.Place },
            )
        }
    }
}

/** Stable across a rename of nothing: a tag is its own key, and the app's two are constants. */
private val TagFilter.key: String
    get() = when (this) {
        is TagFilter.Named -> "tag-$tag"
        TagFilter.Untagged -> "rwilco-untagged"
        TagFilter.Paused -> "rwilco-paused"
        TagFilter.Place -> "rwilco-place"
    }

@Composable
private fun TagFilter.label(): String = when (this) {
    is TagFilter.Named -> tag
    TagFilter.Untagged -> stringResource(R.string.home_tag_untagged)
    TagFilter.Paused -> stringResource(R.string.home_tag_paused)
    TagFilter.Place -> stringResource(R.string.home_tag_place)
}
