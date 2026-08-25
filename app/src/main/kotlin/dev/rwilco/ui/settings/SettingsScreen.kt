package dev.rwilco.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rwilco.R
import dev.rwilco.model.SavedPlace
import dev.rwilco.model.ThemeMode
import dev.rwilco.model.Transition
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerKind
import dev.rwilco.ui.components.DayToggles
import dev.rwilco.ui.components.InfoBadge
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.SectionHeader
import dev.rwilco.ui.components.TagChip
import dev.rwilco.ui.components.TimeField
import dev.rwilco.ui.editor.ActionsSection
import dev.rwilco.ui.editor.sheets.LocationSheet
import dev.rwilco.ui.editor.titleRes
import dev.rwilco.ui.theme.Tokens

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing
    val haptics = Tokens.haptics

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
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = spacing.sm),
                    )
                }
            }
        },
    ) { padding ->
        val current = settings ?: return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen)
                .padding(bottom = spacing.xxl),
        ) {
            // Appearance is a thing you set once and never look at again, so it takes a row
            // of three small icons rather than a card and a full-width control.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = spacing.lg)) {
                Text(
                    text = stringResource(R.string.settings_appearance),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                ThemeChoice(current.theme, viewModel::setTheme)
            }

            SectionHeader(stringResource(R.string.settings_reminders))
            RwilcoCard {
                Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
                    Column {
                        SettingTitle(stringResource(R.string.settings_default_time))
                        Spacer(Modifier.height(spacing.sm))
                        TimeField(time = current.defaultTime, onChange = viewModel::setDefaultTime, modifier = Modifier.fillMaxWidth())
                    }
                    Column {
                        SettingTitle(
                            title = stringResource(R.string.settings_default_trigger),
                            info = stringResource(R.string.settings_default_trigger_hint),
                        )
                        Spacer(Modifier.height(spacing.sm))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            TagChip(
                                label = stringResource(R.string.settings_default_trigger_ask),
                                selected = current.defaultTriggerKind == null,
                                onClick = { viewModel.setDefaultTriggerKind(null) },
                            )
                            for (kind in TriggerKind.entries) {
                                TagChip(
                                    label = stringResource(kind.titleRes),
                                    selected = current.defaultTriggerKind == kind,
                                    // Tapping the chosen one again is how you go back to no favourite.
                                    onClick = { viewModel.setDefaultTriggerKind(if (current.defaultTriggerKind == kind) null else kind) },
                                )
                            }
                        }
                    }
                    Column {
                        SettingTitle(
                            title = stringResource(R.string.settings_default_actions),
                            info = stringResource(R.string.settings_default_actions_hint),
                        )
                        Spacer(Modifier.height(spacing.sm))
                        // The same four tiles the editor uses: what is set here is what a blank
                        // reminder opens with.
                        ActionsSection(selected = current.defaultActions, onToggle = viewModel::toggleDefaultAction)
                    }
                    Column {
                        SettingTitle(
                            title = stringResource(R.string.settings_day_start),
                            info = stringResource(R.string.settings_day_start_hint),
                        )
                        Spacer(Modifier.height(spacing.sm))
                        TimeField(time = current.dayStart, onChange = viewModel::setDayStart, modifier = Modifier.fillMaxWidth())
                    }
                    Column {
                        SettingTitle(
                            title = stringResource(R.string.settings_weekend),
                            info = stringResource(R.string.settings_weekend_hint),
                        )
                        Spacer(Modifier.height(spacing.sm))
                        // One day, not a set: "el finde" is a moment to push something to.
                        DayToggles(
                            selected = setOf(current.weekendDay),
                            onToggle = { day -> viewModel.setWeekend(day, current.weekendTime) },
                        )
                        Spacer(Modifier.height(spacing.sm))
                        TimeField(
                            time = current.weekendTime,
                            onChange = { time -> viewModel.setWeekend(current.weekendDay, time) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingTitle(
                            title = stringResource(R.string.settings_haptics),
                            info = stringResource(R.string.settings_haptics_hint),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(spacing.md))
                        Switch(
                            checked = current.haptics,
                            onCheckedChange = { enabled ->
                                if (enabled) haptics.perform(HapticFeedbackType.ToggleOn)
                                viewModel.setHaptics(enabled)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.surface,
                                checkedTrackColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
            }

            SectionHeader(stringResource(R.string.settings_alerts))
            AlertPermissionsCard()

            SectionHeader(
                title = stringResource(R.string.settings_location),
                info = stringResource(R.string.perm_location_why) + "\n\n" + stringResource(R.string.place_watch_how),
            )
            val hasPlaces by viewModel.hasPlaceReminders.collectAsStateWithLifecycle()
            val watch by viewModel.placeWatch.collectAsStateWithLifecycle()
            LocationPermissionCard(needsPlaces = hasPlaces, watch = watch)

            SectionHeader(
                title = stringResource(R.string.settings_places),
                info = stringResource(R.string.settings_places_hint),
            )
            // Which saved place the sheet is open on: null closed, -1 a new one, else the index.
            var editingPlace by rememberSaveable { mutableStateOf<Int?>(null) }
            SavedPlacesCard(
                places = current.savedPlaces,
                onAdd = { editingPlace = -1 },
                onEdit = { editingPlace = it },
                onRemove = viewModel::removePlace,
            )
            editingPlace?.let { index ->
                val existing = current.savedPlaces.getOrNull(index)
                LocationSheet(
                    initial = existing?.let { Trigger.Location(it.lat, it.lng, it.radiusM, Transition.ENTER, it.label) },
                    title = stringResource(R.string.place_saved_title),
                    pickTransition = false,
                    onConfirm = { place ->
                        viewModel.savePlace(index.takeIf { it >= 0 }, SavedPlace(place.label, place.lat, place.lng, place.radiusM))
                        editingPlace = null
                    },
                    onDismiss = { editingPlace = null },
                )
            }

            SectionHeader(stringResource(R.string.settings_updates))
            AppUpdateCard()

            SectionHeader(
                title = stringResource(R.string.settings_about),
                info = stringResource(R.string.settings_about_body) + "\n\n" + stringResource(R.string.settings_licenses),
            )
        }
    }
}

/** A setting's name, with its explanation folded behind an (i) when it has one. */
@Composable
private fun SettingTitle(title: String, modifier: Modifier = Modifier, info: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (info != null) InfoBadge(info)
    }
}

/** Three small icons: follow the phone, always light, always dark. */
@Composable
private fun ThemeChoice(theme: ThemeMode, onChange: (ThemeMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.xs)) {
        ThemeButton(ThemeMode.SYSTEM, Icons.Outlined.SettingsBrightness, R.string.settings_theme_system, theme, onChange)
        ThemeButton(ThemeMode.LIGHT, Icons.Outlined.LightMode, R.string.settings_theme_light, theme, onChange)
        ThemeButton(ThemeMode.DARK, Icons.Outlined.DarkMode, R.string.settings_theme_dark, theme, onChange)
    }
}

@Composable
private fun ThemeButton(
    mode: ThemeMode,
    icon: ImageVector,
    labelRes: Int,
    current: ThemeMode,
    onChange: (ThemeMode) -> Unit,
) {
    val haptics = Tokens.haptics
    val scheme = MaterialTheme.colorScheme
    val selected = current == mode
    Surface(
        onClick = {
            haptics.perform(HapticFeedbackType.SegmentTick)
            onChange(mode)
        },
        shape = CircleShape,
        // On is inverted, like every other "on" in the app.
        color = if (selected) scheme.onSurface else scheme.surfaceContainerHigh,
        border = if (selected) null else BorderStroke(Tokens.strokes.control, scheme.outline),
        modifier = Modifier.size(Tokens.sizes.touch),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = stringResource(labelRes),
                tint = if (selected) scheme.surface else scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
