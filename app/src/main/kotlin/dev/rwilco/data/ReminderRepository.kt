package dev.rwilco.data

import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock

/**
 * The domain's view of persistence. Reactive for the screens, suspend for one-shot writes; Room
 * already runs both off the main thread.
 */
class ReminderRepository(private val dao: ReminderDao, private val clock: Clock) {

    val open: Flow<List<Reminder>> = dao.observeOpen().map { rows -> rows.map(ReminderEntity::toDomain) }

    val done: Flow<List<Reminder>> = dao.observeDone().map { rows -> rows.map(ReminderEntity::toDomain) }

    fun observe(id: String): Flow<Reminder?> = dao.observe(id).map { it?.toDomain() }

    suspend fun get(id: String): Reminder? = dao.get(id)?.toDomain()

    /** Upsert as given; the caller decides `updatedAt`. */
    suspend fun save(reminder: Reminder) = dao.upsert(reminder.toEntity())

    suspend fun setStatus(id: String, status: Status) {
        val now = clock.instant().toEpochMilli()
        dao.setStatus(id, status.name, now, if (status == Status.DONE) now else null)
    }

    suspend fun delete(id: String) = dao.delete(id)

    /** Undo of a delete: the reminder goes back exactly as it was. */
    suspend fun restore(reminder: Reminder) = dao.upsert(reminder.toEntity())

    suspend fun purgeDone() = dao.purgeDone()

    suspend fun deleteAll() = dao.deleteAll()
}
