package dev.rwilco.geo

/**
 * A geofence belongs to one place trigger, and a reminder may have several, so the id has to
 * carry both. Reminder ids are UUIDs, which contain no '#', so the last one is always the
 * separator.
 */
object GeofenceIds {
    private const val SEPARATOR = '#'

    fun encode(reminderId: String, triggerIndex: Int): String = "$reminderId$SEPARATOR$triggerIndex"

    fun reminderIdOf(geofenceId: String): String = geofenceId.substringBeforeLast(SEPARATOR)

    fun triggerIndexOf(geofenceId: String): Int? = geofenceId.substringAfterLast(SEPARATOR).toIntOrNull()
}
