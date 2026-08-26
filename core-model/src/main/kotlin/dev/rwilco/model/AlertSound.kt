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

object SoundLimits {
    /** Plays in one round, counting the first. One is not "insistent", and past this is a siren. */
    val PLAYS = 2..20

    /** Minutes between them. Under a minute is a stutter; past an hour it is a different alarm. */
    val GAP_MINUTES = 1..60

    const val DEFAULT_PLAYS = 5
    const val DEFAULT_GAP_MINUTES = 5
}

/**
 * How long until the next play, or null when there is not one.
 *
 * [played] counts what has already been heard, the first one included, so a round of five is
 * over once five have gone out. The count is carried by the alarm that schedules the next play
 * rather than written down anywhere: a chain of alarms needs no memory, and a chain that is
 * cancelled leaves none behind.
 */
fun nextSoundIn(played: Int, plays: Int, gapMinutes: Int): Duration? {
    if (played < 1) return Duration.ZERO
    if (played >= plays.coerceIn(SoundLimits.PLAYS)) return null
    return Duration.ofMinutes(gapMinutes.coerceIn(SoundLimits.GAP_MINUTES).toLong())
}
