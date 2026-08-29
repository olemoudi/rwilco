package dev.rwilco.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.diag.collectDiagnostics
import dev.rwilco.diag.report
import dev.rwilco.ui.components.HoldOverlay
import dev.rwilco.ui.components.HoldOverlayState
import dev.rwilco.ui.components.LocalHoldOverlay
import dev.rwilco.ui.components.LocalSnackbar
import dev.rwilco.ui.components.rememberSnackbarController
import dev.rwilco.ui.done.DoneScreen
import dev.rwilco.ui.done.DoneViewModel
import dev.rwilco.ui.editor.EditorScreen
import dev.rwilco.ui.editor.EditorViewModel
import dev.rwilco.ui.home.HomeScreen
import dev.rwilco.ui.home.HomeViewModel
import dev.rwilco.ui.settings.BackupScreen
import dev.rwilco.ui.settings.BackupViewModel
import dev.rwilco.ui.settings.DiagnosticsScreen
import dev.rwilco.ui.settings.SettingsScreen
import dev.rwilco.ui.settings.SettingsViewModel
import dev.rwilco.ui.settings.WatchLogScreen
import dev.rwilco.ui.settings.WhatsNewSheet
import dev.rwilco.ui.theme.Tokens
import kotlinx.coroutines.launch
import dev.rwilco.Destinations

@Composable
fun RwilcoApp(
    app: RwilcoApplication,
    requestedDestination: String? = null,
    onDestinationConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val snackbarHost = remember { SnackbarHostState() }
    val snackbar = rememberSnackbarController(snackbarHost)
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val diagnosticsCopied = stringResource(R.string.home_diagnostics_copied)
    val diagnosticsFailed = stringResource(R.string.home_diagnostics_failed)
    val motion = Tokens.motion
    // What a held button dims the screen with. At the root because that is what it covers.
    val holdOverlay = remember { HoldOverlayState() }

    LaunchedEffect(requestedDestination) {
        val reminderId = MainActivity.reminderIdIn(requestedDestination)
        val sharedText = Destinations.sharedTextIn(requestedDestination)
        when {
            requestedDestination == Destinations.NEW -> {
                navController.navigate(Routes.Editor()) { launchSingleTop = true }
                onDestinationConsumed()
            }
            sharedText != null -> {
                navController.navigate(Routes.Editor(sharedText = sharedText)) { launchSingleTop = true }
                onDestinationConsumed()
            }
            requestedDestination == MainActivity.DESTINATION_SETTINGS -> {
                navController.navigate(Routes.Settings) { launchSingleTop = true }
                onDestinationConsumed()
            }
            requestedDestination == MainActivity.DESTINATION_BACKUP -> {
                // Settings underneath, so "back" from the backup lands where it lives.
                navController.navigate(Routes.Settings) { launchSingleTop = true }
                navController.navigate(Routes.Backup) { launchSingleTop = true }
                onDestinationConsumed()
            }
            reminderId != null -> {
                navController.navigate(Routes.Editor(reminderId)) { launchSingleTop = true }
                onDestinationConsumed()
            }
        }
    }

    CompositionLocalProvider(LocalSnackbar provides snackbar, LocalHoldOverlay provides holdOverlay, LocalClock provides app.clock) {
        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Routes.Home,
                // A short rise-and-fade: screens arrive from the thumb's side of the phone.
                enterTransition = { fadeIn(tween(motion.medium)) + slideInVertically(tween(motion.medium, easing = motion.emphasized)) { it / 16 } },
                exitTransition = { fadeOut(tween(motion.fast)) },
                popEnterTransition = { fadeIn(tween(motion.medium)) },
                popExitTransition = { fadeOut(tween(motion.fast)) + slideOutVertically(tween(motion.medium, easing = motion.emphasized)) { it / 16 } },
            ) {
                composable<Routes.Home> {
                    HomeScreen(
                        viewModel = viewModel(factory = HomeViewModel.Factory(app)),
                        onNew = { navController.navigate(Routes.Editor()) },
                        onNewFromPreset = { id -> navController.navigate(Routes.Editor(fromPresetId = id)) },
                        onEditPreset = { id -> navController.navigate(Routes.Editor(editPresetId = id)) },
                        onNewPreset = { navController.navigate(Routes.Editor(newPreset = true)) },
                        onOpen = { id -> navController.navigate(Routes.Editor(id)) },
                        onClone = { id -> navController.navigate(Routes.Editor(cloneOfId = id)) },
                        onKeepAsPreset = { id -> navController.navigate(Routes.Editor(cloneOfId = id, newPreset = true)) },
                        onDoneList = { navController.navigate(Routes.Done) },
                        onSettings = { navController.navigate(Routes.Settings) },
                        // Built here rather than in the ViewModel: a report is a snapshot of the
                        // whole app — permissions, settings, the alarm log, the place watch —
                        // and Home's ViewModel knows about none of that. Collected fresh on
                        // every tap, because the reason for tapping is always something that
                        // just happened.
                        onDiagnostics = {
                            scope.launch {
                                val report = runCatching { app.collectDiagnostics().report() }.getOrNull()
                                if (report == null) {
                                    snackbar.show(diagnosticsFailed)
                                } else {
                                    clipboard.setText(AnnotatedString(report))
                                    snackbar.show(diagnosticsCopied)
                                }
                            }
                        },
                    )
                }
                composable<Routes.Editor> { entry ->
                    val route = entry.toRoute<Routes.Editor>()
                    val deletedMessage = stringResource(R.string.home_deleted)
                    val undoLabel = stringResource(R.string.common_undo)
                    EditorScreen(
                        viewModel = viewModel(
                            key = "editor/${route.reminderId}/${route.fromPresetId}/${route.cloneOfId}/${route.editPresetId}/${route.newPreset}/${route.sharedText}",
                            factory = EditorViewModel.Factory(app, route.reminderId, route.fromPresetId, route.cloneOfId, route.editPresetId, route.newPreset, route.sharedText),
                        ),
                        onClose = { navController.popBackStack() },
                        onDeleted = { reminder ->
                            // The editor's scope dies with the screen; the undo outlives it.
                            snackbar.show(deletedMessage, undoLabel) {
                                app.appScope.launch { app.repository.restore(reminder) }
                            }
                        },
                    )
                }
                composable<Routes.Done> {
                    DoneScreen(
                        viewModel = viewModel(factory = DoneViewModel.Factory(app)),
                        clock = app.clock,
                        onBack = { navController.popBackStack() },
                        onOpen = { id -> navController.navigate(Routes.Editor(id)) },
                    )
                }
                composable<Routes.Settings> {
                    SettingsScreen(
                        viewModel = viewModel(factory = SettingsViewModel.Factory(app)),
                        onBack = { navController.popBackStack() },
                        onWatchLog = { navController.navigate(Routes.WatchLog) },
                        onBackup = { navController.navigate(Routes.Backup) },
                        onDiagnostics = { navController.navigate(Routes.Diagnostics) },
                    )
                }
                composable<Routes.Diagnostics> {
                    DiagnosticsScreen(app = app, onBack = { navController.popBackStack() })
                }
                composable<Routes.Backup> {
                    BackupScreen(
                        viewModel = viewModel(factory = BackupViewModel.Factory(app)),
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<Routes.WatchLog> {
                    WatchLogScreen(
                        viewModel = viewModel(factory = SettingsViewModel.Factory(app)),
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            // At the top, where nothing is. At the bottom it landed squarely on "Nuevo" — the
            // one button every screen puts in the thumb's way on purpose — so the price of
            // being told a reminder was deleted was not being able to write the next one for
            // five seconds. An undo is a thing to read, not a thing to reach for: the reaching
            // is the button underneath it.
            SnackbarHost(
                hostState = snackbarHost,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = Tokens.spacing.sm),
            )
            // Last, so it dims everything: the screens, the snackbar and the sheets alike.
            HoldOverlay(holdOverlay)
            val settings by app.settings.collectAsStateWithLifecycle()
            settings?.let { current ->
                WhatsNewSheet(
                    lastSeenVersionCode = current.lastSeenVersionCode,
                    onSeen = { seen -> app.appScope.launch { app.settingsStore.update { it.copy(lastSeenVersionCode = seen) } } },
                )
            }
        }
    }
}
