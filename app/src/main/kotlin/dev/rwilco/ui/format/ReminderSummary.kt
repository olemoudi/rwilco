package dev.rwilco.ui.format

import dev.rwilco.R
import dev.rwilco.model.Reminder
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.isAnchored
import java.time.LocalDate
import java.time.LocalTime

/**
 * Why a reminder rings, in one plain line — the same sentence the form says back over its save
 * button, with the words themselves left out.
 *
 * The notification used to put the reminder's own text in the title *and* in the line under it,
 * which is the same sentence twice and the second one carrying nothing. What belongs there is the
 * thing the title cannot say: **why this arrived now.** A person reading "Organizar fotos" at
 * half past six wants "al llegar a Casa" or "cada martes a las 18:30" under it, and that sentence
 * already exists — it is what the editor shows above "Guardar", where it was written to be read
 * back by somebody who has forgotten what they set three screens up.
 *
 * So this is [sentenceParts] again, minus [SentencePart.Words] (the title says those), rendered
 * with no colour, since a notification has none to give. The wording is [triggerPhrase]'s and
 * [conditionPhrase]'s and nobody else's, which is the whole reason the phrase functions stopped
 * being composables: two functions saying the same sentence drift, one does not.
 *
 * Empty when there is nothing to say — a reminder with no rules at all — and the caller then
 * leaves the line off rather than printing a blank one.
 */
fun reminderSummary(words: Words, reminder: Reminder, today: LocalDate, defaultTime: LocalTime): String =
    buildString {
        reminder.rules.forEachIndexed { index, rule ->
            if (index > 0) append(" " + words.get(reminder.ruleMatch.joinRes) + " ")
            append(triggerPhrase(words, rule.trigger, today, defaultTime))
            if (rule.conditions.isNotEmpty()) {
                // "sólo" said once in front of all of them, and an "y" between, exactly as the
                // sentence over the save button joins them.
                val fences = rule.conditions.joinToString(" " + words.get(R.string.editor_sentence_and) + " ") {
                    conditionPhrase(words, it, today)
                }
                append(" " + words.get(R.string.editor_sentence_only, fences))
            }
        }
        // Only a recurrence that works out its own moments has anything to add; "no repetir" is
        // the absence of a clause, not one.
        if (reminder.recurrence.isAnchored && isNotEmpty()) {
            append(", " + words.get(R.string.editor_sentence_returns) + " ")
            append(recurrenceLabel(words, reminder.recurrence, today).replaceFirstChar { it.lowercase(words.locale) })
        }
        // Waiting at a place: the one thing that says when it rings next, and the reason the
        // notification gives when it does.
        reminder.snoozedToPlace?.let { door ->
            if (isNotEmpty()) append(", ")
            append(words.get(R.string.history_snoozed_until, snoozePlacePhrase(words, door)))
        }
    }

/** The word between two rules: the whole difference between the three readings, in one word. */
private val RuleMatch.joinRes: Int
    get() = when (this) {
        RuleMatch.ANY -> R.string.editor_sentence_or
        RuleMatch.ALL -> R.string.editor_sentence_and
        RuleMatch.TOGETHER -> R.string.editor_sentence_at_once
    }

/**
 * The same line off a plain [android.content.Context], for the places that have one and no
 * composition: the notification, and anything else that has to say why a reminder arrived.
 */
fun Reminder.summaryLine(context: android.content.Context, defaultTime: LocalTime): String =
    reminderSummary(context.words(), this, LocalDate.now(), defaultTime)
