package dev.rwilco.ui.alert

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.alarm.LockedAlerts
import dev.rwilco.ui.theme.RwilcoTheme
import dev.rwilco.ui.theme.Tokens

/**
 * The alert a phone can show before its first unlock (0.172.0): that there is a reminder, and
 * nothing of what it says — the words are in storage the phone has not decrypted, and the owner
 * chose (2026-10-08) not to keep a copy of them anywhere a locked phone could read. Two answers:
 * silence it, or unlock and see it, which is where the real one is waiting.
 *
 * Reads nothing of the app's own: no settings, no database. The theme follows the system.
 */
class LockedAlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()
        setContent {
            RwilcoTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    val spacing = Tokens.spacing
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = spacing.screen, vertical = spacing.xxl),
                        verticalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        Spacer(Modifier.weight(1f))
                        Text(stringResource(R.string.locked_alert_title), style = MaterialTheme.typography.displaySmall)
                        Text(stringResource(R.string.locked_alert_text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = ::unlockAndSee,
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface,
                                contentColor = MaterialTheme.colorScheme.surface,
                            ),
                            modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.sizes.primary),
                        ) { Text(stringResource(R.string.locked_alert_unlock)) }
                        TextButton(
                            onClick = ::silence,
                            modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.sizes.touch),
                        ) { Text(stringResource(R.string.locked_alert_silence)) }
                    }
                }
            }
        }
    }

    /** The cards go, and with them the sound their channel is playing. */
    private fun silence() {
        LockedAlerts.silenceAll(this)
        finish()
    }

    /** The system's own bouncer, and then the app, where the real reminder is. */
    private fun unlockAndSee() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val open = {
            silence()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (keyguard?.isKeyguardLocked != true) return open()
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = runOnUiThread { open() }
            },
        )
    }
}
