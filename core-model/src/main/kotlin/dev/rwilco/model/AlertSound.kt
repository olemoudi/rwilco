package dev.rwilco.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration

/**
 * The chimes the app brings with it.
 *
 * All four are built the way a car builds one: two or three short tones in the band the ear is
 * most sensitive to, with an envelope soft enough that nothing clicks. A car does not shout —
 * it plays something short and stops, and you notice it at a volume nobody minds. The audio is
 * synthesised rather than sourced (`scripts/chimes.py`), so it is the app's own and licensed by
 * nobody.
 */
enum class Chime {
    /** A door left open: one soft tone struck four times, unhurried. The most insistent here. */
    ALERT,

    /** Ding-dong, falling a fourth: the seat-belt reminder. Says "look at me" and nothing more. */
    TWO_TONE,

    /** Two lower still, for a quiet room at night or an ear that finds anything above it bright. */
    LOW,

    /** One warm note with a long tail: the quietest thing here, for a phone on a desk. */
    SOFT,
}

/** What a reminder sounds like. */
@Serializable
sealed interface AlertSound {

    /** The phone's own alarm tone, which is what this app used before it had any of its own. */
    @Serializable
    @SerialName("system")
    data object System : AlertSound

    /** One of [Chime]. */
    @Serializable
    @SerialName("chime")
    data class Bundled(val chime: Chime) : AlertSound

    /**
     * A file somebody picked themselves. [uri] is a content Uri the app has taken lasting
     * permission on; [label] is what to call it on screen, because a content Uri is not a name.
     */
    @Serializable
    @SerialName("custom")
    data class Custom(val uri: String, val label: String) : AlertSound
}

/**
 * A short, stable token for a sound, for anything that has to key something by it.
 *
 * A notification channel's sound is fixed the moment the channel is created, so a change of
 * sound has to mean a change of channel id — the same reason the vibration rhythm is in there.
 * The custom one is hashed because a content Uri is long, and because it is nobody's business
 * what somebody's file is called once it has reached the system's channel list.
 */
val AlertSound.key: String
    get() = when (this) {
        AlertSound.System -> "sys"
        is AlertSound.Bundled -> "b${chime.ordinal}"
        is AlertSound.Custom -> "c%08x".format(uri.hashCode())
    }

/**
 * The shape of "hasta que reciba caso" (0.154.0, in the owner's words): each time it alerts, the
 * tone sounds [PLAYS] times back to back; it alerts again every [GAP_MINUTES]; and it alerts at
 * most [ROUNDS] times, the first included. Until 0.154.0 there were two numbers, and the first
 * was the rounds: each alert looped the tone for up to a minute, and "5 veces cada 5 min" read,
 * naturally, as five tones and then a five-minute rest, for ever.
 */
object SoundLimits {
    /** Times the tone sounds back to back each time it alerts. Past this is a siren. */
    val PLAYS = 1..20

    /** Minutes between two alerts. Under a minute is a stutter; past an hour it is a different alarm. */
    val GAP_MINUTES = 1..60

    /** How many times it alerts at most, the first included. One is a single alert. */
    val ROUNDS = 1..20

    const val DEFAULT_PLAYS = 5
    const val DEFAULT_GAP_MINUTES = 5
    const val DEFAULT_ROUNDS = 3
}

/**
 * How many times the tone sounds back to back on the alert screen: the insistent number for a
 * reminder that keeps asking, once for plain "sonido".
 */
fun AppSettings.tonesInARow(insistent: Boolean): Int = if (insistent) soundPlays.coerceIn(SoundLimits.PLAYS) else 1

/**
 * Which of the two tones a firing plays.
 *
 * The split is the action somebody ticked, not the way the noise happens to come out: "sonido"
 * says it once and stops, "hasta que reciba caso" comes back every few minutes until somebody
 * answers — and a tone that is right for the first is often wrong for the second. One you want
 * to notice; the other you are going to hear five times, so it had better be something you can
 * stand hearing five times.
 *
 * [AppSettings.insistentSound] null means there is no distinction, which is what everybody
 * starts with and what nothing changes on its own.
 */
fun AppSettings.soundFor(insistent: Boolean): AlertSound =
    if (insistent) insistentSound ?: alertSound else alertSound

fun AppSettings.soundFor(plan: FiringPlan): AlertSound = soundFor(plan.insistent)

/**
 * How long until the next alert, or null when there is not one.
 *
 * [alerted] counts the alerts already made, the first one included, so three are over once three
 * have gone out. The count is carried by the alarm that schedules the next one rather than
 * written down anywhere: a chain of alarms needs no memory, and a chain that is cancelled leaves
 * none behind.
 */
fun nextSoundIn(alerted: Int, rounds: Int, gapMinutes: Int): Duration? {
    if (alerted < 1) return Duration.ZERO
    if (alerted >= rounds.coerceIn(SoundLimits.ROUNDS)) return null
    return Duration.ofMinutes(gapMinutes.coerceIn(SoundLimits.GAP_MINUTES).toLong())
}
