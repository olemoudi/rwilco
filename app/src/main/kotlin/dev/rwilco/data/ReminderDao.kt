package dev.rwilco.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminder WHERE status != 'DONE' ORDER BY createdAt")
    fun observeOpen(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminder WHERE status != 'DONE' ORDER BY createdAt")
    suspend fun getOpen(): List<ReminderEntity>

    @Query("SELECT * FROM reminder WHERE status = 'DONE' ORDER BY doneAt DESC")
    fun observeDone(): Flow<List<ReminderEntity>>

    /**
     * Everything ever written, done included: what the "you have said this before" list is made
     * of, and what the backup is a copy of. By id so the same rows are the same bytes: the
     * backup decides whether anything changed by hashing them, and SQLite promises no order.
     */
    @Query("SELECT * FROM reminder ORDER BY id")
    suspend fun getAll(): List<ReminderEntity>

    /** The same, reactive: what tells the backup that something is worth copying. */
    @Query("SELECT * FROM reminder ORDER BY id")
    fun observeAll(): Flow<List<ReminderEntity>>

    /** A restore: the table becomes exactly [rows], in one transaction or not at all. */
    @Transaction
    suspend fun replaceAll(rows: List<ReminderEntity>) {
        deleteAll()
        upsertAll(rows)
    }

    @Query("SELECT * FROM reminder WHERE id = :id")
    fun observe(id: String): Flow<ReminderEntity?>

    @Query("SELECT * FROM reminder WHERE id = :id")
    suspend fun get(id: String): ReminderEntity?

    @Upsert
    suspend fun upsert(entity: ReminderEntity)

    /** One transaction for a curation that touches many rows: a tag renamed, a phrase reworded. */
    @Upsert
    suspend fun upsertAll(entities: List<ReminderEntity>)

    /**
     * [resumedAt] is written only when a pause is being lifted (null leaves the column alone):
     * bringing a reminder back is asking for it again, which is what stops a ring nobody
     * answered from keeping a place-as-state quiet for ever. See [Reminder.resumedAt].
     */
    @Query("UPDATE reminder SET status = :status, updatedAt = :at, doneAt = :doneAt, resumedAt = COALESCE(:resumedAt, resumedAt), pausedAt = :pausedAt WHERE id = :id")
    suspend fun setStatus(id: String, status: String, at: Long, doneAt: Long?, resumedAt: Long?, pausedAt: Long?)

    /**
     * A routine brought back from a pause, in one write: the status, the resume stamp, the
     * pause cleared and the count's anchor moved by the time it rested
     * ([Reminder.routineAnchorAfterPause]). One statement, or a process dying between two would
     * leave an anchor already moved under a pause still standing — and the next resume would
     * move it again.
     */
    @Query("UPDATE reminder SET status = 'ACTIVE', updatedAt = :at, resumedAt = :at, pausedAt = NULL, lastDealtAt = :anchor WHERE id = :id")
    suspend fun resumeRoutine(id: String, at: Long, anchor: Long)

    /**
     * The same resume for a routine that has **never been done**, where the count runs from the
     * routine's own start and not from a "hecho" there has never been: the rest moves
     * [Recurrence.Since.startsAt] instead ([Reminder.recurrenceAfterPause]), and `lastDealtAt`
     * is left alone — writing the anchor there made the row claim it had been done once, which
     * is what took "aún no empieza" away from it for good.
     *
     * One statement, for the reason the one above it is: a process dying between two writes must
     * not leave a start already moved under a pause still standing.
     */
    @Query("UPDATE reminder SET status = 'ACTIVE', updatedAt = :at, resumedAt = :at, pausedAt = NULL, recurrence = :recurrence WHERE id = :id")
    suspend fun resumeRoutineStart(id: String, at: Long, recurrence: String)

    /**
     * A contact's cadence carried over from Settings (`contactsOutOfStep`). The recurrence alone,
     * so a ring or a "hablado" written in between is not put back; and only while the contact
     * still follows Settings, so a cadence somebody set by hand in the meantime stays theirs.
     * Deliberately not `updatedAt`: nobody edited it.
     */
    @Query("UPDATE reminder SET recurrence = :recurrence WHERE id = :id AND contactCadenceByHand = 0")
    suspend fun setFollowedCadence(id: String, recurrence: String)

    /**
     * A snooze is the person's word: to a clock or to a place, never both, and the two are
     * written together so whichever is given clears the other.
     */
    @Query("UPDATE reminder SET snoozedUntil = :until, snoozedToPlace = :place WHERE id = :id")
    suspend fun setSnooze(id: String, until: Long?, place: String?)

    /**
     * Deliberately does not touch updatedAt: ringing is not editing. The deadline goes with the
     * ring: a set that rang is not given up on, whatever the clock says afterwards.
     */
    @Query("UPDATE reminder SET lastFiredAt = :at, lastFiredRule = :ruleIndex, snoozedUntil = NULL, snoozedToPlace = NULL, expiresAt = NULL WHERE id = :id")
    suspend fun markFired(id: String, at: Long, ruleIndex: Int?)

    /**
     * "Hecho", in one write: the snooze and the half-finished round go, the anchor every
     * recurrence counts from and the status are stamped. One statement rather than four, so a
     * process that dies in the middle cannot leave a round closed and its anchor unmoved.
     */
    @Query(
        "UPDATE reminder SET snoozedUntil = NULL, snoozedToPlace = NULL, firedRules = '', lastDealtAt = :at, " +
            "status = :status, updatedAt = :at, doneAt = :doneAt, dealtThrough = :through, expiresAt = :expiresAt WHERE id = :id",
    )
    suspend fun dealtWith(id: String, at: Long, status: String, doneAt: Long?, through: Long?, expiresAt: Long?)

    /** The deadline of the round under way: a timer started by its first moment, or nothing. */
    @Query("UPDATE reminder SET expiresAt = :at WHERE id = :id")
    suspend fun setExpiresAt(id: String, at: Long?)

    /** The safety net has said its word about the firing at hand; it says one per firing. */
    @Query("UPDATE reminder SET nudgedAt = :at WHERE id = :id")
    suspend fun setNudgedAt(id: String, at: Long)

    /** A routine was asked whether it had been done; the next question looks from here. */
    @Query("UPDATE reminder SET askedAt = :at WHERE id = :id")
    suspend fun setAskedAt(id: String, at: Long)

    /** The undo of a reset by a place: the count goes back to where it was, and nothing else moves. */
    @Query("UPDATE reminder SET lastDealtAt = :at WHERE id = :id")
    suspend fun setLastDealtAt(id: String, at: Long?)

    @Query("UPDATE reminder SET armedFor = :at, armedRule = :ruleIndex WHERE id = :id")
    suspend fun setArmedFor(id: String, at: Long?, ruleIndex: Int?)

    @Query("UPDATE reminder SET firedRules = :indices WHERE id = :id")
    suspend fun setFiredRules(id: String, indices: String)

    @Query("DELETE FROM reminder WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM reminder WHERE status = 'DONE'")
    suspend fun purgeDone()

    @Query("DELETE FROM reminder WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<String>)

    @Query("DELETE FROM reminder")
    suspend fun deleteAll()
}
