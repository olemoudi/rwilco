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

    /** In place of a rule index: the circle a snooze waits for belongs to no rule. */
    private const val SNOOZE = 's'

    /**
     * The side keeps the letter the crossing had (`E`/`X`), so no id already registered on a
     * phone changes shape; a rule that asks for the doorway ([Trigger.Location.onCrossing])
     * gets a letter of its own, because it is a different thing to watch for and its memory of
     * which side the phone is on must not be inherited from the state reading.
     *
     * Spelled out rather than read off `Transition.name.first()`, which was `E` for both sides
     * — `EXIT` starts with an E too — so an "al salir" circle shared its id, and the watch's
     * memory of which side the phone was on, with the "al llegar" reading of the same pin.
     * Every outside-facing id changes with the fix: one re-registration of the fences, and one
     * fresh baseline per such circle.
     */
    fun encode(reminderId: String, triggerIndex: Int, place: Trigger.Location): String =
        "$reminderId$SEPARATOR$triggerIndex$CIRCLE" +
            circle(place.lat, place.lng, place.radiusM, if (place.presence == Presence.INSIDE) 'E' else 'X') +
            if (place.onCrossing) CROSSING else ""

    /** The [conditionIndex]th circle named by rule [ruleIndex]'s conditions. Never a trigger. */
    fun encodeCondition(reminderId: String, ruleIndex: Int, conditionIndex: Int, place: Condition.AtPlace): String =
        "$reminderId$SEPARATOR$ruleIndex$CONDITION$conditionIndex$CIRCLE" + circle(place.lat, place.lng, place.radiusM, if (place.inside) 'I' else 'O')

    /**
     * The circle a "remind me when I get there" waits for. Always a doorway, and a rule index
     * of [SNOOZE] where a trigger's would be, so [triggerIndexOf] answers null for it the way
     * it does for a condition — and [isSnooze] is what tells the two apart.
     */
    fun encodeSnooze(reminderId: String, place: Trigger.Location): String =
        "$reminderId$SEPARATOR$SNOOZE$CIRCLE" + circle(place.lat, place.lng, place.radiusM, if (place.presence == Presence.INSIDE) 'E' else 'X') + CROSSING

    fun isSnooze(geofenceId: String): Boolean =
        geofenceId.substringAfter(SEPARATOR, missingDelimiterValue = "").startsWith("$SNOOZE$CIRCLE")

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

    /**
     * Whether a string is one of these ids rather than somebody's word for a place.
     *
     * A guard at the reading end, and it is here because this is the only file that knows what
     * one of these looks like. Ids leaked into the place watch's log for a while — a crossing
     * that arrives for a circle the watch is no longer spending anything on has no live place to
     * take a label from, and the fallback was the id — and a log is written once and read for
     * days afterwards, so fixing the writing does nothing for the two hundred lines already on
     * somebody's phone. This is what stops one reaching a screen whatever wrote it.
     *
     * Matched on the tail rather than on the '#' and the '@', because a person may well call a
     * place "Café #1 @ Sol". Nobody calls one "@40.50074,-3.66413,150,E".
     */
    fun looksLikeId(value: String): Boolean = ID_TAIL.containsMatchIn(value)

    private val ID_TAIL = Regex("""@-?\d+\.\d{5},-?\d+\.\d{5},\d+,[EXIO]!?$""")
}
