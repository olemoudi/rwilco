package dev.rwilco.model

import kotlinx.serialization.Serializable
import java.time.Duration

/** How hard the motor is driven. */
enum class VibrationStrength { GENTLE, STRONG }

/** One long stretch, or a buzz-pause-buzz. */
enum class VibrationRhythm { CONTINUOUS, PULSED }

/**
 * What a reminder feels like.
 *
 * The default is what the app did before there was a choice — full strength, buzz and pause —
 * so nobody's phone changes character by updating.
 */
@Serializable
data class VibrationPattern(
    val strength: VibrationStrength = VibrationStrength.STRONG,
    val rhythm: VibrationRhythm = VibrationRhythm.PULSED,
)

/**
 * A vibration as the phone wants it: [timings] in milliseconds, alternating off and on and
 * starting with off, and one amplitude per timing (0 for the silences, 1..255 for the buzzes).
 *
 * Whole and finite, with no repeat count: the pattern is built long enough to last as long as
 * it is allowed to and not a millisecond longer, so the system stops it on its own. See
 * [waveformFor] for why that matters more than it looks. Even the continuous rhythm is a train
 * of buzzes rather than one long one — see [VibrationLimits.CONTINUOUS_BREATH_MS].
 */
data class Waveform(val timings: List<Long>, val amplitudes: List<Int>) {
    val totalMillis: Long get() = timings.sum()
}

object VibrationLimits {
    /**
     * The longest a reminder may hold the motor, and it is a hardware limit rather than a taste.
     *
     * A vibration motor is a coil driving a mass, and both get warm; the longer it is driven the
     * warmer, and the app has no way to ask how warm. So it stops asking after a minute — which
     * costs nothing, because an alarm that has buzzed for a minute has long since made its
     * point. An alarm clock stops eventually too.
     */
    val LONGEST: Duration = Duration.ofMinutes(1)

    /** A buzz, and the quiet after it, for [VibrationRhythm.PULSED]. */
    const val PULSE_ON_MS = 500L
    const val PULSE_OFF_MS = 800L

    /**
     * [VibrationRhythm.CONTINUOUS] is not one unbroken minute; it is these, back to back.
     *
     * Unbroken is the highest-power state the motor has, and a minute of it at full amplitude is
     * the hardest thing this app can ask of one. A gap this short is not a pause — an LRA takes
     * tens of milliseconds to spin down and back up, so most of it is swallowed by the actuator
     * itself and what is left reads as texture rather than as a stop — and it hands back a
     * sixteenth of the minute with the coil unpowered. Cheap insurance rather than a measured
     * fix: the honest protection is still the minute itself.
     */
    const val CONTINUOUS_ON_MS = 2_000L
    const val CONTINUOUS_BREATH_MS = 150L

    /** 1..255. Gentle is a phone in a pocket; strong is one on a table across the room. */
    const val STRONG_AMPLITUDE = 255
    const val GENTLE_AMPLITUDE = 110

    /** What a notification's channel buzzes, which is a announcement rather than an alarm. */
    const val NOTIFICATION_ON_MS = 400L
    const val NOTIFICATION_OFF_MS = 250L
}

val VibrationStrength.amplitude: Int
    get() = when (this) {
        VibrationStrength.STRONG -> VibrationLimits.STRONG_AMPLITUDE
        VibrationStrength.GENTLE -> VibrationLimits.GENTLE_AMPLITUDE
    }

/**
 * The waveform for a full-screen alert, built to run for exactly as long as it is allowed to.
 *
 * The obvious way to make an alert buzz until somebody answers is a repeating waveform, and it
 * is what this app did: repeat from index 0, stop it by hand later. The trouble is that "later"
 * is a promise the app has to keep — through a killed process, a crash, a stop() that never
 * ran — and what is on the other side of a broken promise is a motor buzzing until the battery
 * is flat. Building the whole minute up front hands that promise to the system instead: the
 * effect ends when it ends, whatever happens to the app in the meantime.
 *
 * [limit] is the cap; the pattern is filled up to it and never past it.
 */
fun waveformFor(pattern: VibrationPattern, limit: Duration = VibrationLimits.LONGEST): Waveform {
    val cap = limit.toMillis().coerceAtLeast(0L)
    val amplitude = pattern.strength.amplitude
    val (on, off) = when (pattern.rhythm) {
        VibrationRhythm.CONTINUOUS -> VibrationLimits.CONTINUOUS_ON_MS to VibrationLimits.CONTINUOUS_BREATH_MS
        VibrationRhythm.PULSED -> VibrationLimits.PULSE_ON_MS to VibrationLimits.PULSE_OFF_MS
    }
    // One builder for both rhythms: they are the same shape and differ only in how long the
    // buzz is and how much of a gap follows it. Continuous is a very long buzz and a gap short
    // enough to disappear into the motor's own spin-down.
    val timings = ArrayList<Long>()
    val amplitudes = ArrayList<Int>()
    timings += 0L
    amplitudes += 0
    var spent = 0L
    while (spent < cap) {
        // The last buzz is cut to whatever is left rather than dropped, so a pattern uses the
        // whole of the time it is given and not a second more.
        val buzz = minOf(on, cap - spent)
        timings += buzz
        amplitudes += amplitude
        spent += buzz
        val pause = minOf(off, cap - spent)
        if (pause <= 0L) break
        timings += pause
        amplitudes += 0
        spent += pause
    }
    return Waveform(timings, amplitudes)
}

/**
 * What a notification's channel buzzes: short, because a notification is an announcement and
 * not an alarm — the alarm is the full-screen alert, and it has [waveformFor].
 *
 * Only the rhythm survives here. A channel's pattern is a list of durations and nothing else:
 * Android gives no way to say how hard, so a gentle notification and a strong one are the same
 * notification. The strength is honoured where it can be.
 */
fun notificationPattern(pattern: VibrationPattern): List<Long> = when (pattern.rhythm) {
    VibrationRhythm.CONTINUOUS -> listOf(0L, 1_000L)
    VibrationRhythm.PULSED -> listOf(
        0L,
        VibrationLimits.NOTIFICATION_ON_MS,
        VibrationLimits.NOTIFICATION_OFF_MS,
        VibrationLimits.NOTIFICATION_ON_MS,
    )
}
