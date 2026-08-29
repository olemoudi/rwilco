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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.rwilco.MainActivity
import dev.rwilco.RwilcoApplication
import dev.rwilco.alarm.ReminderScheduler
import dev.rwilco.model.AlertStacking
import dev.rwilco.model.Reminder
import dev.rwilco.model.Snooze
import dev.rwilco.model.VibrationLimits
import dev.rwilco.model.awaitingAnswer
import dev.rwilco.model.firingPlan
import dev.rwilco.model.loopsOnScreen
import dev.rwilco.model.soundFor
import dev.rwilco.ui.theme.RwilcoTheme
import dev.rwilco.ui.theme.resolvesToDark
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The reminder — or reminders — taking over the screen.
 *
 * Its own activity, not a route in the app: a full-screen intent can only launch an activity,
 * showing over the lock screen and turning the screen on are activity-level, and an alarm at
 * three in the morning must not drop the person into the app's back stack when they dismiss it.
 *
 * One instance (`singleTask`), and every start reaches it: a second reminder ringing while the
 * first is still up arrives through [onNewIntent] and joins the screen instead of replacing it
 * — either behind the first, shown the instant it is answered, or beside it as a strip
 * ([AlertStacking]). Each stays until it is dealt with, here or from the shade: every reminder
 * on the screen is watched in the database, and one answered elsewhere leaves by itself.
 */
class AlertActivity : ComponentActivity() {

    private val ringer by lazy { AlertRinger(this) }
    private val app get() = application as RwilcoApplication

    /** The reminders on this screen, in the order they arrived. */
    private val ringing = mutableStateListOf<String>()
    private val loaded = mutableStateMapOf<String, Reminder>()
    private val rules = mutableStateMapOf<String, Int>()
    private val watches = mutableMapOf<String, Job>()

    /** Bumped when a reminder joins: the noise and the two-minute budget start over for it. */
    private var ringEpoch by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The manifest attributes cover the launch; these cover being re-shown while alive.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        savedInstanceState?.getStringArrayList(STATE_RINGING)?.forEachIndexed { index, id ->
            val rule = savedInstanceState.getIntArray(STATE_RULES)?.getOrNull(index)?.takeIf { it >= 0 }
            track(id, rule)
        }
        intent?.let { arrived(it) }
        if (ringing.isEmpty()) {
            finish()
            return
        }

        setContent {
            val settings by app.settings.collectAsStateWithLifecycle()
            val current = settings ?: return@setContent
            val zone = app.clock.zone
            val today = remember { app.clock.instant().atZone(zone).toLocalDate() }
            val reminders = ringing.mapNotNull { loaded[it] }
            if (reminders.isEmpty()) return@setContent
            val plans = reminders.map { firingPlan(it.actions) }
            val sound = plans.any { it.sound }
            val vibrate = plans.any { it.vibrate }
            // One screen can carry several reminders; if any of them is the kind that keeps
            // asking, the tone is the one chosen for that.
            val tone = current.soundFor(plans.any { it.insistent })

            val looping = loopsOnScreen(plans)
            DisposableEffect(sound, vibrate, current.vibration, tone, current.alertToHeadphones, looping, ringEpoch) {
                ringer.start(
                    sound = sound,
                    vibrate = vibrate,
                    pattern = current.vibration,
                    tone = tone,
                    toHeadphones = current.alertToHeadphones,
                    // "Sonido" is once, here too: the screen only goes round and round for the
                    // reminders that asked to be insisted at.
                    looping = loopsOnScreen(plans),
                )
                onDispose { ringer.stop() }
            }
            // An alarm that rings for ever is one nobody leaves the house with. The alert stays
            // up; the noise gives up, exactly as an alarm clock does — and so does the hold on
            // the screen. Nobody answered in a minute because nobody is here, and a screen lit
            // at full brightness until somebody comes home costs more battery than everything
            // else in this app put together. The alert is still on it when they do, and the
            // notification is still in the shade either way.
            //
            // **The noise stops when the buzz does** ([VibrationLimits.LONGEST]). The two are
            // one alarm and they used to end at different times — the motor at its minute, the
            // looping tone a minute later — so the last half of it was a sound with nothing
            // under it. A minute is what the vibration's own limit was argued down to, and a
            // ring that has gone round for one has made the same point.
            LaunchedEffect(ringEpoch) {
                delay(RING_TIMEOUT_MS)
                ringer.stop()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            RwilcoTheme(darkTheme = current.theme.resolvesToDark(), haptics = current.haptics) {
                val items = reminders.map { AlertItem(it.id, AlertContent.fromReminder(it, today, current.defaultTime, rules[it.id])) }
                if (items.size > 1 && current.alertStacking == AlertStacking.STRIPS) {
                    AlertStackScreen(
                        items = items,
                        onDone = { id -> answer(id) { app.firing.dismiss(id) } },
                        onSnooze = { id, snooze -> answer(id) { app.firing.snooze(id, snooze) } },
                        onView = ::view,
                        snoozes = current.notificationSnoozes,
                        customMinutes = current.snoozeCustomMinutes,
                    )
                } else {
                    val first = items.first()
                    AlertScreen(
                        content = first.content,
                        preview = false,
                        waiting = items.size - 1,
                        onDone = { answer(first.id) { app.firing.dismiss(first.id) } },
                        onSnooze = { snooze: Snooze -> answer(first.id) { app.firing.snooze(first.id, snooze) } },
                        onView = { view(first.id) },
                        customMinutes = current.snoozeCustomMinutes,
                    )
                }
            }
        }
    }

    /** A reminder id carried by an intent: the launch, or a later start reaching the live screen. */
    private fun arrived(intent: Intent) {
        val id = ReminderScheduler.reminderIdOf(intent) ?: return
        track(id, ReminderScheduler.ruleIndexOf(intent))
    }

    /**
     * Put a reminder on the screen and keep it there only while it is still owed an answer: the
     * row is watched, so "Hecho" from the notification takes it down here too.
     */
    private fun track(id: String, ruleIndex: Int?) {
        if (id in ringing) return
        ringing += id
        if (ruleIndex != null) rules[id] = ruleIndex
        ringEpoch++
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        watches[id] = lifecycleScope.launch {
            app.repository.observe(id).collect { reminder ->
                if (reminder == null || !reminder.awaitingAnswer(app.clock.instant())) drop(id) else loaded[id] = reminder
            }
        }
    }

    private fun drop(id: String) {
        watches.remove(id)?.cancel()
        ringing.remove(id)
        loaded.remove(id)
        rules.remove(id)
        if (ringing.isEmpty()) close()
    }

    /** Every answer takes the reminder off the screen first, then does the work off the main thread. */
    private fun answer(id: String, work: suspend () -> Unit) {
        drop(id)
        app.appScope.launch { work() }
    }

    private fun view(id: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.reminderDestination(id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        drop(id)
    }

    private fun close() {
        ringer.stop()
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A second reminder firing while this one is up joins the screen; it does not swap the
        // words under somebody's thumb (sequential) or take the screen for itself (strips).
        arrived(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_RINGING, ArrayList(ringing))
        outState.putIntArray(STATE_RULES, IntArray(ringing.size) { rules[ringing[it]] ?: -1 })
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
        /** As long as the buzz beside it, and no longer: see the LaunchedEffect above. */
        val RING_TIMEOUT_MS = VibrationLimits.LONGEST.toMillis()
        const val STATE_RINGING = "ringing"
        const val STATE_RULES = "rules"
    }
}
