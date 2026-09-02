package dev.rwilco.ui.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import dev.rwilco.R
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.TriggerFamily
import dev.rwilco.model.family
import dev.rwilco.ui.format.conditionPhrase
import dev.rwilco.ui.format.recurrenceLabel
import dev.rwilco.ui.format.rememberWords
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.format.triggerPhrase
import dev.rwilco.ui.theme.color
import java.time.LocalDate
import java.time.LocalTime

/**
 * The whole draft as one line, over the button that saves it.
 *
 * The form is five cards down a scrolling column, and by the time somebody reaches "Guardar" the
 * words are three screens up: what is being saved has to be re-read from four separate places
 * and assembled in the head. This says it back in one sentence, in the place Buzzkill puts its
 * helper line — just above the primary action, where the eye already is.
 *
 * **Each piece wears the colour of what it is**: a clock is the time family's blue, a place is
 * the place family's green, a draw is the chance family's violet, and the joins and fences stay
 * the muted prose colour. It is the same colour code the keycaps and the cards' rails use, so
 * the line teaches nothing new — and it is the reason there is no other mark on the editable
 * parts: the colour is the mark.
 *
 * It says nothing at all until there is something to say beyond the words themselves
 * ([saysMoreThanWords]), which also keeps it off the screen while somebody is typing into a
 * blank draft with the keyboard up.
 */
@Composable
fun ReminderSentence(
    parts: List<SentencePart>,
    today: LocalDate,
    defaultTime: LocalTime,
    modifier: Modifier = Modifier,
) {
    Text(
        text = sentenceText(parts, today, defaultTime),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The parts, in words and colours. Separate so the wording can be read in one place. */
@Composable
private fun sentenceText(parts: List<SentencePart>, today: LocalDate, defaultTime: LocalTime): AnnotatedString {
    val words = rememberWords()
    val locale = words.locale
    val wordsInk = MaterialTheme.colorScheme.onSurface
    val timeInk = TriggerFamily.TIME.color()
    return buildAnnotatedString {
        parts.forEachIndexed { index, part ->
            if (index > 0) {
                // A comma where the sentence changes subject — after the words, and before the
                // recurrence — and a plain space between a rule and the word that joins it to
                // the next. The joins carry no spacing of their own, or every one of them would
                // land in the middle of a double space.
                val previous = parts[index - 1]
                append(if (previous is SentencePart.Words || part is SentencePart.Returns) ", " else " ")
            }
            when (part) {
                is SentencePart.Words ->
                    withStyle(SpanStyle(color = wordsInk, fontWeight = FontWeight.SemiBold)) { append(part.text) }

                is SentencePart.Join -> append(stringResource(part.match.joinRes))

                is SentencePart.Rule -> {
                    withStyle(SpanStyle(color = part.rule.trigger.family.color(), fontWeight = FontWeight.SemiBold)) {
                        append(triggerPhrase(words, part.rule.trigger, today, defaultTime))
                    }
                    if (part.rule.conditions.isNotEmpty()) {
                        // A plain loop: conditionPhrase reads string resources, and a composable
                        // cannot be called from joinToString's lambda. Joined with the same "y"
                        // the rules use, and the "sólo" said once in front of all of them.
                        val fences = mutableListOf<String>()
                        for (condition in part.rule.conditions) fences += conditionPhrase(words, condition, today)
                        val joined = fences.joinToString(" " + stringResource(R.string.editor_sentence_and) + " ")
                        append(" " + stringResource(R.string.editor_sentence_only, joined))
                    }
                }

                is SentencePart.Returns -> {
                    // A recurrence is a clock's business, and wears the clock's colour — the
                    // same one its keycap wears on the card.
                    val label = recurrenceLabel(words, part.recurrence, today).replaceFirstChar { it.lowercase(locale) }
                    append(stringResource(R.string.editor_sentence_returns) + " ")
                    withStyle(SpanStyle(color = timeInk, fontWeight = FontWeight.SemiBold)) { append(label) }
                }
            }
        }
    }
}

/** The word between two rules: the whole difference between the three readings, in one word. */
private val RuleMatch.joinRes: Int
    get() = when (this) {
        RuleMatch.ANY -> R.string.editor_sentence_or
        RuleMatch.ALL -> R.string.editor_sentence_and
        RuleMatch.TOGETHER -> R.string.editor_sentence_at_once
    }
