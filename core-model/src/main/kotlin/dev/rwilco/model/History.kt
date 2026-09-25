package dev.rwilco.model

import java.time.Instant

/**
 * The kinds of thing that happen to a reminder, each the verb a line of history says.
 *
 * Here rather than beside the table that stores them (0.149.0) because the history stopped being
 * only a thing to read back: the rounds, the streaks and the rest of the statistics are worked
 * out of it ([rounds]), and that is domain logic a JVM test has to be able to hold. The names are
 * stored as they are spelled — renaming one reads every line of it as nothing.
 */
enum class FiringKind {
    /** It rang, on time. */
    RANG,

    /** It rang, but late enough to arrive as the quiet "did not ring on time" note. */
    MISSED,

    /** The safety net said its word about a moment that got away. */
    NET,

    /**
     * "Hecho": to a ring waiting for an answer, to a round put off, or ahead of the moment — of a
     * one-off, or of the next round of something that comes back (0.149.0: only "saltar" is a skip).
     */
    DEALT,

    /** A round of a recurring reminder let pass on purpose, ahead of its ring. */
    SKIPPED,

    /** Put off, until [FiringEvent.detail] says. */
    SNOOZED,

    /** Under "todos", a place rule ticked off came undone again. */
    UNTICKED,

    /** The set's deadline ran out with the set incomplete, and the round was let go without a sound. */
    LAPSED,

    /** A routine was asked whether it had been done: a question in the shade, not a ring. */
    ASKED,

    /** A routine was counted as done by a place — [FiringEvent.detail] says which doorway. */
    RESET,

    /** "Deshacer" on that: the count went back to where it was before the place counted it. */
    UNRESET,
}

data class FiringEvent(val kind: FiringKind, val at: Instant, val ruleIndex: Int? = null, val detail: String? = null)

/**
 * What a "todavía no" writes as its detail: not a moment and not a place, just the word. Filed as
 * a [FiringKind.SNOOZED] because that is the shape of the line, but it is an answer to a question
 * and not a snooze — the statistics do not count it as one.
 */
const val LATER_DETAIL = "later"
