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

    @Query("UPDATE reminder SET status = :status, updatedAt = :at, doneAt = :doneAt WHERE id = :id")
    suspend fun setStatus(id: String, status: String, at: Long, doneAt: Long?)

    /**
     * A snooze is the person's word: to a clock or to a place, never both, and the two are
     * written together so whichever is given clears the other.
     */
    @Query("UPDATE reminder SET snoozedUntil = :until, snoozedToPlace = :place WHERE id = :id")
    suspend fun setSnooze(id: String, until: Long?, place: String?)

    /** Deliberately does not touch updatedAt: ringing is not editing. */
    @Query("UPDATE reminder SET lastFiredAt = :at, lastFiredRule = :ruleIndex, snoozedUntil = NULL, snoozedToPlace = NULL WHERE id = :id")
    suspend fun markFired(id: String, at: Long, ruleIndex: Int?)

    /**
     * "Hecho", in one write: the snooze and the half-finished round go, the anchor every
     * recurrence counts from and the status are stamped. One statement rather than four, so a
     * process that dies in the middle cannot leave a round closed and its anchor unmoved.
     */
    @Query(
        "UPDATE reminder SET snoozedUntil = NULL, snoozedToPlace = NULL, firedRules = '', lastDealtAt = :at, " +
            "status = :status, updatedAt = :at, doneAt = :doneAt, dealtThrough = :through WHERE id = :id",
    )
    suspend fun dealtWith(id: String, at: Long, status: String, doneAt: Long?, through: Long?)

    /** The safety net has said its word about the firing at hand; it says one per firing. */
    @Query("UPDATE reminder SET nudgedAt = :at WHERE id = :id")
    suspend fun setNudgedAt(id: String, at: Long)

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
