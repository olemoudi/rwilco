package dev.rwilco

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rwilco.ui.RwilcoApp
import dev.rwilco.ui.theme.RwilcoTheme
import dev.rwilco.ui.theme.resolvesToDark
import dev.rwilco.update.UpdateWorker

class MainActivity : ComponentActivity() {

    /** Where a notification asked to land (the update card lives in Settings); cleared once shown. */
    private val requestedDestination = mutableStateOf<String?>(null)

    /** The answer lands in the Settings card's own check; nothing to do with it here. */
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestedDestination.value = intent?.getStringExtra(EXTRA_DESTINATION)
        val app = application as RwilcoApplication
        // A reminders app with notifications off is a reminders app that fails silently, and on
        // Android 13+ a fresh install starts that way. Asked once, here; refused, the Settings
        // card keeps saying so.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val settings by app.settings.collectAsStateWithLifecycle()
            val current = settings
            if (current == null) {
                // The settings have not been read yet (first frame after a cold start). Hold the
                // window ground rather than flashing the system default and then switching.
                RwilcoTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {} }
            } else {
                val dark = current.theme.resolvesToDark()
                // enableEdgeToEdge() picks the status-bar icon colour from the SYSTEM theme; when
                // the app's own choice differs (light app on a dark phone) the icons go invisible
                // unless the style is re-applied for the theme actually on screen.
                LaunchedEffect(dark) {
                    val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                }
                RwilcoTheme(darkTheme = dark, haptics = current.haptics) {
                    RwilcoApp(
                        app = app,
                        requestedDestination = requestedDestination.value,
                        onDestinationConsumed = { requestedDestination.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedDestination.value = intent.getStringExtra(EXTRA_DESTINATION)
    }

    override fun onResume() {
        super.onResume()
        UpdateWorker.runIfStale(this)
        val app = application as RwilcoApplication
        // Back from system settings with "all the time" granted, or exact alarms allowed: what
        // that unlocks is armed now rather than at the next reboot.
        app.resyncIfGrantsChanged()
        // And whatever the phone slept through is said now, not at the six-hourly net.
        app.catchUpIfStale()
    }

    companion object {
        /** Where a notification wants the app to land: [DESTINATION_SETTINGS] or a reminder. */
        const val EXTRA_DESTINATION = "dest"
        const val DESTINATION_SETTINGS = "settings"
        /** The Backup screen, behind Settings: where a stopped backup's two choices are. */
        const val DESTINATION_BACKUP = "backup"
        private const val REMINDER_PREFIX = "reminder:"

        fun reminderDestination(id: String): String = REMINDER_PREFIX + id

        fun reminderIdIn(destination: String?): String? = destination?.removePrefix(REMINDER_PREFIX)?.takeIf { destination.startsWith(REMINDER_PREFIX) }
    }
}
