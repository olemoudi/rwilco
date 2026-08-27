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
    /** A fix was read. The expensive one. `precise` says whether it woke the GPS. */
    FIX,

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
    /** Whether the GPS was asked for. */
    val precise: Boolean = false,
) {
    /** Whether this look actually spent radio. The whole point of the other kinds is that they did not. */
    val isPoll: Boolean get() = kind == NoteKind.FIX || kind == NoteKind.BLIND
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

/** Looks that actually spent radio since [since]. */
fun List<WatchNote>.pollsSince(since: Instant): Int = count { it.isPoll && it.at >= since }

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
