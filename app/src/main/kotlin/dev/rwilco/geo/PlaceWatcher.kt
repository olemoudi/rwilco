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
import dev.rwilco.diag.Diag
import dev.rwilco.data.SettingsStore
import dev.rwilco.model.GeofenceIds
import dev.rwilco.model.dayShape
import dev.rwilco.model.watchedCircles
import dev.rwilco.model.Crossing
import dev.rwilco.model.Fix
import dev.rwilco.model.FixTier
import dev.rwilco.model.Movement
import dev.rwilco.model.NoteKind
import dev.rwilco.model.PlaceWatchPolicy
import dev.rwilco.model.PlaceWatchState
import dev.rwilco.model.Transition
import dev.rwilco.model.Trigger
import dev.rwilco.model.WatchPlan
import dev.rwilco.model.WatchNote
import dev.rwilco.model.WatchedPlace
import dev.rwilco.model.answersFor
import dev.rwilco.model.blindRetry
import dev.rwilco.model.insideAfter
import dev.rwilco.model.settlesFirstSideOf
import dev.rwilco.model.busyNotice
import dev.rwilco.model.counted
import dev.rwilco.model.counting
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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

    /**
     * Whether the last look had the phone inside the circle that set the cadence, and how many
     * stirs since have come to nothing. In memory for the same reason as the two above: the
     * sensor speaks only for the process that armed it, so a streak counted against its stirs
     * is valid exactly as long as the registration behind them.
     */
    @Volatile
    private var plannedInside: Boolean = false

    @Volatile
    private var stirStreak: Int = 0

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
            store.write(current.copy(inside = current.inside.filterKeys { it in watch.remembered }, nextCheckAt = gate, dwelling = emptyMap()))
            if (gate == null) cancel() else scheduleAt(gate)
            return@withLock
        }
        val ids = places.mapTo(HashSet()) { it.id }
        // A listening circle is not gone, it is waiting, and what the last fix said about it
        // still stands: this is a change of list, not a look. (Every resting circle is one of
        // them, so `remembered` has nothing to add here.) Only the circles that asked for a fix
        // decide whether one is worth taking now.
        val known = (watch.asking + watch.listening).mapTo(HashSet()) { it.id }
        val now = clock.instant()
        val judged = current.inside.filterKeys { it in known } + baselined(watch, current.inside, current.lastFix, now)
        // Whichever comes first: what the store remembers, and what a stir already pulled
        // forward in this process (which the store will not have caught up with yet).
        val pending = listOfNotNull(current.nextCheckAt, plannedAt).filter { it > now }.minOrNull()
        val at = if (pending != null && ids.all { it in judged }) pending else now + SOON
        // A count belongs to a circle that is being asked for a position. A circle that has gone
        // — edited, dealt with, its rate changed, and so a new id ([GeofenceIds]) — takes its
        // count with it, and so does one whose gate has shut: a listener must not ring, and a
        // count is a ring waiting to happen.
        store.write(current.copy(inside = judged, nextCheckAt = at, dwelling = current.dwelling.filterKeys { it in ids }))
        scheduleAt(at)
    }

    /**
     * Doorway circles nobody has judged yet, judged against the fix already in hand.
     *
     * A circle with no entry in `inside` pulls the next look to five seconds from now, because
     * an unjudged circle is one the watch cannot say anything about. That is right the first
     * time and wasteful every time after: a circle's id carries its own geometry
     * ([GeofenceIds]), so dragging a pin or a radius makes a new circle and buys another fix —
     * and writing place reminders is exactly when somebody does that, over and over, which is
     * how an afternoon of editing reads as a watch that will not stop looking.
     *
     * The fix in hand answers it, when there is one recent enough to have been taken for this
     * look ([PlaceWatchPolicy.CACHE_MAX_AGE]): baselining is what that first look was *for*,
     * and [insideAfter] with no history is the same arithmetic whether the fix is a second old
     * or four minutes. Not the ninety minutes a fix goes on speaking for a *moment*
     * ([Fix.speaksFor]): "al llegar a casa", written on the sofa at seven with the last fix
     * taken at the office at six, was baselined *outside* — and the look five seconds later
     * found the phone inside and rang for an arrival that happened before the reminder did.
     *
     * **Doorways only.** A place read as a state — "mientras esté en casa" — rings on its first
     * judgement if it finds itself true, and that ring is the point of it (ARCHITECTURE.md, "a
     * place is a state, and the doorway is the exception"). Only a look may ring, so a state
     * still buys its own; what is saved here is the case that was silent anyway.
     */
    private fun baselined(watch: Watching, judged: Map<String, Boolean>, fix: Fix?, now: Instant): Map<String, Boolean> {
        if (fix == null || Duration.between(fix.at, now).abs() > PlaceWatchPolicy.CACHE_MAX_AGE) return emptyMap()
        return (watch.asking + watch.listening)
            // And not off a fix too vague to settle the circle at all: that answer leans
            // towards "already there", which is a doorway silenced for the whole visit.
            .filter { it.onCrossing && it.id !in judged && fix.settlesFirstSideOf(it) }
            .associate { place -> place.id to insideAfter(null, place, fix) }
    }

    /**
     * Which side of [placeId]'s line the phone is on, said by somebody who knows: the snooze
     * that has just drawn a circle around the phone, or read its position against a saved
     * place. Under the lock, so a `sync` in flight cannot write its pruned memory over it —
     * and called only once the row carries the circle, so every sync after this one keeps it.
     */
    suspend fun remember(placeId: String, transition: Transition) = lock.withLock {
        store.write(store.read().remembering(placeId, transition))
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
        // A crossing must not be judged — and above all not WRITTEN DOWN — off a read that
        // failed. live == null has a meaning of its own ("this circle is not worth anything
        // right now"), and a database or settings read that threw used to wear it: the side
        // went into the memory, NOTHING came back, and a real arrival was consumed for good.
        // Left unwritten instead, the next look re-derives the crossing from fix-versus-memory
        // and rings late rather than never.
        val lively = runCatching { places().firstOrNull { it.id == placeId } }
        val failed = lively.exceptionOrNull()
        if (failed != null) {
            Log.e(TAG, "could not judge a crossing at $placeId", failed)
            Diag.note("geo", "crossing unjudged (${failed::class.simpleName}); left for the next look")
            return@withLock Crossing.NOTHING
        }
        val live = lively.getOrNull()
        // A circle that asks for the doorway and has already rung is owed the other side before
        // it rings again: the crossing has to be one the app has seen the far side of, and what
        // it cannot vouch for is not news. The first ring keeps the benefit of the doubt. A
        // circle read as a state needs none of this — what stops it ringing twice is the round
        // it has already rung in (`ReminderFiring`), not the geometry.
        val reminder = repository.get(GeofenceIds.reminderIdOf(placeId))
        // What this circle is called. A crossing can arrive for a circle the watch is not
        // spending anything on — one ticked off, one whose hours are shut, one whose reminder
        // has just been dealt with — and `live` is null for all of them, so the label used to
        // fall back to the id itself: a UUID and a pin, printed on a screen a person reads, in
        // place of "Club". Null is the honest answer when even the rule is gone; a line with no
        // name says less and lies about nothing.
        val label = live?.label ?: reminder?.rules?.getOrNull(GeofenceIds.triggerIndexOf(placeId) ?: -1)
            ?.let { (it.trigger as? Trigger.Location)?.label }
        // Strict is about THIS circle having rung, not the reminder: a sibling's nine o'clock
        // ring must not hold the office doorway to the second-ring rule, and the circle a
        // snooze waits at has never rung at all — held strictly with no side yet seen, the
        // first arrival home was dropped and the reminder went quiet for good.
        val strict = live?.onCrossing == true && !GeofenceIds.isSnooze(placeId) &&
            reminder?.lastFiredAt != null && reminder.lastFiredRule == GeofenceIds.triggerIndexOf(placeId)
        val arrived = transition == Transition.ENTER
        if (!crossingIsNews(state, placeId, transition, now, strict = strict)) {
            Log.i(TAG, "geofence says $transition at $placeId, but we were already there")
            // `inside` is what this watch already believed and `reported` is what the system said.
            // Under `strict` the belief is null and the claim is the only thing the line can say.
            log.note(WatchNote(at = now, kind = NoteKind.ECHO, place = label, lat = live?.lat, lng = live?.lng, radiusM = live?.radiusM, inside = state.inside[placeId], reported = arrived))
            return@withLock Crossing.NOTHING
        }
        // **A rate turns the crossing into the start of a count.** "Al llegar a casa, y cuando
        // lleve diez minutos allí" is not rung by the doorstep, so the fence's word — which is
        // the prompt eye and usually the first one here — opens the count instead of ringing,
        // and the count is what rings (`stepDwell`). Without this the side would be written down
        // and the next look would find no crossing left to report, so a count that should have
        // started at the door would never start at all.
        val awaited = live != null && live.transition == transition
        val rate = live?.dwell?.takeIf { awaited && live.crossing != Crossing.NOTHING }
        val remembered = state.remembering(placeId, transition)
        store.write(if (rate != null) remembered.counting(placeId, now) else remembered)
        // Written down either way; acted on only for the crossing the rule waits for, and only
        // while the circle is worth watching at all — not resting, not outside its hours. What
        // acting on it means is the circle's own to say: a place under "todos" that has already
        // been ticked off is waiting for the crossing that takes that tick back. Decided before
        // the line is written, because the line is what says whether anything came of it: a
        // crossing that fell on the floor and one that rang the phone read the same otherwise.
        val crossing = if (awaited && rate == null) live!!.crossing else Crossing.NOTHING
        log.note(
            WatchNote(
                at = now, kind = NoteKind.FENCE, place = label, lat = live?.lat, lng = live?.lng, radiusM = live?.radiusM,
                inside = arrived, reported = arrived, acted = crossing != Crossing.NOTHING,
                // On a crossing that started one, this is what the line is really about.
                dwellS = rate?.seconds, heldS = rate?.let { 0L },
            ),
        )
        crossing
    }

    /**
     * The system says the wait after an arrival is over: a `GEOFENCE_TRANSITION_DWELL`, which
     * only a fence behind a rate ever asks for.
     *
     * This is the free half of counting a rate. Play Services times the same wait out of signals
     * this app never sees and at no cost here, and when it answers first the watch's own four
     * positions are never spent. It is *stricter* than the count below it — the system resets its
     * own timer on any leaving, where the watch tolerates a third of the rate — so a loitering it
     * reports is a stay by anybody's reckoning.
     *
     * **A count still running is what it needs to find.** Not [crossingIsNews], which is five
     * minutes wide: with a rate of an hour the two eyes can finish a quarter of an hour apart and
     * the second one would ring a second time. Whichever finishes the count clears it, so the
     * other arrives at nothing. That also leaves the watch's own count as the one authority on
     * *giving up*: it measures against the circle somebody drew, with this app's hysteresis, and
     * a loitering that arrives after it has already decided the phone left is about a different
     * line.
     */
    suspend fun acceptDwell(placeId: String): Crossing = lock.withLock {
        val state = store.read()
        val now = clock.instant()
        val lively = runCatching { places().firstOrNull { it.id == placeId } }
        val failed = lively.exceptionOrNull()
        if (failed != null) {
            Log.e(TAG, "could not judge a loitering at $placeId", failed)
            Diag.note("geo", "loitering unjudged (${failed::class.simpleName}); left for the next look")
            return@withLock Crossing.NOTHING
        }
        val live = lively.getOrNull()
        val rate = live?.dwell
        val running = state.dwelling[placeId]
        if (live == null || rate == null || running == null) {
            Log.i(TAG, "the system timed $placeId, but no count is running")
            log.note(WatchNote(at = now, kind = NoteKind.ECHO, place = live?.label, lat = live?.lat, lng = live?.lng, radiusM = live?.radiusM, inside = state.inside[placeId], reported = true))
            return@withLock Crossing.NOTHING
        }
        // The side goes down too: a loitering is an arrival the app may not have seen, and the
        // memory is what the next crossing is judged against.
        store.write(state.remembering(placeId, Transition.ENTER).counted(placeId))
        Log.i(TAG, "the system says ${rate.toMinutes()} min at $placeId")
        log.note(
            WatchNote(
                at = now, kind = NoteKind.LOITER, place = live.label, lat = live.lat, lng = live.lng, radiusM = live.radiusM,
                inside = true, reported = true, acted = live.crossing != Crossing.NOTHING,
                dwellS = rate.seconds, heldS = rate.seconds,
            ),
        )
        live.crossing
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
            // And a count with nothing left looking at it is a count that cannot finish.
            if (gate == null) {
                store.write(current.copy(inside = forgotten, dwelling = emptyMap()))
                cancel()
            } else {
                Log.i(TAG, "nothing worth a fix until the hours open")
                store.write(current.copy(inside = forgotten, nextCheckAt = gate, tier = FixTier.BALANCED, dwelling = emptyMap()))
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
            // A rest takes no fix, so it says nothing about the blind streak: kept, or a run of
            // blind looks backing off 10/20/40 min was reset to ten by the sofa.
            store.write(rest.state.copy(nextCheckAt = at, blindStreak = before.blindStreak))
            scheduleAt(at, rested.gapM, inside = rest.state.inside[rested.nearest.id] == true)
            Log.i(TAG, "nothing has moved; no fix taken, next look in ${Duration.between(now, at).toMinutes()} min")
            write(NoteKind.REST, now, rest.state, rested, rest.movement, charge)
            return
        }
        // How wide the question this look is asking is: from the fix the last plan was drawn on
        // to the moment that plan chose to look again. Not "time left until now", which by the
        // time the alarm has fired is zero — and not the plan's own wait, which nothing stores;
        // this is the span the watch has actually been away from a fresh reading, which is the
        // same number for an approach and larger for a watch that has been resting, both right.
        val plannedWait = before.lastFix?.let { last -> before.nextCheckAt?.let { Duration.between(last.at, it) } }
            ?.takeIf { !it.isNegative }
        val reading = readFix(before.tier, plannedWait, before.lastGapM)
        val fix = reading?.fix
        val cached = reading?.cached == true
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
        // A look that found something is a look the stirring was right about; one that found the
        // phone where it left it is not, and the next stir is worth less. See [stirredWait].
        if (step.events.isNotEmpty() || step.state.inside != before.inside) stirStreak = 0
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
        scheduleAt(at, plan.gapM, inside = step.state.inside[plan.nearest.id] == true)
        Log.i(TAG, "${plan.gapM.toInt()} m from ${plan.nearest.label}; next look in ${Duration.between(now, at).toMinutes()} min (${plan.tier})")
        write(if (cached) NoteKind.CACHE else NoteKind.FIX, now, step.state, plan, step.movement, charge)
        val what = places.associate { it.id to it.crossing }
        // The crossings are the point of the look and the last thing in it, and `inside` above
        // already says they happened: a crossing this coroutine is cut off before handing on —
        // the receiver's budget runs out around the second ring of a look that took its full
        // seven seconds on a fix — is one no later look can report again. So they go out
        // whatever happens to the coroutine, and one that fails does not take the rest with it.
        withContext(NonCancellable) {
            // **A rate nothing could measure is worth a sentence.** The battery has the last word
            // on how often this watch looks ([batteryFloor]), and under it a ten-minute stay
            // simply cannot be timed: the looks arrive an hour apart, the vouched minutes never
            // add up, and the count gives up. Nothing rang and nothing was wrong — which is
            // exactly the failure nobody can see, so it is said out loud once, here.
            for (place in step.unmeasured) {
                Log.w(TAG, "gave up timing ${place.dwell?.toMinutes()} min at ${place.label}")
                log.note(
                    WatchNote(
                        at = now, kind = NoteKind.UNMEASURED, place = place.label,
                        lat = place.lat, lng = place.lng, radiusM = place.radiusM,
                        charge = charge?.let { (it * 100).roundToInt() },
                        dwellS = place.dwell?.seconds,
                    ),
                )
                place.dwell?.let { runCatching { WatchNotices.notifyUnmeasured(context, place.label, it, charge) } }
            }
            for (event in step.events) {
                val reminderId = GeofenceIds.reminderIdOf(event.placeId)
                val ruleIndex = GeofenceIds.triggerIndexOf(event.placeId)
                Log.i(TAG, "watch saw ${event.transition} at ${event.placeId}")
                runCatching {
                    if (what[event.placeId] == Crossing.TAKES_BACK && ruleIndex != null) {
                        firing.untick(reminderId, ruleIndex)
                    } else if (GeofenceIds.isSnooze(event.placeId)) {
                        firing.fire(reminderId, viaSnoozePlace = true)
                    } else {
                        firing.fire(reminderId, ruleIndex = ruleIndex)
                    }
                }.onFailure { Log.e(TAG, "handing on a crossing at ${event.placeId} failed", it) }
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
                lat = plan?.nearest?.lat,
                lng = plan?.nearest?.lng,
                radiusM = plan?.nearest?.radiusM,
                inside = plan?.nearest?.let { state.inside[it.id] },
                speedMps = movement.speedMps,
                movedM = movement.movedM,
                sensed = movement.sensed,
                stillStreak = state.stillStreak,
                charge = charge?.let { (it * 100).roundToInt() },
                accuracyM = state.lastFix?.accuracyM?.roundToInt(),
                tier = plan?.tier ?: FixTier.BALANCED,
                // A count in progress on the circle that set the cadence — which, while one is
                // running, is nearly always the circle counting: it asks for the shortest wait
                // there is. Everything else on this line is about `plan.nearest` too.
                dwellS = plan?.nearest?.dwell?.seconds,
                heldS = plan?.nearest?.let { state.dwelling[it.id]?.heldMs?.div(1_000) },
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
     * **From inside a circle it also has to be going somewhere.** Significant motion means the
     * phone's location changed, and a kitchen is a change of location — so a phone being lived
     * with inside its own place stirs every few minutes all evening, and every one of those used
     * to buy a look at five minutes' notice: twelve fixes an hour, which is what this whole case
     * was rebuilt to stop costing. So stirs from inside are counted, and each one a look then
     * finds on the same side of the same line doubles the next one's notice ([stirredWait]),
     * back up to the half hour the case started at. From *outside* a line nothing is counted: a
     * phone that has settled and then sets off is precisely what this is for.
     *
     * Runs off the sensor's delivery thread ([MotionSensor] hands it to a coroutine), so the
     * two binder calls and the log line it writes are nowhere near the main looper.
     */
    private suspend fun stirred() = lock.withLock {
        val planned = plannedAt ?: return@withLock
        val gap = plannedGapM ?: return@withLock
        if (gap >= PlaceWatchPolicy.NEAR_M) return@withLock
        val now = clock.instant()
        val streak = if (plannedInside) stirStreak else 0
        val at = now + stirredWait(battery.remaining(), streak)
        if (planned <= at) return@withLock
        if (plannedInside) stirStreak++
        Log.i(TAG, "the phone stirred ${gap.toInt()} m from a line; looking sooner")
        scheduleAt(at, gap)
        // Its own line in the log, but not through `write`: moving an alarm is not a poll, and
        // counting it as one would have the watch complain about the thing that saves it work.
        log.note(WatchNote(at = now, kind = NoteKind.STIR, waitS = Duration.between(now, at).seconds, gapM = gap, sensed = true))
    }

    /**
     * One fix from the fused provider at the tier the last plan decided on, or whatever it had
     * lying around if nothing fresh comes in time. The receiver that runs this has ten seconds
     * in all, so the wait is short and the fallback is the point.
     *
     * The cheapest reading is the one already taken. Before any radio is spent the provider's
     * own last position is read — it is kept warm by whatever else on the phone asks for a
     * position, at no cost to this app — and if it answers the question this look was going to
     * ask ([Fix.answersFor]: young next to the planned wait, and its doubt short of the line it
     * has to judge) then that is the look, and it cost nothing. [cached] is handed back so the
     * caller can say so in the log, because a saving counted as a poll is a saving nobody sees.
     */
    private suspend fun readFix(tier: FixTier, plannedWait: Duration?, gapM: Double?): Reading? {
        val now = clock.instant()
        val held = runCatching { fused.lastLocation.await() }.getOrNull()?.toFix()
        if (held != null && plannedWait != null && held.answersFor(now, plannedWait, gapM)) {
            return Reading(held, cached = true)
        }
        val priority = when (tier) {
            FixTier.PRECISE -> Priority.PRIORITY_HIGH_ACCURACY
            FixTier.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            FixTier.COARSE -> Priority.PRIORITY_LOW_POWER
        }
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
        val location = fresh?.toFix() ?: held ?: return null
        return Reading(location, cached = false)
    }

    /** A fix, and whether getting it cost any radio. */
    private data class Reading(val fix: Fix, val cached: Boolean)

    private fun Location.toFix() = Fix(
        lat = latitude,
        lng = longitude,
        // No accuracy is a fix from a provider that will not say: treat it as very rough.
        accuracyM = if (hasAccuracy()) accuracy.toDouble() else UNKNOWN_ACCURACY_M,
        at = Instant.ofEpochMilli(time),
    )

    /**
     * Arm the next look. [inside] is whether the plan behind it had the phone inside the circle
     * that set the cadence, which is what decides whether a stir counts against a streak.
     *
     * **Exact only where exactness is real.** Every look used to be an exact allow-while-idle
     * alarm, including the hourly one, and an exact alarm is one the system may not batch with
     * anybody else's: a wake-up of its own, every time. Doze already holds allow-while-idle
     * alarms to one per nine minutes, so above a quarter of an hour the exactness was buying
     * nothing that the phone was going to honour anyway — while below it, walking up to a door,
     * a two-minute look arriving three minutes late is a place reminder that missed the door.
     * So: exact under [EXACT_UNDER], batchable above it.
     */
    private fun scheduleAt(at: Instant, gapM: Double? = null, inside: Boolean = false) {
        val intent = pendingIntent()
        val soon = Duration.between(clock.instant(), at) < EXACT_UNDER
        val exact = soon && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms())
        runCatching {
            if (exact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
            }
        }.onSuccess {
            // Planned only once the alarm exists. Written first, a set that threw left a plan
            // with nothing behind it, and recover() — the one thing that keeps the chain alive
            // — stood down because a look was "coming".
            plannedAt = at
            // A schedule with no plan behind it (a sync, a blind retry) knows of no line to be
            // near, and a stir has nothing to judge itself against until the next real look.
            plannedGapM = gapM
            plannedInside = inside
        }.onFailure {
            plannedAt = null
            plannedGapM = null
            plannedInside = false
            Log.w(TAG, "could not set the next look", it)
            Diag.note("geo", "could not set the next look: ${it::class.simpleName}")
        }
    }

    private fun cancel() {
        runCatching { alarms.cancel(pendingIntent()) }
        motion.stop()
        plannedAt = null
        plannedGapM = null
        plannedInside = false
        stirStreak = 0
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

        /** Under this a look is armed exactly; above it, batchably. See [scheduleAt]. */
        val EXACT_UNDER: Duration = Duration.ofMinutes(15)
    }
}
