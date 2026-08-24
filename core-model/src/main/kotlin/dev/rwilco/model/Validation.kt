package dev.rwilco.model

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

const val MAX_TEXT_LENGTH = 500
const val MAX_LABEL_LENGTH = 40
const val MIN_RADIUS_M = 100
const val MAX_RADIUS_M = 1000
const val MIN_RANDOM_TIMES = 1
const val MAX_RANDOM_TIMES = 5

/** What blocks saving. */
sealed interface ValidationError {
    data object TextBlank : ValidationError
    data object TextTooLong : ValidationError
    data object NoTrigger : ValidationError
    data object NoAction : ValidationError
    data class BadTrigger(val index: Int, val problem: TriggerProblem) : ValidationError
}

enum class TriggerProblem {
    DAYS_EMPTY,
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

fun validate(text: String, triggers: List<Trigger>, actions: Set<Action>): List<ValidationError> {
    val errors = ArrayList<ValidationError>()
    if (text.isBlank()) errors += ValidationError.TextBlank
    if (text.length > MAX_TEXT_LENGTH) errors += ValidationError.TextTooLong
    if (triggers.isEmpty()) errors += ValidationError.NoTrigger
    if (actions.isEmpty()) errors += ValidationError.NoAction
    triggers.forEachIndexed { index, trigger ->
        problemOf(trigger)?.let { errors += ValidationError.BadTrigger(index, it) }
    }
    return errors
}

fun problemOf(trigger: Trigger): TriggerProblem? = when (trigger) {
    is Trigger.AtDateTime, is Trigger.OnDate -> null
    is Trigger.AtTime -> TriggerProblem.DAYS_EMPTY.takeIf { trigger.days.isEmpty() }
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
fun warnings(triggers: List<Trigger>, now: Instant, zone: ZoneId, defaultTime: LocalTime): List<ValidationWarning> =
    triggers.mapIndexedNotNull { index, trigger ->
        val oneShot = trigger is Trigger.AtDateTime || trigger is Trigger.OnDate
        if (oneShot && nextFireOf(trigger, "", now, zone, defaultTime) == null) ValidationWarning.InPast(index) else null
    }
