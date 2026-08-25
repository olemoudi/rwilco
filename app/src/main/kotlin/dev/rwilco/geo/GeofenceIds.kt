package dev.rwilco.geo

/**
 * A watched circle belongs to one rule of one reminder, and a reminder may have several, so the
 * id has to carry both. Reminder ids are UUIDs, which contain no '#', so the last one is always
 * the separator.
 *
 * A rule's *conditions* can name circles too ("y sólo si estoy en casa"). Those are watched but
 * never fired, and they get a suffix after the rule index so they can never be mistaken for the
 * trigger's own — [triggerIndexOf] returns null for one, which is what stops it ringing anything
 * if it ever reached the firing path at all.
 */
object GeofenceIds {
    private const val SEPARATOR = '#'
    private const val CONDITION = 'c'

    fun encode(reminderId: String, triggerIndex: Int): String = "$reminderId$SEPARATOR$triggerIndex"

    /** The [conditionIndex]th circle named by rule [ruleIndex]'s conditions. Never a trigger. */
    fun encodeCondition(reminderId: String, ruleIndex: Int, conditionIndex: Int): String =
        "$reminderId$SEPARATOR$ruleIndex$CONDITION$conditionIndex"

    fun reminderIdOf(geofenceId: String): String = geofenceId.substringBeforeLast(SEPARATOR)

    /** Null when the id names a condition's circle rather than a trigger. */
    fun triggerIndexOf(geofenceId: String): Int? = geofenceId.substringAfterLast(SEPARATOR).toIntOrNull()
}
