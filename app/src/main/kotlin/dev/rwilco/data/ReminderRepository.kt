package dev.rwilco.data

import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import dev.rwilco.model.expiredDone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant

/**
 * The domain's view of persistence. Reactive for the screens, suspend for one-shot writes; Room
 * already runs both off the main thread.
 */
class ReminderRepository(
    private val dao: ReminderDao,
    private val clock: Clock,
    private val events: FiringEventDao,
) {

    val open: Flow<List<Reminder>> = dao.observeOpen().map { rows -> rows.map(ReminderEntity::toDomain) }

    val done: Flow<List<Reminder>> = dao.observeDone().map { rows -> rows.map(ReminderEntity::toDomain) }

    fun observe(id: String): Flow<Reminder?> = dao.observe(id).map { it?.toDomain() }

    suspend fun get(id: String): Reminder? = dao.get(id)?.toDomain()

    /** Everything that is not done, right now — what the scheduler arms alarms from. */
    suspend fun openNow(): List<Reminder> = dao.getOpen().map(ReminderEntity::toDomain)

    /** Everything, done included — the raw material for suggesting text somebody has used before. */
    suspend fun allNow(): List<Reminder> = dao.getAll().map(ReminderEntity::toDomain)

    /** The rows themselves, as stored: what the backup copies, byte for byte. */
    suspend fun allRows(): List<ReminderEntity> = dao.getAll()

    /** The rows, reactive, so the backup hears about a change without polling. */
    val rows: Flow<List<ReminderEntity>> = dao.observeAll()

    /** A restore: the table becomes [rows] in one transaction. */
    suspend fun replaceAll(rows: List<ReminderEntity>) = dao.replaceAll(rows)

    suspend fun snooze(id: String, until: Instant?) = dao.setSnooze(id, until?.toEpochMilli())

    suspend fun markFired(id: String, at: Instant, ruleIndex: Int?) = dao.markFired(id, at.toEpochMilli(), ruleIndex)

    suspend fun setNudgedAt(id: String, at: Instant) = dao.setNudgedAt(id, at.toEpochMilli())

    suspend fun setArmedFor(id: String, at: Instant?, ruleIndex: Int?) =
        dao.setArmedFor(id, at?.toEpochMilli(), ruleIndex)

    /** Which rules of an ALL reminder have happened so far; empty starts the round again. */
    suspend fun setFiredRules(id: String, indices: Set<Int>) = dao.setFiredRules(id, encodeIndices(indices))

    /** Upsert as given; the caller decides `updatedAt`. */
    suspend fun save(reminder: Reminder) = dao.upsert(reminder.toEntity())

    /** A curation touching several rows at once: renaming a tag, rewording a phrase. */
    suspend fun saveAll(reminders: List<Reminder>) {
        if (reminders.isEmpty()) return
        dao.upsertAll(reminders.map { it.toEntity() })
    }

    /**
     * A firing dealt with at [at]: see `ReminderDao.dealtWith`. [through] is the moment that was
     * coming and has been dealt with before it could ring, or null when the thing dealt with was
     * a firing that had already happened.
     */
    suspend fun dealtWith(id: String, at: Instant, status: Status, through: Instant?) {
        val millis = at.toEpochMilli()
        dao.dealtWith(id, millis, status.name, if (status == Status.DONE) millis else null, through?.toEpochMilli())
    }

    suspend fun setStatus(id: String, status: Status) {
        val now = clock.instant().toEpochMilli()
        dao.setStatus(id, status.name, now, if (status == Status.DONE) now else null)
    }

    suspend fun delete(id: String) = dao.delete(id)

    /** Undo of a delete: the reminder goes back exactly as it was. */
    suspend fun restore(reminder: Reminder) = dao.upsert(reminder.toEntity())

    suspend fun purgeDone() = dao.purgeDone()

    /**
     * The history, trimmed to the three months it is kept for ([DONE_KEPT_MONTHS]); the number
     * of rows that went.
     *
     * The rule lives in [expiredDone] and the deletion is by id rather than by a WHERE clause
     * saying the same thing in SQL: one place to read it, one place to test it, and no chance of
     * the two drifting into disagreeing about what "three months" means.
     */
    suspend fun sweepOldDone(): Int {
        val stale = expiredDone(allNow(), clock.instant(), clock.zone)
        if (stale.isNotEmpty()) dao.deleteAll(stale)
        return stale.size
    }

    suspend fun deleteAll() = dao.deleteAll()

    /**
     * One thing that happened to a reminder, written down. Capped at [HISTORY_KEEP] per
     * reminder on the way in; a row that is gone by the time this runs (a notification's
     * button outliving its reminder) is nothing to write about, not a failure.
     */
    suspend fun record(reminderId: String, kind: FiringKind, at: Instant = clock.instant(), ruleIndex: Int? = null, detail: String? = null) {
        runCatching {
            events.insert(FiringEventEntity(reminderId = reminderId, at = at.toEpochMilli(), kind = kind.name, ruleIndex = ruleIndex, detail = detail))
            events.trim(reminderId, HISTORY_KEEP)
        }
    }

    /** What happened to one reminder, newest first. */
    suspend fun history(reminderId: String, limit: Int = HISTORY_KEEP): List<FiringEvent> =
        events.history(reminderId, limit).mapNotNull(FiringEventEntity::toDomain)

    /** The newest [perReminder] happenings of every reminder, for the diagnostics report. */
    suspend fun recentHistory(perReminder: Int): Map<String, List<FiringEvent>> =
        events.newest(HISTORY_KEEP * 8)
            .groupBy { it.reminderId }
            .mapValues { (_, rows) -> rows.take(perReminder).mapNotNull(FiringEventEntity::toDomain) }
}
