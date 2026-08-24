package dev.rwilco.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rwilco.R
import dev.rwilco.model.ThemeMode
import dev.rwilco.model.TriggerKind
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.SectionHeader
import dev.rwilco.ui.components.DayToggles
import dev.rwilco.ui.components.SegmentedChoice
import dev.rwilco.ui.components.TagChip
import dev.rwilco.ui.components.TimeField
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
            SectionHeader(stringResource(R.string.settings_appearance))
            RwilcoCard {
                Column(Modifier.padding(spacing.lg)) {
                    SegmentedChoice(
                        options = listOf(
                            stringResource(R.string.settings_theme_system),
                            stringResource(R.string.settings_theme_light),
                            stringResource(R.string.settings_theme_dark),
                        ),
                        selectedIndex = current.theme.ordinal,
                        onSelect = { viewModel.setTheme(ThemeMode.entries[it]) },
                    )
                }
            }

            SectionHeader(stringResource(R.string.settings_reminders))
            RwilcoCard {
                Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
                    Column {
                        Text(stringResource(R.string.settings_default_time), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(spacing.sm))
                        TimeField(time = current.defaultTime, onChange = viewModel::setDefaultTime, modifier = Modifier.fillMaxWidth())
                    }
                    Column {
                        Text(stringResource(R.string.settings_default_trigger), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.settings_default_trigger_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        Text(stringResource(R.string.settings_weekend), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.settings_weekend_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_haptics), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.settings_haptics_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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

            SectionHeader(stringResource(R.string.settings_location))
            val hasPlaces by viewModel.hasPlaceReminders.collectAsStateWithLifecycle()
            LocationPermissionCard(needsPlaces = hasPlaces)

            SectionHeader(stringResource(R.string.settings_updates))
            AppUpdateCard()

            SectionHeader(stringResource(R.string.settings_about))
            RwilcoCard {
                Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(stringResource(R.string.settings_about_body), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.settings_licenses),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
