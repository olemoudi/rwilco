package dev.rwilco.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AddAlert
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rwilco.BuildConfig
import dev.rwilco.R
import dev.rwilco.model.AlertSound
import dev.rwilco.model.AlertStacking
import dev.rwilco.model.AppSettings
import dev.rwilco.model.OFFERED_KINDS
import dev.rwilco.model.Presence
import dev.rwilco.model.SavedPlace
import dev.rwilco.model.ThemeMode
import dev.rwilco.model.Trigger
import dev.rwilco.model.VibrationRhythm
import dev.rwilco.model.VibrationStrength
import dev.rwilco.ui.components.DayToggles
import dev.rwilco.ui.components.InfoBadge
import dev.rwilco.ui.components.PermissionFixRow
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.SegmentedChoice
import dev.rwilco.ui.components.TagChip
import dev.rwilco.ui.components.TimeField
import dev.rwilco.ui.editor.ActionsSection
import dev.rwilco.ui.editor.sheets.LocationSheet
import dev.rwilco.ui.editor.titleRes
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.format.rememberIs24h
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.components.LocalSnackbar
import androidx.compose.material.icons.outlined.Language
import android.os.Build
import android.net.Uri

/**
 * The groups the screen folds into, in the order they are worth a look: whether a reminder
 * arrives at all, then what it sounds and feels like, then what a new one starts as, then the
 * shape of the day, then the standing things — places, looks, the copy, updates, the app.
 */
private enum class Group { ALERTS, SOUND, VIBRATION, NET, NEW, DAY, PLACES, LOOK, UPDATES, ABOUT }

/** Which groups are open, kept across a rotation. Enums are not Bundle-able; their names are. */
private val OpenGroups = listSaver<Set<Group>, String>(
    save = { open -> open.map(Group::name) },
    restore = { names -> names.map(Group::valueOf).toSet() },
)

/**
 * Everything about the app, folded.
 *
 * Thirty controls in one scroll is a screen nobody reads. So it is an index of ten rows, each
 * carrying its own current value, and each opening where it stands rather than pushing the
 * person to another screen and back. The rows are independent: closing one because another
 * opened would move a header out from under the thumb that just tapped it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onWatchLog: () -> Unit, onBackup: () -> Unit, onDiagnostics: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current

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
        val alerts = rememberAlertReadiness()
        val places = rememberPlaceReadiness()
        val hasPlaces by viewModel.hasPlaceReminders.collectAsStateWithLifecycle()

        var open by rememberSaveable(stateSaver = OpenGroups) { mutableStateOf(emptySet<Group>()) }
        val toggle: (Group) -> Unit = { group -> open = if (group in open) open - group else open + group }

        // A phone that will not let a reminder through is the one thing a fold must not hide.
        // The groups that are in trouble open themselves, once, and then it is the person's call.
        val broken = buildSet {
            if (!alerts.allGood) add(Group.ALERTS)
            if (hasPlaces && !places.ready) add(Group.PLACES)
        }
        var announced by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(broken) {
            if (!announced && broken.isNotEmpty()) {
                open = open + broken
                announced = true
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen)
                .padding(bottom = spacing.xxl),
        ) {
            SettingsGroup(
                icon = Icons.Outlined.NotificationsActive,
                title = stringResource(R.string.settings_alerts),
                summary = if (alerts.allGood) {
                    stringResource(R.string.settings_summary_ready)
                } else {
                    pluralStringResource(R.plurals.settings_summary_broken, alerts.problems, alerts.problems)
                },
                attention = !alerts.allGood,
                expanded = Group.ALERTS in open,
                onToggle = { toggle(Group.ALERTS) },
            ) {
                AlertPermissionsCard(alerts)
                // Two reminders ringing within moments of each other, and what the screen does
                // with the second one. A choice rather than a rule, because both readings are
                // right for somebody: one thing at a time, or everything owed an answer at once.
                RwilcoCard {
                    Column(Modifier.padding(spacing.lg)) {
                        SettingTitle(
                            title = stringResource(R.string.settings_alert_stacking),
                            info = stringResource(R.string.settings_alert_stacking_hint),
                        )
                        Spacer(Modifier.height(spacing.sm))
                        SegmentedChoice(
                            options = listOf(
                                stringResource(R.string.settings_stacking_sequential),
                                stringResource(R.string.settings_stacking_strips),
                            ),
                            selectedIndex = AlertStacking.entries.indexOf(current.alertStacking),
                            onSelect = { viewModel.setAlertStacking(AlertStacking.entries[it]) },
                        )
                    }
                }
                SnoozeCard(
                    settings = current,
                    onCustomMinutes = viewModel::setSnoozeCustomMinutes,
                    onPick = viewModel::pickNotificationSnooze,
                )
                // The proof: a real alert in ten seconds, through everything the rows above
                // are about. The tone and the buzz have previews of their own further down;
                // this is the whole thing, lock screen included.
                val testAlertText = stringResource(R.string.settings_test_alert_text)
                val testAlertStarted = stringResource(R.string.settings_test_alert_started)
                TestAlertCard(
                    onTest = { actions ->
                        viewModel.testAlert(testAlertText, actions)
                        snackbar.show(testAlertStarted)
                    },
                )
            }

            val insistent by viewModel.insistentInUse.collectAsStateWithLifecycle()
            SettingsGroup(
                icon = Icons.AutoMirrored.Outlined.VolumeUp,
                title = stringResource(R.string.settings_sound_title),
                summary = soundName(current.alertSound),
                expanded = Group.SOUND in open,
                onToggle = { toggle(Group.SOUND) },
            ) {
                SoundCard(
                    sound = current.alertSound,
                    insistentSound = current.insistentSound,
                    plays = current.soundPlays,
                    gapMinutes = current.soundGapMinutes,
                    insistentInUse = insistent,
                    toHeadphones = current.alertToHeadphones,
                    onSound = viewModel::setAlertSound,
                    onInsistentSound = viewModel::setInsistentSound,
                    onPlays = viewModel::setSoundPlays,
                    onGap = viewModel::setSoundGap,
                    onToHeadphones = viewModel::setAlertToHeadphones,
                )
            }

            SettingsGroup(
                icon = Icons.Outlined.HealthAndSafety,
                title = stringResource(R.string.settings_net_title),
                summary = join(
                    stringResource(R.string.settings_net_after_value, current.safetyNet.afterHours),
                    stringResource(R.string.settings_net_fraction_value, current.safetyNet.fraction),
                ),
                expanded = Group.NET in open,
                onToggle = { toggle(Group.NET) },
            ) {
                SafetyNetCard(settings = current.safetyNet, onChange = viewModel::setSafetyNet)
            }

            SettingsGroup(
                icon = Icons.Outlined.Vibration,
                title = stringResource(R.string.settings_vibration_strength),
                summary = join(
                    stringResource(
                        if (current.vibration.strength == VibrationStrength.STRONG) {
                            R.string.settings_vibration_strong
                        } else {
                            R.string.settings_vibration_gentle
                        },
                    ),
                    stringResource(
                        if (current.vibration.rhythm == VibrationRhythm.PULSED) {
                            R.string.settings_vibration_pulsed
                        } else {
                            R.string.settings_vibration_continuous
                        },
                    ),
                ),
                expanded = Group.VIBRATION in open,
                onToggle = { toggle(Group.VIBRATION) },
            ) {
                VibrationCard(pattern = current.vibration, onChange = viewModel::setVibration)
            }

            SettingsGroup(
                icon = Icons.Outlined.AddAlert,
                title = stringResource(R.string.settings_group_new),
                summary = join(
                    TimeText.time(current.defaultTime, rememberIs24h(), currentLocale()),
                    favouriteTriggerName(current),
                ),
                expanded = Group.NEW in open,
                onToggle = { toggle(Group.NEW) },
            ) {
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
                                    selected = current.defaultTriggerKind == null && !current.popularTriggersFirst,
                                    onClick = { viewModel.setDefaultTriggerKind(null) },
                                )
                                // A favourite nobody has to keep choosing: the tiles sort
                                // themselves by what this person actually uses.
                                TagChip(
                                    label = stringResource(R.string.settings_default_trigger_popular),
                                    selected = current.popularTriggersFirst,
                                    onClick = { viewModel.setPopularTriggersFirst(!current.popularTriggersFirst) },
                                )
                                for (kind in OFFERED_KINDS) {
                                    TagChip(
                                        label = stringResource(kind.titleRes),
                                        selected = !current.popularTriggersFirst && current.defaultTriggerKind == kind,
                                        // Tapping the chosen one again is how you go back to no favourite.
                                        onClick = {
                                            viewModel.setDefaultTriggerKind(
                                                if (current.defaultTriggerKind == kind && !current.popularTriggersFirst) null else kind,
                                            )
                                        },
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
                            // The same four tiles the editor uses: what is set here is what a
                            // blank reminder opens with.
                            ActionsSection(selected = current.defaultActions, onToggle = viewModel::toggleDefaultAction)
                        }
                    }
                }
            }

            SettingsGroup(
                icon = Icons.Outlined.Schedule,
                title = stringResource(R.string.settings_group_day),
                summary = TimeText.window(current.awake.wake, current.awake.sleep, rememberIs24h(), currentLocale()) +
                    if (current.savedWindows.isEmpty()) {
                        ""
                    } else {
                        " · " + pluralStringResource(R.plurals.settings_summary_windows, current.savedWindows.size, current.savedWindows.size)
                    },
                expanded = Group.DAY in open,
                onToggle = { toggle(Group.DAY) },
            ) {
                RwilcoCard {
                    Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
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
                        Column {
                            SettingTitle(
                                title = stringResource(R.string.settings_weekend_end),
                                info = stringResource(R.string.settings_weekend_end_hint),
                            )
                            Spacer(Modifier.height(spacing.sm))
                            DayToggles(
                                selected = setOf(current.weekendEndDay),
                                onToggle = { day -> viewModel.setWeekendEnd(day, current.weekendEndTime) },
                            )
                            Spacer(Modifier.height(spacing.sm))
                            TimeField(
                                time = current.weekendEndTime,
                                onChange = { time -> viewModel.setWeekendEnd(current.weekendEndDay, time) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Column {
                            SettingTitle(
                                title = stringResource(R.string.settings_awake),
                                info = stringResource(R.string.settings_awake_hint),
                            )
                            Spacer(Modifier.height(spacing.sm))
                            // Two pairs, labelled: the whole point of them is that they differ,
                            // and which of the two a given day gets is decided by the two
                            // settings above.
                            AwakePair(
                                label = stringResource(R.string.settings_awake_weekday),
                                wake = current.awake.wake,
                                sleep = current.awake.sleep,
                                onWake = { viewModel.setAwake(current.awake.copy(wake = it)) },
                                onSleep = { viewModel.setAwake(current.awake.copy(sleep = it)) },
                            )
                            Spacer(Modifier.height(spacing.md))
                            AwakePair(
                                label = stringResource(R.string.settings_awake_weekend),
                                wake = current.awake.weekendWake,
                                sleep = current.awake.weekendSleep,
                                onWake = { viewModel.setAwake(current.awake.copy(weekendWake = it)) },
                                onSleep = { viewModel.setAwake(current.awake.copy(weekendSleep = it)) },
                            )
                        }
                        // The stretches of that day worth a name of their own. Here rather than
                        // in a card of its own because this group IS the shape of the day: when
                        // you get up, when the weekend is, and which bits of it you call things.
                        Column {
                            SettingTitle(title = stringResource(R.string.settings_windows))
                            Spacer(Modifier.height(spacing.sm))
                            SavedWindowsCard(
                                windows = current.savedWindows,
                                onSave = viewModel::saveWindow,
                                onRemove = viewModel::removeWindow,
                                onRestore = viewModel::restoreWindow,
                            )
                        }
                    }
                }
            }

            // The permission, the places and the watch are one subject: everything the app does
            // with where you are. They were two sections saying the same thing twice.
            val watch by viewModel.placeWatch.collectAsStateWithLifecycle()
            var editingPlace by rememberSaveable { mutableStateOf<Int?>(null) }
            SettingsGroup(
                icon = Icons.Outlined.Place,
                title = stringResource(R.string.settings_places),
                summary = when {
                    hasPlaces && !places.ready -> stringResource(R.string.settings_summary_location_missing)
                    current.savedPlaces.isEmpty() -> stringResource(R.string.settings_summary_no_places)
                    else -> pluralStringResource(
                        R.plurals.settings_summary_places,
                        current.savedPlaces.size,
                        current.savedPlaces.size,
                    )
                },
                attention = hasPlaces && !places.ready,
                expanded = Group.PLACES in open,
                onToggle = { toggle(Group.PLACES) },
            ) {
                LocationPermissionCard(readiness = places, needsPlaces = hasPlaces, watch = watch)
                SavedPlacesCard(
                    places = current.savedPlaces,
                    onAdd = { editingPlace = -1 },
                    onEdit = { editingPlace = it },
                    onRemove = viewModel::removePlace,
                    onRestore = viewModel::restorePlace,
                )
                RwilcoCard {
                    Column(Modifier.padding(spacing.lg)) {
                        SettingSwitchRow(
                            title = stringResource(R.string.watch_notice_title),
                            info = stringResource(R.string.watch_notice_body),
                            checked = current.busyWatchNotice,
                            onCheckedChange = viewModel::setBusyWatchNotice,
                        )
                    }
                }
                // The whole account of what the watch has been doing, which is a place to go and
                // look rather than anything to put on this screen.
                SettingsLinkRow(title = stringResource(R.string.watch_log_open), onClick = onWatchLog)
            }
            // Which saved place the sheet is open on: null closed, -1 a new one, else the index.
            editingPlace?.let { index ->
                val existing = current.savedPlaces.getOrNull(index)
                LocationSheet(
                    initial = existing?.let { Trigger.Location(it.lat, it.lng, it.radiusM, Presence.INSIDE, it.label) },
                    title = stringResource(R.string.place_saved_title),
                    pickTransition = false,
                    onConfirm = { place ->
                        viewModel.savePlace(index.takeIf { it >= 0 }, SavedPlace(place.label, place.lat, place.lng, place.radiusM))
                        editingPlace = null
                    },
                    onDismiss = { editingPlace = null },
                )
            }

            SettingsGroup(
                icon = Icons.Outlined.Palette,
                title = stringResource(R.string.settings_group_look),
                summary = stringResource(
                    when (current.theme) {
                        ThemeMode.SYSTEM -> R.string.settings_theme_system
                        ThemeMode.LIGHT -> R.string.settings_theme_light
                        ThemeMode.DARK -> R.string.settings_theme_dark
                    },
                ),
                expanded = Group.LOOK in open,
                onToggle = { toggle(Group.LOOK) },
            ) {
                RwilcoCard {
                    Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.settings_appearance),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            ThemeChoice(current.theme, viewModel::setTheme)
                        }
                        // The interface's own tick, which is not a reminder's vibration and
                        // was filed under "reminders" for far too long.
                        SettingSwitchRow(
                            title = stringResource(R.string.settings_haptics),
                            info = stringResource(R.string.settings_haptics_hint),
                            checked = current.haptics,
                            onCheckedChange = viewModel::setHaptics,
                        )
                        // **This switch can only ever turn the tick OFF.** Android gates every
                        // app's touch feedback behind its own "vibración al tocar", and there
                        // is no asking it nicely: with that off, this one is a switch somebody
                        // turns on and nothing happens — which is a worse thing to ship than
                        // the missing tick. So it says so, in the same red row and with the
                        // same one button that every other silently-failing state on this
                        // screen gets (see AlertReadiness).
                        if (current.haptics && !systemHapticsOn(context)) {
                            PermissionFixRow(
                                text = stringResource(R.string.settings_haptics_system_off),
                                action = stringResource(R.string.settings_haptics_system_fix),
                            ) {
                                runCatching { context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }
                            }
                        }
                    }
                }
                // The per-app language is the system's to keep (locales_config.xml says which
                // two), and the system's page is the one place to change it — it only exists
                // from API 33, and below that the phone's own language is the whole answer.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val locale = currentLocale()
                    SettingsLinkRow(
                        title = stringResource(R.string.settings_language),
                        summary = locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) },
                        icon = Icons.Outlined.Language,
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:${context.packageName}")))
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(spacing.md))
            BackupCard(onOpen = onBackup)

            SettingsGroup(
                icon = Icons.Outlined.SystemUpdate,
                title = stringResource(R.string.settings_updates),
                summary = stringResource(
                    if (current.updatesWifiOnly) R.string.settings_summary_wifi_only else R.string.settings_summary_any_network,
                ),
                expanded = Group.UPDATES in open,
                onToggle = { toggle(Group.UPDATES) },
            ) {
                AppUpdateCard()
                RwilcoCard {
                    Column(Modifier.padding(spacing.lg)) {
                        SettingSwitchRow(
                            title = stringResource(R.string.settings_updates_wifi),
                            info = stringResource(R.string.settings_updates_wifi_hint),
                            checked = current.updatesWifiOnly,
                            onCheckedChange = viewModel::setUpdatesWifiOnly,
                        )
                    }
                }
            }

            var showNotes by rememberSaveable { mutableStateOf(false) }
            SettingsGroup(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.settings_about),
                summary = BuildConfig.VERSION_NAME,
                expanded = Group.ABOUT in open,
                onToggle = { toggle(Group.ABOUT) },
            ) {
                // Out of the (i) it was hidden behind: this is the one place on the screen where
                // prose is the content rather than an explanation of a control.
                RwilcoCard {
                    Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                        Text(
                            text = stringResource(R.string.settings_about_body),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.settings_licenses),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SettingsLinkRow(
                    title = stringResource(R.string.settings_release_notes),
                    summary = stringResource(R.string.settings_release_notes_hint, BuildConfig.VERSION_NAME),
                    onClick = { showNotes = true },
                )
                // Last of all, because nobody comes looking for it until something is broken.
                SettingsLinkRow(
                    title = stringResource(R.string.diag_title),
                    summary = stringResource(R.string.diag_row_hint),
                    onClick = onDiagnostics,
                )
            }
            if (showNotes) {
                ReleaseNotesSheet(
                    entries = RELEASES.take(RECENT_RELEASES),
                    title = stringResource(R.string.settings_release_notes),
                    onDismiss = { showNotes = false },
                )
            }
        }
    }
}

/** "Alert · strong": two values on one closed row, joined the way the locale wants them. */
@Composable
private fun join(first: String, second: String): String =
    stringResource(R.string.settings_summary_join, first, second)

/** What the reminders sound like, by name — the chime, the phone's own, or the chosen file. */
@Composable
private fun soundName(sound: AlertSound): String = when (sound) {
    is AlertSound.Bundled -> stringResource(sound.chime.labelRes)
    AlertSound.System -> stringResource(R.string.settings_sound_system)
    is AlertSound.Custom -> sound.label
}

/** The trigger that heads the list: a kind, the order it works out for itself, or neither. */
@Composable
private fun favouriteTriggerName(settings: AppSettings): String = when {
    settings.popularTriggersFirst -> stringResource(R.string.settings_default_trigger_popular)
    settings.defaultTriggerKind != null -> stringResource(settings.defaultTriggerKind!!.titleRes)
    else -> stringResource(R.string.settings_default_trigger_ask)
}

/**
 * One day's worth of hours: up at, to bed at. A bedtime earlier than the waking hour is the
 * next morning's and nothing here needs to say so — [dev.rwilco.model.awakeOn] reads it that
 * way, which is what makes "to bed at 01:30" mean what anybody would expect it to.
 */
@Composable
private fun AwakePair(
    label: String,
    wake: java.time.LocalTime,
    sleep: java.time.LocalTime,
    onWake: (java.time.LocalTime) -> Unit,
    onSleep: (java.time.LocalTime) -> Unit,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Tokens.spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
            TimeField(
                time = wake,
                onChange = onWake,
                label = stringResource(R.string.settings_awake_wake),
                modifier = Modifier.weight(1f),
            )
            TimeField(
                time = sleep,
                onChange = onSleep,
                label = stringResource(R.string.settings_awake_sleep),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** A setting's name, with its explanation folded behind an (i) when it has one. */
@Composable
internal fun SettingTitle(title: String, modifier: Modifier = Modifier, info: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (info != null) InfoBadge(info, title = title)
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
                modifier = Modifier.size(Tokens.sizes.glyphMedium),
            )
        }
    }
}

/**
 * Whether the phone's own touch feedback is on.
 *
 * Read straight rather than remembered: it can be changed in the system settings the row below
 * sends somebody to, and they come back to this screen expecting the warning to have gone.
 */
private fun systemHapticsOn(context: Context): Boolean =
    Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
