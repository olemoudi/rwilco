package dev.rwilco.model

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

const val MAX_TEXT_LENGTH = 500
const val MAX_LABEL_LENGTH = 40
const val MIN_RADIUS_M = 100
const val MAX_RADIUS_M = 1000
/** A countdown of nothing is not a countdown; a week is as far as the sheet lets anybody go. */
const val MIN_COUNTDOWN_MINUTES = 1
const val MAX_COUNTDOWN_MINUTES = 7 * 24 * 60
const val MIN_RANDOM_TIMES = 1
const val MAX_RANDOM_TIMES = 5

/**
 * What blocks saving — which is only the words, and a trigger that is nonsense in itself.
 *
 * Not having a trigger is not an error: a reminder with nothing but text and a tag is a note on
 * a shelf, and keeping a list under "compra" that nothing ever rings is a thing people do.
 * Nor is having no action: a moment that passes quietly is still the person's choice.
 */
sealed interface ValidationError {
    data object TextBlank : ValidationError
    data object TextTooLong : ValidationError
    data class BadTrigger(val index: Int, val problem: TriggerProblem) : ValidationError
}

enum class TriggerProblem {
    DAYS_EMPTY,
    COUNTDOWN_OUT_OF_RANGE,
    RADIUS_OUT_OF_RANGE,
    COORDINATES_INVALID,
    LABEL_TOO_LONG,
    TIMES_OUT_OF_RANGE,
    WINDOW_EMPTY,
}

/** What is worth a word but not a wall: the reminder still saves. */
sealed interface ValidationWarning {
    data class InPast(val index: Int) : ValidationWarning
}

fun validate(text: String, rules: List<TriggerRule>): List<ValidationError> {
    val errors = ArrayList<ValidationError>()
    if (text.isBlank()) errors += ValidationError.TextBlank
    if (text.length > MAX_TEXT_LENGTH) errors += ValidationError.TextTooLong
    rules.forEachIndexed { index, rule ->
        problemOf(rule.trigger)?.let { errors += ValidationError.BadTrigger(index, it) }
        rule.conditions.forEach { condition ->
            problemOf(condition)?.let { errors += ValidationError.BadTrigger(index, it) }
        }
    }
    return errors
}

fun problemOf(condition: Condition): TriggerProblem? = when (condition) {
    // A window that starts where it ends is not a window; one that crosses midnight is.
    is Condition.TimeWindow -> TriggerProblem.WINDOW_EMPTY.takeIf { condition.from == condition.to }
}

fun problemOf(trigger: Trigger): TriggerProblem? = when (trigger) {
    is Trigger.AtDateTime, is Trigger.OnDate -> null
    is Trigger.AtTime -> TriggerProblem.DAYS_EMPTY.takeIf { trigger.days.isEmpty() }
    is Trigger.Countdown -> TriggerProblem.COUNTDOWN_OUT_OF_RANGE.takeIf { trigger.minutes !in MIN_COUNTDOWN_MINUTES..MAX_COUNTDOWN_MINUTES }
    is Trigger.Location -> when {
        trigger.lat !in -90.0..90.0 || trigger.lng !in -180.0..180.0 -> TriggerProblem.COORDINATES_INVALID
        trigger.radiusM !in MIN_RADIUS_M..MAX_RADIUS_M -> TriggerProblem.RADIUS_OUT_OF_RANGE
        trigger.label.length > MAX_LABEL_LENGTH -> TriggerProblem.LABEL_TOO_LONG
        else -> null
    }
    is Trigger.Random -> when {
        trigger.timesPer !in MIN_RANDOM_TIMES..MAX_RANDOM_TIMES -> TriggerProblem.TIMES_OUT_OF_RANGE
        // A window has to hold a minute per draw, or two draws would land on the same one.
        RandomDraw.windowMinutes(trigger) < trigger.timesPer -> TriggerProblem.WINDOW_EMPTY
        else -> null
    }
}

/** One-shot moments already behind us. Needs a clock, unlike [validate]. */
fun warnings(rules: List<TriggerRule>, now: Instant, zone: ZoneId, defaultTime: LocalTime): List<ValidationWarning> =
    rules.mapIndexedNotNull { index, rule ->
        val oneShot = rule.trigger is Trigger.AtDateTime || rule.trigger is Trigger.OnDate
        if (oneShot && nextFireOf(rule.trigger, "", now, zone, defaultTime) == null) ValidationWarning.InPast(index) else null
    }
