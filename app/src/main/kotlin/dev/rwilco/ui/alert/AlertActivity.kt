package dev.rwilco.ui.alert

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
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
import dev.rwilco.model.asksToBeSilenced
import dev.rwilco.model.awaitingAnswer
import dev.rwilco.model.firingPlan
import dev.rwilco.model.dayShape
import dev.rwilco.model.hushedByTheHour
import dev.rwilco.model.hushed
import dev.rwilco.model.loopsOnScreen
import dev.rwilco.model.soundFor
import dev.rwilco.ui.theme.RwilcoTheme
import dev.rwilco.ui.theme.resolvesToDark
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.rwilco.model.notificationSnoozeOffers
import android.widget.Toast
import androidx.compose.runtime.produceState
import dev.rwilco.geo.hereFix
import dev.rwilco.model.SnoozePlace
import dev.rwilco.model.circle
import dev.rwilco.model.hereCircle
import dev.rwilco.model.snoozePlaceOffers
import dev.rwilco.model.speaksForHere
import dev.rwilco.geo.hasBackgroundLocation
import dev.rwilco.R

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

    /**
     * The ones being shown although nothing is owed: the safety net's notes about a reminder
     * that never rang, or one still waiting at a place. See [ReminderScheduler.EXTRA_ANYWAY].
     * They are held until they are answered here, and they make no noise — a note about
     * something that already got away is not an alarm.
     */
    private val anyway = mutableStateListOf<String>()

    /** Bumped when a reminder joins: the noise and the two-minute budget start over for it. */
    private var ringEpoch by mutableIntStateOf(0)

    /**
     * Whether there is a noise going on right now — which is a different question from whether
     * this reminder asked for one, and the one the big button is about.
     *
     * It goes false three ways: somebody silenced it, the minute ran out, or the screen went
     * away. All three run through [hush], so the button and the ringer can never disagree.
     */
    private var noise by mutableStateOf(false)

    /** Stop the noise and say so. The alert stays up: the reminder is still owed an answer. */
    private fun hush() {
        ringer.stop()
        noise = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The manifest attributes cover the launch; these cover being re-shown while alive.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val wasHeld = savedInstanceState?.getStringArrayList(STATE_ANYWAY).orEmpty()
        savedInstanceState?.getStringArrayList(STATE_RINGING)?.forEachIndexed { index, id ->
            val rule = savedInstanceState.getIntArray(STATE_RULES)?.getOrNull(index)?.takeIf { it >= 0 }
            track(id, rule, held = id in wasHeld)
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
            // What the screen is about to make a noise for, which is not everything on it. A
            // reminder opened from one of the net's notes arrives silent, because the noise it
            // was asked for belongs to a moment that has already been and gone — and so does
            // one whose hour is one somebody is asleep at, which this screen has to ask for
            // itself: it builds its own plan from the row, so without this, tapping the card at
            // three in the morning would start the alarm the firing had just been careful not
            // to make ([hushedByTheHour]).
            val plans = reminders
                .filter { it.id !in anyway }
                .map { reminder ->
                    val plan = firingPlan(reminder.actions)
                    val momentFor = reminder.lastFiredAt ?: app.clock.instant()
                    if (hushedByTheHour(momentFor, app.clock.instant(), zone, current.dayShape)) plan.hushed() else plan
                }
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
                    looping = looping,
                )
                // **Only a noise that outlives the tap is one there is anything to answer.**
                // A single tone is over in a second or two and nothing ever cleared this, so
                // the red button sat on top of "hecho" for the rest of the minute, protecting
                // somebody from a silence. See [asksToBeSilenced].
                noise = asksToBeSilenced(plans)
                onDispose { hush() }
            }
            // An alarm that rings for ever is one nobody leaves the house with. The alert stays
            // up; the noise gives up, exactly as an alarm clock does. The alert is still on the
            // screen when somebody comes back to it, and the notification is still in the shade
            // either way.
            //
            // **The screen is not held on at all** (0.63.0). It used to be pinned awake for the
            // minute the noise lasted, on the reasoning that an alarm you cannot see is not an
            // alarm — but a takeover that keeps a phone lit is a decision about somebody's
            // battery and their bedroom that the phone's own screen timeout has already made,
            // and the alert is not more entitled to override it than anything else here.
            // [setTurnScreenOn] still brings the screen up so the alert is seen; from there it
            // goes off when the system says so.
            //
            // **The noise stops when the buzz does** ([VibrationLimits.LONGEST]). The two are
            // one alarm and they used to end at different times — the motor at its minute, the
            // looping tone a minute later — so the last half of it was a sound with nothing
            // under it. A minute is what the vibration's own limit was argued down to, and a
            // ring that has gone round for one has made the same point.
            LaunchedEffect(ringEpoch) {
                delay(RING_TIMEOUT_MS)
                hush()
            }

            // The place answers: the saved place this phone's reminders use most, as a doorway
            // in, and "al salir de aquí". Read once per screen; neither the saved places nor
            // where the watch last saw the phone change while an alarm is being answered.
            val places by produceState(initialValue = emptyList<SnoozePlace>(), current.savedPlaces) {
                value = snoozePlaceOffers(current.savedPlaces, app.repository.allNow(), app.placeWatch.read(), hasBackgroundLocation())
            }
            RwilcoTheme(darkTheme = current.theme.resolvesToDark(), haptics = current.haptics) {
                val items = reminders.map { AlertItem(it.id, AlertContent.fromReminder(it, today, current.defaultTime, rules[it.id])) }
                if (items.size > 1 && current.alertStacking == AlertStacking.STRIPS) {
                    AlertStackScreen(
                        items = items,
                        onDone = { id -> answer(id) { app.firing.dismiss(id) } },
                        onSnooze = { id, snooze -> answer(id) { app.firing.snooze(id, snooze) } },
                        onView = ::view,
                        snoozes = current.notificationSnoozeOffers,
                        customMinutes = current.snoozeCustomMinutes,
                        onDoneAll = { answerAll(items.map { it.id }) { id -> app.firing.dismiss(id) } },
                        onSnoozeAll = { snooze -> answerAll(items.map { it.id }) { id -> app.firing.snooze(id, snooze) } },
                        ringing = noise,
                        onSilence = ::hush,
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
                        places = places,
                        onSnoozeToPlace = { offer -> snoozeToPlace(first.id, offer) },
                        ringing = noise,
                        onSilence = ::hush,
                    )
                }
            }
        }
    }

    /** A reminder id carried by an intent: the launch, or a later start reaching the live screen. */
    private fun arrived(intent: Intent) {
        val id = ReminderScheduler.reminderIdOf(intent) ?: return
        track(id, ReminderScheduler.ruleIndexOf(intent), ReminderScheduler.anywayIn(intent))
    }

    /**
     * Put a reminder on the screen and keep it there only while it is still owed an answer: the
     * row is watched, so "Hecho" from the notification takes it down here too. [held] is the
     * exception and the whole of it — a reminder shown because one of the net's notes was
     * tapped, which is owed nothing and stays until it is answered here.
     */
    private fun track(id: String, ruleIndex: Int?, held: Boolean = false) {
        if (id in ringing) {
            // Already on the screen — but one being shown *silently* because a note was tapped
            // stops being that the moment its own alarm arrives (a wait at a place, and the
            // door opened while the screen was up). It gets the noise it was asked for, and the
            // epoch is what starts it.
            if (!held && anyway.remove(id)) ringEpoch++
            return
        }
        ringing += id
        if (ruleIndex != null) rules[id] = ruleIndex
        if (held) anyway += id
        ringEpoch++
        watches[id] = lifecycleScope.launch {
            app.repository.observe(id).collect { reminder ->
                // One opened from a note is held until it is answered here: it was never owed
                // an answer, so the usual test would take it off the screen on the very first
                // emission and the tap would have flashed and done nothing. The explicit
                // answers still call [drop] themselves, and a deleted row still leaves.
                val owed = reminder != null && reminder.awaitingAnswer(app.clock.instant())
                if (reminder == null || !(owed || id in anyway)) drop(id) else loaded[id] = reminder
            }
        }
    }

    private fun drop(id: String) {
        watches.remove(id)?.cancel()
        ringing.remove(id)
        loaded.remove(id)
        rules.remove(id)
        anyway.remove(id)
        if (ringing.isEmpty()) close()
    }

    /** Every answer takes the reminder off the screen first, then does the work off the main thread. */
    private fun answer(id: String, work: suspend () -> Unit) {
        drop(id)
        app.appScope.launch { work() }
    }

    /** One "al salir de aquí" at a time: the position takes a few seconds, and a second tap must not ask twice. */
    private var seekingHere = false

    /**
     * "Al llegar a casa" is answered like any snooze. "Al salir de aquí" first has to know where
     * here is, and the screen stays up while it asks: with nothing to draw the circle around
     * there is nothing to write, and the reminder is still owed an answer.
     */
    private fun snoozeToPlace(id: String, offer: SnoozePlace) {
        when (offer) {
            is SnoozePlace.Arrive -> answer(id) {
                val now = app.clock.instant()
                app.firing.snoozeToPlace(id, offer.circle(), app.placeWatch.read().lastFix?.takeIf { it.speaksForHere(now) }, app.placeWatcher::remember)
            }
            SnoozePlace.LeaveHere -> {
                if (seekingHere) return
                seekingHere = true
                lifecycleScope.launch {
                    val fix = try { hereFix(this@AlertActivity, app.placeWatch, app.clock.instant()) } finally { seekingHere = false }
                    if (fix == null) {
                        Toast.makeText(this@AlertActivity, R.string.snooze_no_fix, Toast.LENGTH_SHORT).show()
                    } else {
                        val here = hereCircle(fix, getString(R.string.snooze_here_label))
                        answer(id) { app.firing.snoozeToPlace(id, here, fix, app.placeWatcher::remember) }
                    }
                }
            }
        }
    }

    /** The same, for all of them at once: the screen empties first, then each is answered in turn. */
    private fun answerAll(ids: List<String>, work: suspend (String) -> Unit) {
        ids.forEach(::drop)
        app.appScope.launch { for (id in ids) work(id) }
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
        hush()
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
        outState.putStringArrayList(STATE_ANYWAY, ArrayList(anyway))
    }

    override fun onStop() {
        super.onStop()
        // Left the screen without answering: the notification is still there, so go quiet.
        hush()
    }

    override fun onDestroy() {
        super.onDestroy()
        hush()
    }

    private companion object {
        /** As long as the buzz beside it, and no longer: see the LaunchedEffect above. */
        val RING_TIMEOUT_MS = VibrationLimits.LONGEST.toMillis()
        const val STATE_RINGING = "ringing"
        const val STATE_RULES = "rules"
        const val STATE_ANYWAY = "anyway"
    }
}
