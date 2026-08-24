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

    @Query("SELECT * FROM reminder WHERE id = :id")
    fun observe(id: String): Flow<ReminderEntity?>

    @Query("SELECT * FROM reminder WHERE id = :id")
    suspend fun get(id: String): ReminderEntity?

    @Upsert
    suspend fun upsert(entity: ReminderEntity)

    @Query("UPDATE reminder SET status = :status, updatedAt = :at, doneAt = :doneAt WHERE id = :id")
    suspend fun setStatus(id: String, status: String, at: Long, doneAt: Long?)

    @Query("DELETE FROM reminder WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM reminder WHERE status = 'DONE'")
    suspend fun purgeDone()

    @Query("DELETE FROM reminder")
    suspend fun deleteAll()
}
