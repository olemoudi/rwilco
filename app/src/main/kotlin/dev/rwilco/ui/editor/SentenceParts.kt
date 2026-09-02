package dev.rwilco.ui.editor

import dev.rwilco.model.Recurrence
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.TriggerRule
import dev.rwilco.model.isAnchored

/**
 * The pieces a reminder reads as, in the order they are said. Pure: no strings, no locale, no
 * composition — so the shape of the sentence is a thing a JVM test can hold, and the wording is
 * somebody else's job ([ReminderSentence]).
 */
sealed interface SentencePart {
    /** What the reminder says, as written. */
    data class Words(val text: String) : SentencePart

    /** One rule, with whatever fences it carries. */
    data class Rule(val rule: TriggerRule) : SentencePart

    /** The word between two rules, which is the whole difference between the three readings. */
    data class Join(val match: RuleMatch) : SentencePart

    /** "…and comes back every fortnight": only where the recurrence works out its own moments. */
    data class Returns(val recurrence: Recurrence) : SentencePart
}

/**
 * A reminder folded into one sentence: the words, the rules with the right word between them,
 * and the recurrence last.
 *
 * The order is the order the form asks in, which is also the order somebody would say it in —
 * and the join is where the reading lives: "a las nueve **o** al llegar a casa" is a different
 * arrangement from "a las nueve **y** al llegar a casa", and until now the only place that
 * difference was said out loud was a segmented control three sections up the screen.
 *
 * Blank words are simply left out rather than stood in for: a draft with nothing typed yet is
 * still worth reading back for its "when", and a placeholder in the middle of a sentence reads
 * as a thing that went wrong.
 */
fun sentenceParts(
    text: String,
    rules: List<TriggerRule>,
    match: RuleMatch,
    recurrence: Recurrence,
): List<SentencePart> {
    val parts = mutableListOf<SentencePart>()
    // The words are bounded so the clause after them survives: the line over "Guardar" is
    // capped at three lines, and a two-sentence reminder ate exactly the half somebody
    // scrolled down to check (0.68.0).
    if (text.isNotBlank()) parts += SentencePart.Words(text.trim().let { if (it.length > MAX_SENTENCE_WORDS) it.take(MAX_SENTENCE_WORDS).trimEnd() + "…" else it })
    rules.forEachIndexed { index, rule ->
        if (index > 0) parts += SentencePart.Join(match)
        parts += SentencePart.Rule(rule)
    }
    // Only a recurrence that produces its own moments has anything to add; "no repetir" is the
    // absence of a sentence, not a clause in one.
    if (recurrence.isAnchored) parts += SentencePart.Returns(recurrence)
    return parts
}

/**
 * Whether there is anything worth reading back beyond the words themselves.
 *
 * A note with no "when" reads as its own text and nothing else, and a line under the form
 * repeating what somebody has just typed two fields above is noise — so it is not shown.
 */
fun List<SentencePart>.saysMoreThanWords(): Boolean = any { it !is SentencePart.Words }

/** How much of the words the sentence over the button carries before an ellipsis. */
const val MAX_SENTENCE_WORDS = 60
