package dev.rwilco.ui.alert

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.rwilco.MainActivity
import dev.rwilco.RwilcoApplication
import dev.rwilco.alarm.ReminderScheduler
import dev.rwilco.model.Reminder
import dev.rwilco.model.Snooze
import dev.rwilco.model.firingPlan
import dev.rwilco.ui.theme.RwilcoTheme
import dev.rwilco.ui.theme.resolvesToDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The reminder taking over the screen.
 *
 * Its own activity, not a route in the app: a full-screen intent can only launch an activity,
 * showing over the lock screen and turning the screen on are activity-level, and an alarm at
 * three in the morning must not drop the person into the app's back stack when they dismiss it.
 */
class AlertActivity : ComponentActivity() {

    private val ringer by lazy { AlertRinger(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The manifest attributes cover the launch; these cover being re-shown while alive.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val app = application as RwilcoApplication
        val reminderId = ReminderScheduler.reminderIdOf(intent)
        if (reminderId == null) {
            finish()
            return
        }

        setContent {
            val settings by app.settings.collectAsStateWithLifecycle()
            val current = settings ?: return@setContent
            val reminder by produceState<Reminder?>(initialValue = null, reminderId) {
                value = app.repository.get(reminderId)
                if (value == null) finish()
            }
            val loaded = reminder ?: return@setContent
            val plan = remember(loaded.actions) { firingPlan(loaded.actions) }
            val zone = app.clock.zone
            val today = remember { app.clock.instant().atZone(zone).toLocalDate() }

            DisposableEffect(plan, current.vibration, current.alertSound) {
                ringer.start(sound = plan.sound, vibrate = plan.vibrate, pattern = current.vibration, tone = current.alertSound)
                onDispose { ringer.stop() }
            }
            // An alarm that rings for ever is one nobody leaves the house with. The alert stays
            // up; the noise gives up, exactly as an alarm clock does — and so does the hold on
            // the screen. Nobody answered in two minutes because nobody is here, and a screen
            // lit at full brightness until somebody comes home costs more battery than
            // everything else in this app put together. The alert is still on it when they do,
            // and the notification is still in the shade either way.
            LaunchedEffect(plan) {
                delay(RING_TIMEOUT_MS)
                ringer.stop()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            RwilcoTheme(darkTheme = current.theme.resolvesToDark(), haptics = current.haptics) {
                AlertScreen(
                    content = AlertContent.fromReminder(loaded, today, current.defaultTime),
                    preview = false,
                    onDone = { act { app.firing.dismiss(reminderId) } },
                    onSnooze = { snooze: Snooze -> act { app.firing.snooze(reminderId, snooze) } },
                    onView = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.reminderDestination(reminderId))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        )
                        close()
                    },
                )
            }
        }
    }

    /** Every answer stops the noise first, then does the work, then gets out of the way. */
    private fun act(work: suspend () -> Unit) {
        ringer.stop()
        val app = application as RwilcoApplication
        app.appScope.launch { work() }
        close()
    }

    private fun close() {
        ringer.stop()
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A second reminder firing while this one is up: let the notification carry it rather
        // than swapping the words under somebody's thumb.
        setIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        // Left the screen without answering: the notification is still there, so go quiet.
        ringer.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        ringer.stop()
    }

    private companion object {
        const val RING_TIMEOUT_MS = 2 * 60 * 1000L
    }
}
