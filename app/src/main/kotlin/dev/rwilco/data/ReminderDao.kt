package dev.rwilco.data

import androidx.room.Dao
import androidx.room.Query
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

    /** Everything ever written, done included: what the "you have said this before" list is made of. */
    @Query("SELECT * FROM reminder")
    suspend fun getAll(): List<ReminderEntity>

    @Query("SELECT * FROM reminder WHERE id = :id")
    fun observe(id: String): Flow<ReminderEntity?>

    @Query("SELECT * FROM reminder WHERE id = :id")
    suspend fun get(id: String): ReminderEntity?

    @Upsert
    suspend fun upsert(entity: ReminderEntity)

    @Query("UPDATE reminder SET status = :status, updatedAt = :at, doneAt = :doneAt WHERE id = :id")
    suspend fun setStatus(id: String, status: String, at: Long, doneAt: Long?)

    /** A snooze is the person's word, so it also clears whatever was armed for the old moment. */
    @Query("UPDATE reminder SET snoozedUntil = :until WHERE id = :id")
    suspend fun setSnooze(id: String, until: Long?)

    /** Deliberately does not touch updatedAt: ringing is not editing. */
    @Query("UPDATE reminder SET lastFiredAt = :at, snoozedUntil = NULL WHERE id = :id")
    suspend fun markFired(id: String, at: Long)

    @Query("UPDATE reminder SET armedFor = :at WHERE id = :id")
    suspend fun setArmedFor(id: String, at: Long?)

    @Query("DELETE FROM reminder WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM reminder WHERE status = 'DONE'")
    suspend fun purgeDone()

    @Query("DELETE FROM reminder")
    suspend fun deleteAll()
}
