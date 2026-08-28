package dev.rwilco.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.History
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import dev.rwilco.R
import dev.rwilco.model.Section
import dev.rwilco.model.TagFilter
import dev.rwilco.ui.components.EmptyState
import dev.rwilco.ui.components.LocalSnackbar
import dev.rwilco.ui.components.SectionHeader
import dev.rwilco.ui.components.TagChip
import dev.rwilco.ui.components.rememberNow
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.tagColor

/** So a test can scroll the list itself; a lazy list does not compose what is off screen. */
const val HOME_LIST_TAG = "homeList"

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNew: () -> Unit,
    onNewFromPreset: (String) -> Unit,
    onEditPreset: (String) -> Unit,
    onNewPreset: () -> Unit,
    onOpen: (String) -> Unit,
    /** A new reminder shaped like this one, waiting for its own words. */
    onClone: (String) -> Unit,
    onDoneList: () -> Unit,
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val pinned by viewModel.pinnedPresets.collectAsStateWithLifecycle()
    var choosing by rememberSaveable { mutableStateOf(false) }
    var managingPins by rememberSaveable { mutableStateOf(false) }
    // The preset whose words are being asked for before it can be written.
    var askingWordsFor by rememberSaveable { mutableStateOf<String?>(null) }
    // The card being held, and so the one the actions menu is about.
    var actingOn by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = LocalSnackbar.current
    val doneMessage = stringResource(R.string.home_marked_done)
    val deletedMessage = stringResource(R.string.home_deleted)
    val undoLabel = stringResource(R.string.common_undo)
    val cardActionsLabel = stringResource(R.string.home_card_actions)
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
                is HomeEvent.Created -> snackbar.show(
                    message = createdMessage,
                    undoLabel = undoLabel,
                    onUndo = { viewModel.undoCreated(event.reminder, event.preset) },
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
                onClone = {
                    actingOn = null
                    onClone(held.id)
                },
                onDismiss = { actingOn = null },
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
            item(key = "header") {
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
                                defaultTime = state.defaultTime,
                                onClick = { onOpen(hero.card.id) },
                                onLongClick = { actingOn = hero.card.id },
                                longClickLabel = cardActionsLabel,
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
                tint = (tag as? TagFilter.Named)?.let { tagColor(it.tag) },
                // The one chip with a glyph, and the same glyph its mark wears on a card: the
                // row is read by shape before it is read by word, and that is the shape.
                leadingIcon = Icons.Outlined.HealthAndSafety.takeIf { tag == TagFilter.SafetyNet },
                leadingIconTint = MaterialTheme.colorScheme.error.takeIf { tag == TagFilter.SafetyNet },
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
        TagFilter.SafetyNet -> "rwilco-safety-net"
    }

@Composable
private fun TagFilter.label(): String = when (this) {
    is TagFilter.Named -> tag
    TagFilter.Untagged -> stringResource(R.string.home_tag_untagged)
    TagFilter.Paused -> stringResource(R.string.home_tag_paused)
    TagFilter.SafetyNet -> stringResource(R.string.home_tag_safety_net)
}
