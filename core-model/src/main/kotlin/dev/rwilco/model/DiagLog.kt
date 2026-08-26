@file:UseSerializers(InstantSerializer::class)

package dev.rwilco.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Duration
import java.time.Instant

/**
 * The app's own account of what it did, for the times something did not happen.
 *
 * A reminder that never arrived leaves nothing behind: no crash, no error, an empty screen and
 * somebody's word that it was set. `Log.i` says plenty but lives in a logcat nobody has. So
 * this: one short line per decision that could be the one, kept in a ring the person can read,
 * copy and hand over. It is written for whoever has to fix it, which means it holds ids and
 * decisions and *not* what any reminder says — the words are never the bug.
 */
@Serializable
data class DiagNote(
    val at: Instant,
    /** Which part spoke: `fire`, `arm`, `show`, `vault`, `sys`, `update`. */
    val tag: String,
    val text: String,
)

@Serializable
data class DiagLog(val notes: List<DiagNote> = emptyList())

/** Enough to cover the week the age cap allows at a busy phone's rate, and not a byte more. */
const val DIAG_LOG_KEEP = 300

/** Past this a line is history rather than a symptom, and nobody is debugging last month. */
val DIAG_LOG_AGE: Duration = Duration.ofDays(7)

/** A line is a sentence, not a paragraph: anything longer is a decision badly described. */
const val DIAG_NOTE_LENGTH = 160

/** Within this, the same line again is the same line: a re-arm fires from four doors at once. */
val DIAG_REPEAT_WINDOW: Duration = Duration.ofMinutes(1)

/**
 * The newest line first, the oldest dropped, nothing older than [DIAG_LOG_AGE] kept — and a
 * line identical to the one above it, within [DIAG_REPEAT_WINDOW], replaces it instead of
 * piling on. One edit re-arms everything through several doors and each would write the same
 * sentence; four copies of it push out four lines that said something.
 */
fun DiagLog.noting(note: DiagNote): DiagLog {
    val trimmed = note.copy(text = note.text.take(DIAG_NOTE_LENGTH))
    val head = notes.firstOrNull()
    val repeat = head != null && head.tag == trimmed.tag && head.text == trimmed.text &&
        Duration.between(head.at, trimmed.at) < DIAG_REPEAT_WINDOW
    val rest = if (repeat) notes.drop(1) else notes
    val since = trimmed.at.minus(DIAG_LOG_AGE)
    return copy(notes = (listOf(trimmed) + rest).filter { it.at.isAfter(since) }.take(DIAG_LOG_KEEP))
}
