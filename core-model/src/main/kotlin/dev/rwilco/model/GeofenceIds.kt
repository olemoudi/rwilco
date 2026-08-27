package dev.rwilco.model

import java.util.Locale

/**
 * A watched circle belongs to one rule of one reminder, and a reminder may have several, so the
 * id has to carry both. Reminder ids are UUIDs, which contain no '#', so the first one is always
 * the separator.
 *
 * It carries the circle itself as well — pin, radius, and which way it is waited on — because
 * the place watch remembers which circles the phone is inside *by this id*, and a rule index
 * is not a circle: delete the first of two rules and the second becomes rule 0, wearing the
 * memory of a place that is gone, which is how "al llegar a casa" once rang at somebody
 * sitting at home. With the circle in the id, an edited rule is a new id and starts from no
 * memory, which costs one baseline look and rings nothing.
 *
 * A rule's *conditions* can name circles too ("y sólo si estoy en casa"). Those are watched but
 * never fired, and they get a suffix after the rule index so they can never be mistaken for the
 * trigger's own — [triggerIndexOf] returns null for one, which is what stops it ringing anything
 * if it ever reached the firing path at all.
 */
object GeofenceIds {
    private const val SEPARATOR = '#'
    private const val CONDITION = 'c'
    private const val CIRCLE = '@'

    fun encode(reminderId: String, triggerIndex: Int, place: Trigger.Location): String =
        "$reminderId$SEPARATOR$triggerIndex$CIRCLE" + circle(place.lat, place.lng, place.radiusM, place.transition.name.first())

    /** The [conditionIndex]th circle named by rule [ruleIndex]'s conditions. Never a trigger. */
    fun encodeCondition(reminderId: String, ruleIndex: Int, conditionIndex: Int, place: Condition.AtPlace): String =
        "$reminderId$SEPARATOR$ruleIndex$CONDITION$conditionIndex$CIRCLE" + circle(place.lat, place.lng, place.radiusM, if (place.inside) 'I' else 'O')

    fun reminderIdOf(geofenceId: String): String = geofenceId.substringBefore(SEPARATOR)

    /** Null when the id names a condition's circle rather than a trigger. */
    fun triggerIndexOf(geofenceId: String): Int? =
        geofenceId.substringAfter(SEPARATOR, missingDelimiterValue = "").substringBefore(CIRCLE).toIntOrNull()

    /** Five decimals is about a metre: the same pin however the number was rounded on the way. */
    private fun circle(lat: Double, lng: Double, radiusM: Int, way: Char): String =
        String.format(Locale.ROOT, "%.5f,%.5f,%d,%c", lat, lng, radiusM, way)
}
