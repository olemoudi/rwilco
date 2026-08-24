package dev.rwilco

import android.content.Intent
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
import dev.rwilco.update.UpdateNotifications
import dev.rwilco.update.UpdateWorker

class MainActivity : ComponentActivity() {

    /** Where a notification asked to land (the update card lives in Settings); cleared once shown. */
    private val requestedDestination = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestedDestination.value = intent?.getStringExtra(UpdateNotifications.EXTRA_DEST)
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
                        onDestinationConsumed = { requestedDestination.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedDestination.value = intent.getStringExtra(UpdateNotifications.EXTRA_DEST)
    }

    override fun onResume() {
        super.onResume()
        UpdateWorker.runIfStale(this)
    }
}
