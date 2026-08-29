@file:UseSerializers(InstantSerializer::class)

package dev.rwilco.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Duration
import java.time.Instant

/*
 * The place watch's own account of itself.
 *
 * Everything in `PlaceWatch.kt` decides how often to ask the phone where it is, from half a
 * dozen factors that argue with each other, and the only thing anybody can see from outside is
 * the battery graph a day later. This is the middle: one line per look, saying what the watch
 * did, what it cost, and — the part that matters — every number it decided from. Nothing here
 * changes what the watch does; it only writes down why.
 */

/** What one look came to. */
@Serializable
enum class NoteKind {
    /** A fix was read. The expensive one. `tier` says how much radio it woke. */
    FIX,

    /**
     * A look answered out of the fix the phone already had: no radio at all. Deliberately its
     * own kind and not a cheaper [FIX], because the whole point of it is that it did not poll,
     * and a log that called it one would hide the saving inside the number it saves.
     */
    CACHE,

    /** A look skipped: the sensor and the last two fixes agreed there was nothing to see. */
    REST,

    /** A look that got nothing worth having — location off, or a provider that never answered. */
    BLIND,

    /** The motion sensor fired and pulled the next look forward. No fix taken here. */
    STIR,

    /** Play Services reported a crossing, and the watch let it through. */
    FENCE,

    /** Play Services reported a crossing the watch's own last fix says never happened. */
    ECHO,
}

/**
 * One line of the account: when, what, and the factors it came out of. Every field but [at] and
 * [kind] is optional, because a [NoteKind.BLIND] look knows nothing and a [NoteKind.FENCE] one
 * knows something else.
 */
@Serializable
data class WatchNote(
    val at: Instant,
    val kind: NoteKind,
    /** The wait this look planned, in seconds. */
    val waitS: Long? = null,
    /** Metres to the nearest line, and whose. */
    val gapM: Double? = null,
    val place: String? = null,
    /**
     * Which circle, as geometry rather than as somebody's word for it.
     *
     * The label is kept for the screen and never reaches the report — a place name is somebody's
     * life, not a fact about a bug. The circle is a fact about a bug, and without it a line of
     * three geofences crossing at once says only that *something* was crossed: chasing a real
     * false alarm meant guessing which of four co-located circles the log was talking about. The
     * report rounds these the same way it rounds a reminder's, so the redaction stays in one
     * place.
     */
    val lat: Double? = null,
    val lng: Double? = null,
    val radiusM: Int? = null,
    /** Inside that place, at this look. */
    val inside: Boolean? = null,
    val speedMps: Double? = null,
    /** Metres since the previous fix, less the doubt in both. */
    val movedM: Double? = null,
    /** The motion sensor's word; null when it was not listening. */
    val sensed: Boolean? = null,
    val stillStreak: Int = 0,
    /** Battery left, 0..100; null while charging or when the phone would not say. */
    val charge: Int? = null,
    /**
     * How wide the doubt on this fix was, in metres.
     *
     * Not diagnostics for their own sake: it is the number that says whether a circle could be
     * judged at all. A fifty-metre circle read off a fix accurate to eighty is not a judgement,
     * and without this in the log a false ring and a real one look identical afterwards — which
     * is exactly where an evening went once.
     */
    val accuracyM: Int? = null,
    /** How much radio was asked for. A note written before there were three reads back as balanced. */
    val tier: FixTier = FixTier.BALANCED,
) {
    /** Whether this look actually spent radio. The whole point of the other kinds is that they did not. */
    val isPoll: Boolean get() = kind == NoteKind.FIX || kind == NoteKind.BLIND

    /**
     * The place's name, and nothing that is not one.
     *
     * [place] was a geofence id for a while whenever a crossing arrived for a circle the watch
     * had stopped spending on, and a log outlives the build that wrote it: two hundred lines of
     * somebody's afternoon still hold those ids. Everything that reads a name reads this one, so
     * a line whose name is an id has no name — which is what it always meant.
     */
    val placeName: String? get() = place?.takeUnless { GeofenceIds.looksLikeId(it) }
}

/**
 * The account, and what has already been said about it. Persisted as JSON in its own store;
 * every field has a default, so an older blob reads back as an emptier one and never as a crash.
 */
@Serializable
data class WatchLog(
    val notes: List<WatchNote> = emptyList(),
    /** The last time the "this is looking too often" notice went out. */
    val lastNoticeAt: Instant? = null,
)

/** How many entries the log keeps: a couple of days of a quiet watch, half of one of a busy one. */
const val WATCH_LOG_KEEP = 200

/** The newest line, and the oldest ones dropped. Newest first, which is how it is read. */
fun WatchLog.noting(note: WatchNote): WatchLog = copy(notes = (listOf(note) + notes).take(WATCH_LOG_KEEP))

/**
 * The same account with one line per *thing that happened*, for the screen a person reads.
 *
 * A place named by six rules is six geofences, and walking through it is six crossings inside
 * the same second — six identical lines saying "Llegaste a Club", which is the log telling the
 * truth and saying nothing. They are one event to whoever walked through the door, so runs of
 * crossings of the same circle, the same way, within [within] collapse to the first of them.
 *
 * Only crossings, and only the same circle: two different places crossed at once is two things
 * that happened, and a look is never the same event as the look before it. Nothing is dropped
 * from the store — the diagnostics report still has all six, which is where the fact that there
 * were six is worth having.
 */
fun List<WatchNote>.asEvents(within: Duration = Duration.ofMinutes(1)): List<WatchNote> =
    filterIndexed { index, note ->
        if (note.kind != NoteKind.FENCE && note.kind != NoteKind.ECHO) return@filterIndexed true
        // Newest first, so the one kept is the first of the run and the rest fall in behind it.
        val previous = getOrNull(index - 1) ?: return@filterIndexed true
        !(previous.kind == note.kind &&
            previous.inside == note.inside &&
            previous.lat == note.lat &&
            previous.lng == note.lng &&
            previous.radiusM == note.radiusM &&
            Duration.between(note.at, previous.at).abs() <= within)
    }

/** Looks that actually spent radio since [since]. */
fun List<WatchNote>.pollsSince(since: Instant): Int = count { it.isPoll && it.at >= since }

/**
 * What a stretch of the log came to, in one line: how many looks, what each kind of them cost,
 * and which circle was setting the pace.
 *
 * The account underneath is one line per look and reads like one — honest, and unreadable as an
 * answer to "is this thing costing me anything?". That question has a shape: a handful of numbers
 * that can be compared with yesterday's. [pacedBy] is the one that says *why*, because the
 * cadence is always some single circle's ask and knowing which one is the difference between a
 * watch that is busy and a watch that is busy for a reason.
 */
data class WatchTally(
    val looks: Int = 0,
    /** Fixes bought with the wifi/cell blend. */
    val network: Int = 0,
    /** Fixes that woke the satellites. */
    val gps: Int = 0,
    /** Fixes bought from the towers alone. */
    val coarse: Int = 0,
    /** Looks answered out of a fix already in hand. */
    val cached: Int = 0,
    /** Looks skipped: the sensor and the last two fixes agreed there was nothing to see. */
    val rested: Int = 0,
    /** Looks that got nothing worth having. */
    val blind: Int = 0,
    /** The circle that set the cadence most often, and on how many of these looks. */
    val pacedBy: String? = null,
    val pacedByLooks: Int = 0,
)

fun List<WatchNote>.tally(since: Instant): WatchTally {
    // Crossings and stirs are things that happened to the watch, not looks it took.
    val looks = filter { it.at >= since && it.kind != NoteKind.FENCE && it.kind != NoteKind.ECHO && it.kind != NoteKind.STIR }
    if (looks.isEmpty()) return WatchTally()
    val fixes = looks.filter { it.kind == NoteKind.FIX }
    val paced = looks.mapNotNull { it.placeName }.groupingBy { it }.eachCount().maxByOrNull { it.value }
    return WatchTally(
        looks = looks.size,
        network = fixes.count { it.tier == FixTier.BALANCED },
        gps = fixes.count { it.tier == FixTier.PRECISE },
        coarse = fixes.count { it.tier == FixTier.COARSE },
        cached = looks.count { it.kind == NoteKind.CACHE },
        rested = looks.count { it.kind == NoteKind.REST },
        blind = looks.count { it.kind == NoteKind.BLIND },
        pacedBy = paced?.key,
        pacedByLooks = paced?.value ?: 0,
    )
}

/**
 * Whether the watch has been looking too often, and nobody has been told yet.
 *
 * [PlaceWatchPolicy.BUSY_POLLS] an hour is three fifths of what the watch can physically do
 * ([PlaceWatchPolicy.MIN_WAIT] is two minutes, so thirty an hour is the ceiling). Reaching it
 * means over half the hour spent at the fastest cadence there is, which takes a very long
 * approach on foot — the ordinary one is a fraction of that — or something the app is getting
 * wrong. Either way it is worth interrupting somebody about, once.
 *
 * Once, and quietly: the window is an hour and so is the silence after a notice, because a
 * second notice inside the same hour is about the same hour.
 */
fun WatchLog.busyNotice(now: Instant): Boolean {
    if (notes.pollsSince(now - PlaceWatchPolicy.BUSY_WINDOW) <= PlaceWatchPolicy.BUSY_POLLS) return false
    val last = lastNoticeAt ?: return true
    return Duration.between(last, now) >= PlaceWatchPolicy.BUSY_WINDOW
}
