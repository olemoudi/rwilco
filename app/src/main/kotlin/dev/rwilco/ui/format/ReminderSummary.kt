package dev.rwilco.ui.format

import dev.rwilco.R
import dev.rwilco.model.Reminder
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.isAnchored
import dev.rwilco.model.isRoutine
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import dev.rwilco.model.DEFAULT_DAY_START
import dev.rwilco.model.deadlineApplies
import dev.rwilco.model.routineDeadline

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
        // The deadline on the set, after the rules it bounds — the same clause the editor says.
        val deadline = reminder.deadline
        if (deadline != null && deadlineApplies(deadline, reminder.rules, reminder.ruleMatch) && isNotEmpty()) {
            append(", " + deadlinePhrase(words, deadline))
        }
        // Only a recurrence that works out its own moments has anything to add; "no repetir" is
        // the absence of a clause, not one.
        // A routine with no rules — the commonest kind — is its span and nothing else, and the
        // card that said nothing for it was a ring with no reason on it.
        if (reminder.recurrence.isAnchored && (isNotEmpty() || reminder.isRoutine)) {
            val label = recurrenceLabel(words, reminder.recurrence, today)
            // With nothing in front of it the span is the whole sentence. Joined on regardless, a
            // routine with no rules read ", y vuelve cada 21 días" — a comma and a conjunction
            // after nothing — on its notification, and now in a search result too (0.134.0).
            if (isEmpty()) append(label)
            else append(", " + words.get(R.string.editor_sentence_returns) + " " + label.replaceFirstChar { it.lowercase(words.locale) })
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

/**
 * Why a **routine's** card is in the shade: its plazo ran out — "Su plazo: cada 5 días desde la
 * última vez · venció ayer 10:00" — and never the sentence its rules make (0.159.0).
 *
 * A routine rings for its plazo and nothing else (`Routines.kt`): its rules only ask, or count it
 * as done. The card said [reminderSummary] all the same, and for "entrenar, al llevar 15 min en
 * el parque, y vuelve cada 5 días" that read as "you have been in the park fifteen minutes" on a
 * ring that was yesterday's deadline coming back from "mañana a la misma hora" — with the phone
 * nowhere near the park. The alert screen already said the plazo; this is the card saying it
 * too, with the moment it ran out, because a card that comes back from a snooze arrives a day
 * after it and "venció" is the one word that says which ring this is.
 */
fun routineRingReason(words: Words, reminder: Reminder, today: LocalDate, due: ZonedDateTime): String {
    val span = recurrenceLabel(words, reminder.recurrence, today).replaceFirstChar { it.lowercase(words.locale) }
    val ranOut = dayWord(words, due.toLocalDate(), today) + " " + TimeText.time(due.toLocalTime(), words.is24h, words.locale)
    return words.get(R.string.notif_routine_reason, span, ranOut)
}

/**
 * The line under a ring's title, off a plain [android.content.Context]: [routineRingReason] for a
 * routine, [summaryLine] for everything else.
 */
fun Reminder.ringReason(context: android.content.Context, defaultTime: LocalTime, dayStart: LocalTime = DEFAULT_DAY_START): String {
    val zone = ZoneId.systemDefault()
    val due = if (isRoutine) routineDeadline(zone, dayStart) else null
    return if (due == null) summaryLine(context, defaultTime)
    else routineRingReason(context.words(), this, LocalDate.now(zone), due.atZone(zone))
}
