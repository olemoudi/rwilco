package dev.rwilco.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import dev.rwilco.model.FiringEvent
import dev.rwilco.model.FiringKind
import java.time.Instant

/**
 * What happened to a reminder, one row per happening — the memory the row itself does not
 * keep. `lastFiredAt`, `lastDealtAt` and `snoozedUntil` are each one slot deep: the ring
 * before last is gone the moment the next one lands, and "¿sonó ayer?" had no answer
 * anywhere but a global diagnostics ring that keeps a week. This is per reminder, up to
 * [HISTORY_KEEP] deep, and goes with the reminder ([ForeignKey] cascade): a history is a fact
 * about that row, not a second table of things to tidy.
 *
 * Since 0.149.0 it is also what the statistics are worked out of (`rounds`, `reminderStats`), which
 * is why it is read back **in the order it was written** ([FiringEventDao.written]) and why every
 * undo takes its own line back with it.
 *
 * Not in the vault. What the row *is* is what the backup copies, and what happened to it stays on
 * the phone it happened on: a restore starts the streaks again (the achievements they earned
 * travel, in the settings).
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

/**
 * How many happenings a reminder keeps: about a year of a daily one with its snoozes, which is
 * what a streak or "never missed" needs to mean something. It was fifty (seven weeks) while the
 * history was only read back line by line; an hourly reminder still reaches the cap in three
 * weeks, and its statistics then say "since" the oldest line kept rather than "ever".
 */
const val HISTORY_KEEP = 1000

/** How many lines the diagnostics report looks through for its five per reminder. */
const val DIAG_HISTORY_LINES = 400

@Dao
interface FiringEventDao {
    @Insert
    suspend fun insert(event: FiringEventEntity)

    /** A reminder's history put back with it, after an undone delete. */
    @Insert
    suspend fun insertAll(events: List<FiringEventEntity>)

    /** Newest first; the screen reads it that way and the cap below counts from the same end. */
    @Query("SELECT * FROM firing_event WHERE reminderId = :reminderId ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun history(reminderId: String, limit: Int): List<FiringEventEntity>

    /**
     * One reminder's history in the order it was written, which is the order the rounds are read
     * in: a "hecho" dated back ("lo hice el sábado") is written after the ring it answers and must
     * be read after it too, whatever its date says.
     */
    @Query("SELECT * FROM firing_event WHERE reminderId = :reminderId ORDER BY id")
    suspend fun written(reminderId: String): List<FiringEventEntity>

    /** The newest across every reminder, for the diagnostics report to sort into its rows. */
    @Query("SELECT * FROM firing_event ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun newest(limit: Int): List<FiringEventEntity>

    /**
     * The newest line of one of [kinds] written after [after], if there is one: what an undo takes
     * back with the row (0.139.0). "Hecho" writes a line and the undo restored the row without it,
     * so a reminder answered and unanswered twice read as "hecha 3 veces" having been done once.
     * Bounded by [after] — the moment the row itself was last dealt with — so it can only ever
     * reach the line this very "hecho" wrote, never the real one before it.
     */
    @Query(
        "DELETE FROM firing_event WHERE id = (SELECT id FROM firing_event WHERE reminderId = :reminderId " +
            "AND kind IN (:kinds) AND at > :after ORDER BY at DESC, id DESC LIMIT 1)",
    )
    suspend fun deleteNewest(reminderId: String, kinds: List<String>, after: Long)

    /** Everything past the newest [keep] of one reminder's rows. */
    @Query(
        "DELETE FROM firing_event WHERE reminderId = :reminderId AND id NOT IN " +
            "(SELECT id FROM firing_event WHERE reminderId = :reminderId ORDER BY at DESC, id DESC LIMIT :keep)",
    )
    suspend fun trim(reminderId: String, keep: Int)
}
