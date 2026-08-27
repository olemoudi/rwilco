package dev.rwilco.geo

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import dev.rwilco.alarm.ReminderFiring
import dev.rwilco.data.ReminderRepository
import dev.rwilco.data.SettingsStore
import dev.rwilco.model.GeofenceIds
import dev.rwilco.model.dayShape
import dev.rwilco.model.watchedCircles
import dev.rwilco.model.Crossing
import dev.rwilco.model.Fix
import dev.rwilco.model.Movement
import dev.rwilco.model.NoteKind
import dev.rwilco.model.PlaceWatchPolicy
import dev.rwilco.model.PlaceWatchState
import dev.rwilco.model.Transition
import dev.rwilco.model.WatchPlan
import dev.rwilco.model.WatchNote
import dev.rwilco.model.WatchedPlace
import dev.rwilco.model.blindRetry
import dev.rwilco.model.busyNotice
import dev.rwilco.model.crossingIsNews
import dev.rwilco.model.pollsSince
import dev.rwilco.model.remembering
import dev.rwilco.model.stepPlaceWatch
import dev.rwilco.model.stepWithoutLooking
import dev.rwilco.model.stirredWait
import dev.rwilco.notify.WatchNotices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * The app's own eye on its places, alongside the phone's geofences.
 *
 * The geofences are the net: free, always on, and the system's own word on where the phone
 * is. This is the second opinion, and the one that decides its own cost. However many places
 * are being waited on, there is ONE alarm, ONE fix and ONE decision: each check reads a single
 * fix, judges every place against it with hysteresis (`stepPlaceWatch`), fires the crossings
 * that match a rule, and sets the next alarm to the soonest look any one place asks for — from
 * two minutes walking up to a door to an afternoon for a place three provinces away, which no
 * road gets anybody to sooner. So the cadence is the most impatient circle's and the answer is
 * everybody's: a place across town that would settle for half an hour is judged every five
 * minutes anyway, because the fix the doorstep paid for is already in hand. That is also why a
 * circle whose gate is shut is not dropped but demoted — see [Watching.listening].
 *
 * What it costs: a fix is wifi/cell unless the line is close and the phone moving, when it is
 * GPS for a few seconds; the alarm is allow-while-idle, which Doze holds to one per nine
 * minutes — and a phone in Doze is a phone that is not moving, so nothing is lost. At the
 * ceiling that is a GPS fix every two minutes for the minutes it takes to arrive, well under a
 * percent of a day's battery; at the floor, no fix at all — see `stepWithoutLooking`, which
 * spends the phone's own motion sensor ([MotionSensor], free) instead of its radios.
 *
 * A firing both the geofence and this watch see is fired once: [ReminderFiring] drops a place
 * firing that repeats within minutes.
 */
class PlaceWatcher(
    private val context: Context,
    private val repository: ReminderRepository,
    private val firing: ReminderFiring,
    private val store: PlaceWatchStore,
    private val log: PlaceLogStore,
    private val settings: SettingsStore,
    private val clock: Clock,
    /** The phone's own answer to "have you moved?", which costs nothing to ask. */
    private val motion: MotionSensor = MotionSensor(context),
) {

    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val fused: FusedLocationProviderClient by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private val battery = BatteryGauge(context)

    /**
     * One watch, one turn at a time. [sync], [check] and [accept] each read the store, decide,
     * and write it back, and they arrive through different doors at the same moment: the alarm
     * that starts a dead process runs [check] while the process's own start-up runs [sync], and
     * a geofence can report a crossing in the middle of either. Interleaved, the later write
     * hands the store the earlier one's stale reading — a check's judgement lost, a place
     * marked outside again after it rang, and a "look soon" planned five seconds after the
     * look that had just been taken.
     */
    private val lock = Mutex()

    /**
     * The look this process last set an alarm for, and how far the nearest line was when it did.
     * In memory rather than in the store because that is exactly where they are worth having:
     * the sensor only speaks for the process that armed it, so [stirred] and these two are valid
     * together or not at all, and reading them costs nothing on a sensor callback that must not
     * block.
     */
    @Volatile
    private var plannedAt: Instant? = null

    @Volatile
    private var plannedGapM: Double? = null

    init {
        motion.onMotion = ::stirred
    }

    /** What the Settings card shows: last fix, nearest line, next look. */
    val state = store.state

    /**
     * Every circle the open reminders need watched, keyed the way the geofences are.
     *
     * A position is the most expensive answer this app buys, so it is the last one it asks for
     * and the first one it declines to. See [watching] for what takes a circle off the list.
     *
     * Two kinds, and the difference is whether they may ring. A rule's *trigger* is an event to
     * be caught, and only while the rule is still pending: under "todos" a rule whose moment
     * has already happened is ticked off in `firedRules` and watching its place again all week
     * is a fix an hour for an answer nobody is waiting for. A rule's *conditions* can name
     * circles too ("y sólo si estoy en casa"), and those are watched for their state alone —
     * `fires = false`, so `stepPlaceWatch` never turns one into a firing — because the answer
     * has to be in hand at the moment some other trigger goes off. And only then: see
     * [PlaceWatchPolicy.ASK_LEAD].
     *
     * These are the circles worth a fix, and the only ones a crossing is acted on from. A
     * circle whose gate is shut is not here at all — see [Watching.listening].
     */
    suspend fun places(): List<WatchedPlace> = watching().asking

    /**
     * What is worth a fix now, what merely rides along, and when the next shut gate opens.
     *
     * All of the deciding is `Reminder.watchedCircles` (`core-model`, `PlaceGate.kt`) — the
     * gates, the cadence floor under "todos", the circles waiting to come undone — because it
     * is arithmetic on the rules and the clock, a card needs the same answer to say whether a
     * circle is costing anything, and none of it can be tested on a JVM from in here. What is
     * left is this: sort them into the ones that ask for a fix and the ones that only listen,
     * and remember when the soonest shut gate opens.
     *
     * A shut gate stops a circle from *buying* a fix; it does not stop it from being told. The
     * look is one fix for however many circles are being waited on, and judging one more
     * against it costs arithmetic, so the shut ones go to the step as [Watching.listening]:
     * judged, never planned from, never rung.
     */
    private suspend fun watching(): Watching {
        val now = clock.instant()
        val current = settings.settings.first()
        val asking = ArrayList<WatchedPlace>()
        val listening = ArrayList<WatchedPlace>()
        val remembered = HashSet<String>()
        var opens: Instant? = null
        for (reminder in repository.openNow()) {
            for (circle in reminder.watchedCircles(now, clock.zone, current.defaultTime, current.dayShape, current.dayStart)) {
                val gate = circle.opensAt
                if (gate == null) asking += circle.place else listening += circle.place
                if (gate != null && (opens == null || gate < opens!!)) opens = gate
                // A resting circle keeps its baseline only if it is waiting for a doorway. A
                // *state* has to be asked afresh when the rest is over, and keeping the answer
                // is how "mientras esté en casa, y vuelve cada día" rang once and then never
                // again: the phone never left, so the side never changed, so there was no
                // moment for the watch to report. Forgotten, the first look after the rest
                // finds it true and says so.
                if (circle.resting && circle.place.onCrossing) remembered += circle.place.id
            }
        }
        return Watching(asking, listening, opens, remembered)
    }

    /**
     * The circles worth a fix now, the ones that only ride along on it, when the next shut gate
     * opens, and the ids of resting circles that must keep the memory of which side the phone
     * is on even through a look that takes no fix at all.
     *
     * [listening] never plans and never rings; it is judged and nothing else. What it buys is
     * an up-to-date baseline for the moment its gate opens, paid for by somebody else's look —
     * and only while the looks are actually happening: a watch with nothing left to ask about
     * takes no fix, and a memory nothing has refreshed is dropped rather than kept to be
     * compared against weeks later, which is how a "crossing" nobody made gets invented.
     */
    private data class Watching(
        val asking: List<WatchedPlace>,
        val listening: List<WatchedPlace>,
        val opensAt: Instant?,
        val remembered: Set<String>,
    )

    /**
     * The list of places changed, or the app started: forget places that are gone, and look soon
     * if there is anything new to look at. A place added while standing inside it is baselined
     * by that look, not rung.
     *
     * "If" is the whole point. This runs on every process start, and the process starts every
     * time an alarm reaches an app the system had cleaned up — including the place watch's own
     * alarm. Looking soon unconditionally would mean a second fix five seconds after every
     * check, all day, for a list of places that had not changed since the last one. So the
     * pending look is left standing unless a place has never been judged (nothing in `inside`
     * knows it) or nothing is pending at all, which is also what a reboot looks like.
     */
    suspend fun sync() = lock.withLock {
        val watch = watching()
        val places = watch.asking
        val current = store.read()
        if (places.isEmpty() || !context.hasBackgroundLocation()) {
            // Nothing worth a fix now is not the same as nothing to watch: a set whose hours
            // open at five is worth waking for at five and worth nothing until then.
            val gate = watch.opensAt.takeIf { context.hasBackgroundLocation() }
            store.write(current.copy(inside = current.inside.filterKeys { it in watch.remembered }, nextCheckAt = gate))
            if (gate == null) cancel() else scheduleAt(gate)
            return@withLock
        }
        val ids = places.mapTo(HashSet()) { it.id }
        // A listening circle is not gone, it is waiting, and what the last fix said about it
        // still stands: this is a change of list, not a look. (Every resting circle is one of
        // them, so `remembered` has nothing to add here.) Only the circles that asked for a fix
        // decide whether one is worth taking now.
        val known = (watch.asking + watch.listening).mapTo(HashSet()) { it.id }
        val judged = current.inside.filterKeys { it in known }
        val now = clock.instant()
        // Whichever comes first: what the store remembers, and what a stir already pulled
        // forward in this process (which the store will not have caught up with yet).
        val pending = listOfNotNull(current.nextCheckAt, plannedAt).filter { it > now }.minOrNull()
        val at = if (pending != null && ids.all { it in judged }) pending else now + SOON
        store.write(current.copy(inside = judged, nextCheckAt = at))
        scheduleAt(at)
    }

    /**
     * A crossing the phone's geofences report, judged against what this watch knows and written
     * down if it stands. False means the app's own last fix already had the phone on that side
     * of the line: Play Services re-reading a line nobody crossed, which is what makes a place
     * reminder ring at somebody who never left home.
     */
    suspend fun accept(placeId: String, transition: Transition): Crossing = lock.withLock {
        val state = store.read()
        val now = clock.instant()
        val live = runCatching { places().firstOrNull { it.id == placeId } }.getOrNull()
        val label = live?.label ?: placeId
        // A circle that asks for the doorway and has already rung is owed the other side before
        // it rings again: the crossing has to be one the app has seen the far side of, and what
        // it cannot vouch for is not news. The first ring keeps the benefit of the doubt. A
        // circle read as a state needs none of this — what stops it ringing twice is the round
        // it has already rung in (`ReminderFiring`), not the geometry.
        val reminder = repository.get(GeofenceIds.reminderIdOf(placeId))
        val strict = live?.onCrossing == true && reminder?.lastFiredAt != null
        if (!crossingIsNews(state, placeId, transition, now, strict = strict)) {
            Log.i(TAG, "geofence says $transition at $placeId, but we were already there")
            log.note(WatchNote(at = now, kind = NoteKind.ECHO, place = label, inside = state.inside[placeId]))
            return@withLock Crossing.NOTHING
        }
        store.write(state.remembering(placeId, transition))
        log.note(WatchNote(at = now, kind = NoteKind.FENCE, place = label, inside = transition == Transition.ENTER))
        // Written down either way; acted on only for the crossing the rule waits for, and only
        // while the circle is worth watching at all — not resting, not outside its hours. What
        // acting on it means is the circle's own to say: a place under "todos" that has already
        // been ticked off is waiting for the crossing that takes that tick back.
        if (live != null && live.transition == transition) live.crossing else Crossing.NOTHING
    }

    /**
     * One look: where is the phone, what did it cross, when to look again. Run by the alarm.
     *
     * Whatever goes wrong inside — a store that will not open, a provider that throws — the one
     * thing this must not do is come back without a next look armed: the alarm chain is the
     * watch, and a link dropped here is a watch that stops until something else happens to
     * start it (a process start, the six-hourly worker). A failed look is retried the way a
     * blind one is. The receiver that runs this does the same for a look it had to cut short.
     */
    suspend fun check() {
        try {
            lock.withLock { look() }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.e(TAG, "the look failed; trying again later", t)
            recover()
        }
    }

    /**
     * The next look is missing, or is behind us: arm one a blind retry away. What the receiver
     * calls when [check] ran out of its time, and what [check] calls when it blew up — the two
     * ways a look can end without having set the alarm that keeps the watch alive. Harmless
     * when the watch has nothing to do: that look finds nothing to watch and cancels itself.
     */
    fun recover() {
        val planned = plannedAt
        if (planned != null && planned > clock.instant()) return
        val at = clock.instant() + NO_FIX_RETRY
        Log.w(TAG, "no next look was left armed; one at $at")
        scheduleAt(at)
    }

    private suspend fun look() {
        val watch = watching()
        val places = watch.asking
        if (places.isEmpty() || !context.hasBackgroundLocation()) {
            val gate = watch.opensAt.takeIf { context.hasBackgroundLocation() }
            // What is no longer watched is no longer known. Leaving the last answer standing
            // would have a card say "no se cumple ahora mismo" about a circle nothing has
            // looked at since the window closed last night; the honest mark for that is the
            // one that says nobody has looked. A resting circle is the exception and keeps its
            // memory, because which side of the line the phone is on is what decides whether
            // the next crossing is an arrival. sync() has always done this; a look had not.
            //
            // A listening circle is NOT the exception. What it knows is worth exactly as much
            // as the looks that keep renewing it, and this is the look where those stop: a
            // judgement left standing here would be compared, whenever the gate finally opens,
            // against a fix from another week, and the crossing that comes out of that
            // subtraction is one nobody made.
            val current = store.read()
            val forgotten = current.inside.filterKeys { it in watch.remembered }
            if (gate == null) {
                store.write(current.copy(inside = forgotten))
                cancel()
            } else {
                Log.i(TAG, "nothing worth a fix until the hours open")
                store.write(current.copy(inside = forgotten, nextCheckAt = gate, precise = false))
                scheduleAt(gate)
            }
            return
        }
        val before = store.read()
        val now = clock.instant()
        val charge = battery.remaining()
        // What the phone felt while nobody was looking; the next listening window starts here.
        val sensed = motion.consume()
        val rest = stepWithoutLooking(before, places, now, sensed, charge, listening = watch.listening)
        val rested = rest?.plan
        if (rest != null && rested != null) {
            // Never past the moment another circle's hours open, for the same reason as below.
            val at = watch.opensAt?.coerceAtMost(now + rested.wait) ?: (now + rested.wait)
            store.write(rest.state.copy(nextCheckAt = at))
            scheduleAt(at, rested.gapM)
            Log.i(TAG, "nothing has moved; no fix taken, next look in ${Duration.between(now, at).toMinutes()} min")
            write(NoteKind.REST, now, rest.state, rested, rest.movement, charge)
            return
        }
        val fix = readFix(precise = before.precise)
        // A fix the watch would not vouch for is a fix it must not judge by.
        //
        // When nothing fresh answers — location switched off, the provider cold — the fallback
        // is whatever the phone had lying around, and that can be this morning's. Judging
        // places by it writes the wrong answer into `inside`, and then the geofence's own word
        // on a real arrival is thrown away as "we were already there" (crossingIsNews). The
        // bound is the same one crossingIsNews uses, because it is the same question.
        val stale = fix != null && Duration.between(fix.at, now) > PlaceWatchPolicy.SPEED_MEMORY
        if (fix == null || stale) {
            // Nothing to go on — location off, or nothing answered. Not a reason to give up,
            // but a reason to ask less often: a phone with location switched off will still
            // have it switched off in ten minutes, and in ten minutes after that.
            val wait = blindRetry(before.blindStreak, NO_FIX_RETRY)
            Log.w(TAG, "no fix worth having${if (stale) " (stale)" else ""}; trying again in ${wait.toMinutes()} min")
            val at = now + wait
            store.write(before.copy(nextCheckAt = at, blindStreak = before.blindStreak + 1))
            scheduleAt(at)
            write(NoteKind.BLIND, now, before, plan = null, movement = Movement(sensed = sensed), charge = charge)
            return
        }
        // A fix resets the blind streak: PlaceWatchState is rebuilt from scratch by the step.
        val step = stepPlaceWatch(before, fix, places, now, sensed, charge, listening = watch.listening)
        val plan = step.plan
        if (plan == null) {
            store.write(step.state)
            cancel()
            return
        }
        // Never past the moment another circle's hours open: that fix is the one that answers
        // "was I there when the window started", and it has to be taken before anything asks.
        val at = watch.opensAt?.coerceAtMost(now + plan.wait) ?: (now + plan.wait)
        // The next look is armed BEFORE anything rings. Ringing is the slow part of a look —
        // a notification, maybe a screen — and the receiver's budget is short; a look cut off
        // in the middle of it must already have left the watch its next link.
        // A resting circle's memory needs no merging back in any more: it is one of the
        // listeners the step was handed, so this judgement is its own and up to date.
        store.write(step.state.copy(nextCheckAt = at))
        scheduleAt(at, plan.gapM)
        Log.i(TAG, "${plan.gapM.toInt()} m from ${plan.nearest.label}; next look in ${Duration.between(now, at).toMinutes()} min${if (plan.precise) " (gps)" else ""}")
        write(NoteKind.FIX, now, step.state, plan, step.movement, charge)
        val what = places.associate { it.id to it.crossing }
        for (event in step.events) {
            val reminderId = GeofenceIds.reminderIdOf(event.placeId)
            val ruleIndex = GeofenceIds.triggerIndexOf(event.placeId)
            Log.i(TAG, "watch saw ${event.transition} at ${event.placeId}")
            if (what[event.placeId] == Crossing.TAKES_BACK && ruleIndex != null) {
                firing.untick(reminderId, ruleIndex)
            } else {
                firing.fire(reminderId, ruleIndex = ruleIndex)
            }
        }
    }

    /**
     * One line of the account, and the one thing the watch ever says about itself unprompted.
     *
     * The notice is off unless it has been asked for, and even then it is the log's own rule
     * that decides ([WatchLog.busyNotice]): more polls in the last hour than any cadence in the
     * policy should be able to produce, and nothing said about that hour already.
     */
    private suspend fun write(
        kind: NoteKind,
        now: Instant,
        state: PlaceWatchState,
        plan: WatchPlan?,
        movement: Movement,
        charge: Double?,
    ) {
        val written = log.note(
            WatchNote(
                at = now,
                kind = kind,
                waitS = plan?.wait?.seconds ?: state.nextCheckAt?.let { Duration.between(now, it).seconds },
                gapM = plan?.gapM,
                place = plan?.nearest?.label,
                inside = plan?.nearest?.let { state.inside[it.id] },
                speedMps = movement.speedMps,
                movedM = movement.movedM,
                sensed = movement.sensed,
                stillStreak = state.stillStreak,
                charge = charge?.let { (it * 100).roundToInt() },
                accuracyM = state.lastFix?.accuracyM?.roundToInt(),
                precise = plan?.precise ?: false,
            ),
        )
        if (!written.busyNotice(now)) return
        if (!settings.settings.first().busyWatchNotice) return
        WatchNotices.notifyBusy(context, written.notes.pollsSince(now - PlaceWatchPolicy.BUSY_WINDOW))
        log.noticed(now)
    }

    /**
     * The phone has gone somewhere, and the watch had settled on a long wait because it had not.
     *
     * This is what the half-hour rest inside a place with a "when I leave" rule costs, bought
     * back: the sensor fires as somebody actually walks out, and the look that was twenty-five
     * minutes away moves to [stirredWait] from now. It only ever moves a look *earlier*, only
     * when the nearest line is close enough for going somewhere to mean anything (deep inland of
     * everywhere, or three provinces from the only place being watched, a stir means nothing and
     * the plan stands), and the sensor's one-shot re-arming caps it at one early look per check.
     *
     * Runs off the sensor's delivery thread ([MotionSensor] hands it to a coroutine), so the
     * two binder calls and the log line it writes are nowhere near the main looper.
     */
    private suspend fun stirred() = lock.withLock {
        val planned = plannedAt ?: return@withLock
        val gap = plannedGapM ?: return@withLock
        if (gap >= PlaceWatchPolicy.NEAR_M) return@withLock
        val now = clock.instant()
        val at = now + stirredWait(battery.remaining())
        if (planned <= at) return@withLock
        Log.i(TAG, "the phone stirred ${gap.toInt()} m from a line; looking sooner")
        scheduleAt(at, gap)
        // Its own line in the log, but not through `write`: moving an alarm is not a poll, and
        // counting it as one would have the watch complain about the thing that saves it work.
        log.note(WatchNote(at = now, kind = NoteKind.STIR, waitS = Duration.between(now, at).seconds, gapM = gap, sensed = true))
    }

    /**
     * One fix from the fused provider, GPS when [precise] and the cheaper blend otherwise, or
     * whatever it had lying around if nothing fresh comes in time. The receiver that runs
     * this has ten seconds in all, so the wait is short and the fallback is the point.
     */
    private suspend fun readFix(precise: Boolean): Fix? {
        val priority = if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val fresh = withTimeoutOrNull(FIX_TIMEOUT_MS) {
            val cancel = CancellationTokenSource()
            try {
                runCatching { fused.getCurrentLocation(priority, cancel.token).await() }
                    .onFailure { Log.w(TAG, "current location failed", it) }
                    .getOrNull()
            } finally {
                cancel.cancel()
            }
        }
        val location = fresh ?: runCatching { fused.lastLocation.await() }.getOrNull() ?: return null
        return location.toFix()
    }

    private fun Location.toFix() = Fix(
        lat = latitude,
        lng = longitude,
        // No accuracy is a fix from a provider that will not say: treat it as very rough.
        accuracyM = if (hasAccuracy()) accuracy.toDouble() else UNKNOWN_ACCURACY_M,
        at = Instant.ofEpochMilli(time),
    )

    private fun scheduleAt(at: Instant, gapM: Double? = null) {
        plannedAt = at
        // A schedule with no plan behind it (a sync, a blind retry) knows of no line to be near,
        // and a stir has nothing to judge itself against until the next real look.
        plannedGapM = gapM
        val intent = pendingIntent()
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        runCatching {
            if (exact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
            }
        }.onFailure { Log.w(TAG, "could not set the next look", it) }
    }

    private fun cancel() {
        runCatching { alarms.cancel(pendingIntent()) }
        motion.stop()
        plannedAt = null
        plannedGapM = null
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, PlaceCheckReceiver::class.java).setAction(PlaceCheckReceiver.ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "RwilcoGeo"
        const val FIX_TIMEOUT_MS = 7_000L
        const val UNKNOWN_ACCURACY_M = 500.0
        val SOON: Duration = Duration.ofSeconds(5)
        val NO_FIX_RETRY: Duration = Duration.ofMinutes(10)
    }
}

/** A Play Services task as a suspension; failure is a null result, the caller logs it. */
private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) continuation.resume(task.result) else continuation.resume(null)
    }
}
