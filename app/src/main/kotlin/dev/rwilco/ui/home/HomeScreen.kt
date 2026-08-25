package dev.rwilco.ui.home

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rwilco.R
import dev.rwilco.model.Section
import dev.rwilco.ui.components.EmptyState
import dev.rwilco.ui.components.LocalSnackbar
import dev.rwilco.ui.components.SectionHeader
import dev.rwilco.ui.components.TagChip
import dev.rwilco.ui.components.rememberNow
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNew: () -> Unit,
    onNewFromPreset: (String) -> Unit,
    onEditPreset: (String) -> Unit,
    onNewPreset: () -> Unit,
    onOpen: (String) -> Unit,
    onDoneList: () -> Unit,
    onSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val pinned by viewModel.pinnedPresets.collectAsStateWithLifecycle()
    var choosing by rememberSaveable { mutableStateOf(false) }
    var managingPins by rememberSaveable { mutableStateOf(false) }
    // The preset whose words are being asked for before it can be written.
    var askingWordsFor by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = LocalSnackbar.current
    val doneMessage = stringResource(R.string.home_marked_done)
    val deletedMessage = stringResource(R.string.home_deleted)
    val refreshedMessage = stringResource(R.string.home_refreshed)
    val undoLabel = stringResource(R.string.common_undo)
    val swipeDoneLabel = stringResource(R.string.card_swipe_done)
    val swipeDeleteLabel = stringResource(R.string.card_swipe_delete)
    val createdMessage = stringResource(R.string.home_created)

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is HomeEvent.Removed -> snackbar.show(
                    message = if (event.kind == HomeEvent.Removed.Kind.DONE) doneMessage else deletedMessage,
                    undoLabel = undoLabel,
                    onUndo = { viewModel.undo(event) },
                )
                HomeEvent.Refreshed -> snackbar.show(refreshedMessage)
                is HomeEvent.Created -> snackbar.show(
                    message = createdMessage,
                    undoLabel = undoLabel,
                    onUndo = { viewModel.undoCreated(event.reminder) },
                )
                // Something it carries has already passed: the form, not a silent overdue.
                is HomeEvent.NeedsEditor -> onNewFromPreset(event.presetId)
            }
        }
    }

    BackHandler(enabled = search.open) { viewModel.setSearching(false) }

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
                onConfirm = { words ->
                    askingWordsFor = null
                    viewModel.createFromPreset(preset, words, state.defaultTime)
                },
                onDismiss = { askingWordsFor = null },
            )
        }
    }

    val spacing = Tokens.spacing
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        floatingActionButton = {
            // While searching the thumb is on the keyboard, not on "New": the button would only
            // be covering a result.
            if (!search.open) {
                ExtendedFloatingActionButton(
                    // With nothing kept under a name there is no question to ask.
                    onClick = { if (presets.isEmpty()) onNew() else choosing = true },
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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.screen,
                end = spacing.screen,
                top = padding.calculateTopPadding() + spacing.md,
                bottom = padding.calculateBottomPadding() + Tokens.sizes.primary + spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item(key = "header") {
                if (search.open) {
                    SearchField(
                        query = search.query,
                        onQueryChange = viewModel::setQuery,
                        onClose = { viewModel.setSearching(false) },
                    )
                } else {
                    Header(
                        today = today,
                        onSearch = { viewModel.setSearching(true) },
                        onRefresh = viewModel::refresh,
                        onDoneList = onDoneList,
                        onSettings = onSettings,
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
                            else viewModel.createFromPreset(preset, null, state.defaultTime)
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
                if (state.tags.isNotEmpty()) {
                    item(key = "tags") {
                        TagFilterRow(tags = state.tags, selected = state.selectedTag, onSelect = viewModel::selectTag)
                    }
                }
                state.hero?.let { hero ->
                    item(key = "hero") {
                        // Swipeable like every other card: it is a reminder, and the one that
                        // matters most is the last one that should be impossible to deal with.
                        SwipeableCard(
                            onDone = { viewModel.markDone(hero.card.id) },
                            onDelete = { viewModel.delete(hero.card.id) },
                            modifier = Modifier.animateItem(),
                        ) {
                            HeroCard(
                                hero = hero,
                                clock = viewModel.clock,
                                today = today,
                                onClick = { onOpen(hero.card.id) },
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
                                onClick = { onOpen(card.id) },
                                onTogglePause = { viewModel.togglePause(card.id, card.paused) },
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

@Composable
private fun Header(
    today: java.time.LocalDate,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    onDoneList: () -> Unit,
    onSettings: () -> Unit,
) {
    val locale = currentLocale()
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            // The app's own name was a line of decoration on the app's own screen; the row it
            // took is worth more as somewhere to put the shapes you reach for.
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(Tokens.spacing.xs))
            Text(
                text = TimeText.dateLong(today, locale),
                style = MonoStyles.date,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row {
            IconButton(onClick = onSearch) {
                Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.home_search))
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.home_refresh))
            }
            IconButton(onClick = onDoneList) {
                Icon(Icons.Outlined.History, contentDescription = stringResource(R.string.home_done_list))
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.home_settings))
            }
        }
    }
}

@Composable
private fun TagFilterRow(tags: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item(key = "all") {
            TagChip(label = stringResource(R.string.home_all_tags), selected = selected == null, onClick = { onSelect(null) })
        }
        items(tags, key = { it }) { tag ->
            TagChip(label = tag, selected = tag.equals(selected, ignoreCase = true), onClick = { onSelect(tag) })
        }
    }
}
