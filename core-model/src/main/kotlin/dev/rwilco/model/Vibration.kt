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
 * [waveformFor] for why that matters more than it looks.
 */
data class Waveform(val timings: List<Long>, val amplitudes: List<Int>) {
    val totalMillis: Long get() = timings.sum()
}

object VibrationLimits {
    /**
     * The longest a reminder may hold the motor, and it is a hardware limit rather than a taste.
     *
     * A vibration motor is a coil driving a mass, and both get hot; a minute at full amplitude
     * is already the far end of what one is built to do in a stretch, and phones have burnt
     * theirs out on less. An alarm that has buzzed for a minute has also long since made its
     * point — an alarm clock stops eventually too — so nothing is lost by stopping there.
     */
    val LONGEST: Duration = Duration.ofMinutes(1)

    /** A buzz, and the quiet after it, for [VibrationRhythm.PULSED]. */
    const val PULSE_ON_MS = 500L
    const val PULSE_OFF_MS = 800L

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
    if (pattern.rhythm == VibrationRhythm.CONTINUOUS) {
        return Waveform(listOf(0L, cap), listOf(0, amplitude))
    }
    val timings = ArrayList<Long>()
    val amplitudes = ArrayList<Int>()
    timings += 0L
    amplitudes += 0
    var spent = 0L
    while (spent + VibrationLimits.PULSE_ON_MS <= cap) {
        timings += VibrationLimits.PULSE_ON_MS
        amplitudes += amplitude
        spent += VibrationLimits.PULSE_ON_MS
        // The last pause is dropped: a silence at the end of a pattern that is about to stop
        // anyway is a millisecond of nothing, and it is what would push the total over the cap.
        val pause = minOf(VibrationLimits.PULSE_OFF_MS, cap - spent)
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
