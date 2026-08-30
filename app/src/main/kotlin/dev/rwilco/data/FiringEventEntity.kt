package dev.rwilco.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import java.time.Instant

/**
 * What happened to a reminder, one row per happening — the memory the row itself does not
 * keep. `lastFiredAt`, `lastDealtAt` and `snoozedUntil` are each one slot deep: the ring
 * before last is gone the moment the next one lands, and "¿sonó ayer?" had no answer
 * anywhere but a global diagnostics ring that keeps a week. This is per reminder, a few dozen
 * deep, and goes with the reminder ([ForeignKey] cascade): a history is a fact about that
 * row, not a second table of things to tidy.
 *
 * Not in the vault. It is diagnostic, like the place watch's log — what the row *is* is what
 * the backup copies, and what happened to it stays on the phone it happened on.
 */
@Entity(
    tableName = "firing_event",
    foreignKeys = [ForeignKey(entity = ReminderEntity::class, parentColumns = ["id"], childColumns = ["reminderId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("reminderId", "at")],
)
data class FiringEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reminderId: String,
    /** Epoch millis, when it happened — not the moment it was armed for. */
    val at: Long,
    /** [FiringKind] by name; an unknown one reads as nothing rather than as a crash. */
    val kind: String,
    val ruleIndex: Int? = null,
    /** A word for the screen where the kind is not the whole of it: which snooze, how late. */
    val detail: String? = null,
) {
    fun toDomain(): FiringEvent? {
        val kind = FiringKind.entries.firstOrNull { it.name == kind } ?: return null
        return FiringEvent(kind, Instant.ofEpochMilli(at), ruleIndex, detail)
    }
}

/** The kinds of thing that happen to a reminder, each the verb a line of history says. */
enum class FiringKind {
    /** It rang, on time. */
    RANG,

    /** It rang, but late enough to arrive as the quiet "did not ring on time" note. */
    MISSED,

    /** The safety net said its word about a moment that got away. */
    NET,

    /** "Hecho", given to a ring waiting for an answer, or to a one-off ahead of its moment. */
    DEALT,

    /** A round of a recurring reminder let pass on purpose, ahead of its ring. */
    SKIPPED,

    /** Put off, until [FiringEvent.detail] says. */
    SNOOZED,

    /** Under "todos", a place rule ticked off came undone again. */
    UNTICKED,
}

data class FiringEvent(val kind: FiringKind, val at: Instant, val ruleIndex: Int? = null, val detail: String? = null)

/** How many happenings a reminder keeps. A daily reminder answered every day is seven weeks of it. */
const val HISTORY_KEEP = 50

@Dao
interface FiringEventDao {
    @Insert
    suspend fun insert(event: FiringEventEntity)

    /** Newest first; the screen reads it that way and the cap below counts from the same end. */
    @Query("SELECT * FROM firing_event WHERE reminderId = :reminderId ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun history(reminderId: String, limit: Int): List<FiringEventEntity>

    /** The newest across every reminder, for the diagnostics report to sort into its rows. */
    @Query("SELECT * FROM firing_event ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun newest(limit: Int): List<FiringEventEntity>

    /** Everything past the newest [keep] of one reminder's rows. */
    @Query(
        "DELETE FROM firing_event WHERE reminderId = :reminderId AND id NOT IN " +
            "(SELECT id FROM firing_event WHERE reminderId = :reminderId ORDER BY at DESC, id DESC LIMIT :keep)",
    )
    suspend fun trim(reminderId: String, keep: Int)
}
