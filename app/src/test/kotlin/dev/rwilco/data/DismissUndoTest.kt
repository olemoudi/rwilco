package dev.rwilco.data

import dev.rwilco.model.ReminderCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The snapshot a "hecho" is taken back by, and the trip it makes inside a notification's button.
 *
 * The undo on the alert and in the shade carries the row itself, because a "hecho" writes nine
 * columns in one statement and putting one of them back is not an undo. What can go wrong here is
 * the trip: a column lost on the way out, or a row written by a newer build refusing to be read.
 */
class DismissUndoTest {

    private val row = ReminderEntity(
        id = "r1",
        text = "Sacar la basura",
        tags = "casa",
        triggers = "[]",
        actions = "[]",
        status = "ACTIVE",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_100_000L,
        doneAt = null,
        lastDealtAt = 1_700_000_200_000L,
        lastFiredAt = 1_700_000_300_000L,
        dealtThrough = 1_700_000_400_000L,
        firedRules = "0,1",
        expiresAt = 1_700_000_500_000L,
    )

    private fun trip(row: ReminderEntity): ReminderEntity {
        val text = ReminderCodec.json.encodeToString(ReminderEntity.serializer(), row)
        return ReminderCodec.json.decodeFromString(ReminderEntity.serializer(), text)
    }

    @Test
    fun `a row survives the trip through the button`() {
        assertEquals(row, trip(row))
    }

    @Test
    fun `the columns a hecho would write come back with it`() {
        // The nine the one statement touches, as far as they are the row's own: what an undo
        // that only put the anchor back would have lost.
        val after = trip(row)
        assertEquals(row.lastDealtAt, after.lastDealtAt)
        assertEquals(row.dealtThrough, after.dealtThrough)
        assertEquals(row.firedRules, after.firedRules)
        assertEquals(row.expiresAt, after.expiresAt)
        assertEquals(row.status, after.status)
        assertEquals(row.doneAt, after.doneAt)
    }

    @Test
    fun `a snooze waiting at a place keeps its circle`() {
        val waiting = row.copy(
            snoozedUntil = null,
            snoozedToPlace = """{"type":"location","lat":40.4168,"lon":-3.7038,"radiusM":150,"presence":"INSIDE","label":"Casa"}""",
        )
        assertEquals(waiting.snoozedToPlace, trip(waiting).snoozedToPlace)
    }

    @Test
    fun `a row written by a newer build keeps what this one understands`() {
        // The same leniency the vault reads rows with: a column this build has never heard of is
        // dropped, never the row — an undo is not the place to start refusing to work.
        val text = ReminderCodec.json.encodeToString(ReminderEntity.serializer(), row)
            .replaceFirst("{", """{"somethingNewer":"1",""")
        assertEquals(row, ReminderCodec.json.decodeFromString(ReminderEntity.serializer(), text))
    }
}
