package dev.rwilco

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a fresh start: getIntent() survives a rotation and a process-death
        // restore, and reading it again would push the shared text (or the shortcut's
        // blank form) back on top of whatever the person was doing.
        if (savedInstanceState == null) requestedDestination.value = intent?.let(::destinationOf)
        val app = application as RwilcoApplication
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
                        onDestinationConsumed = {
                            requestedDestination.value = null
                            // And the intent it came from, so a later recreation finds nothing
                            // left to answer.
                            intent = Intent(this, MainActivity::class.java)
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedDestination.value = destinationOf(intent)
    }

    /** A notification's extra, the launcher shortcut, or a shared line of text (see [Destinations]). */
    private fun destinationOf(intent: Intent): String? =
        Destinations.of(intent.action, intent.type, intent.getStringExtra(EXTRA_DESTINATION), intent.getStringExtra(Intent.EXTRA_TEXT))

    override fun onResume() {
        super.onResume()
        UpdateWorker.runIfStale(this)
        val app = application as RwilcoApplication
        // Back from system settings with "all the time" granted, or exact alarms allowed: what
        // that unlocks is armed now rather than at the next reboot.
        app.resyncIfGrantsChanged()
        // And whatever the phone slept through is said now, not at the six-hourly net.
        app.catchUpIfStale()
        askForNotificationsOnce()
    }

    /**
     * A reminders app with notifications off is a reminders app that fails silently, and on
     * Android 13+ a fresh install starts that way. Asked once per process and from `onResume`,
     * not from `onCreate`: a dialog thrown up while the first frame is still being built lands
     * on top of whatever the app was doing, and every rotation would ask again. Refused, the
     * Settings card keeps saying so, which is where the second ask lives.
     */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (!asked.compareAndSet(false, true)) return
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), ASK_NOTIFICATIONS)
    }

    companion object {
        /** The one permission this activity asks for by hand; the answer is read, not awaited. */
        private const val ASK_NOTIFICATIONS = 1

        /** Once per process: somebody who said no is not asked again by every launch. */
        private val asked = AtomicBoolean(false)

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
