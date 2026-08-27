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
    private const val CROSSING = "!"

    /**
     * The side keeps the letter the crossing had (`E`/`X`), so no id already registered on a
     * phone changes shape; a rule that asks for the doorway ([Trigger.Location.onCrossing])
     * gets a letter of its own, because it is a different thing to watch for and its memory of
     * which side the phone is on must not be inherited from the state reading.
     */
    fun encode(reminderId: String, triggerIndex: Int, place: Trigger.Location): String =
        "$reminderId$SEPARATOR$triggerIndex$CIRCLE" +
            circle(place.lat, place.lng, place.radiusM, place.presence.asTransition.name.first()) +
            if (place.onCrossing) CROSSING else ""

    /** The [conditionIndex]th circle named by rule [ruleIndex]'s conditions. Never a trigger. */
    fun encodeCondition(reminderId: String, ruleIndex: Int, conditionIndex: Int, place: Condition.AtPlace): String =
        "$reminderId$SEPARATOR$ruleIndex$CONDITION$conditionIndex$CIRCLE" + circle(place.lat, place.lng, place.radiusM, if (place.inside) 'I' else 'O')

    fun reminderIdOf(geofenceId: String): String = geofenceId.substringBefore(SEPARATOR)

    /** Null when the id names a condition's circle rather than a trigger. */
    fun triggerIndexOf(geofenceId: String): Int? =
        geofenceId.substringAfter(SEPARATOR, missingDelimiterValue = "").substringBefore(CIRCLE).toIntOrNull()

    /**
     * The geometry alone, without which side of it anybody is waiting for.
     *
     * Five decimals is about a metre: the same pin however the number was rounded on the way.
     * It is public because it is also how a circle is recognised *backwards* — a condition
     * carries a place but no id, and this is what finds the watch's memory of that place (see
     * `PlaceWatchState.sideOf`).
     */
    fun circleKey(lat: Double, lng: Double, radiusM: Int): String =
        String.format(Locale.ROOT, "%.5f,%.5f,%d", lat, lng, radiusM)

    private fun circle(lat: Double, lng: Double, radiusM: Int, way: Char): String =
        circleKey(lat, lng, radiusM) + "," + way
}
