package dev.rwilco.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.rwilco.model.Reminder
import dev.rwilco.model.ReminderCodec
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.Status
import java.time.Instant

/**
 * One row per reminder. Tags, triggers and actions are JSON text columns (see ReminderCodec):
 * nothing queries inside them, and a JSON column takes a new trigger kind without a migration.
 * Instants are epoch millis.
 */
@Entity(tableName = "reminder", indices = [Index("status")])
data class ReminderEntity(
    @PrimaryKey val id: String,
    val text: String,
    val tags: String,
    /** The rules, as JSON. Named for what it held in v1 — a bare trigger list — which it still reads. */
    val triggers: String,
    val actions: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val doneAt: Long?,
    val snoozedUntil: Long? = null,
    val lastFiredAt: Long? = null,
    val armedFor: Long? = null,
    /** ANY or ALL; an unknown value reads as ANY, which is the safe way round (it rings sooner). */
    val ruleMatch: String = "ANY",
    /** Which rule [armedFor] was for, so a slept-through firing is recorded against the right one. */
    val armedRule: Int? = null,
    /** ALL only: the rule indices already ticked off this round, comma-separated. */
    val firedRules: String = "",
    /** Whether "hecho" leaves it waiting for the next moment. Off unless it was asked for. */
    val repeats: Boolean = false,
)

fun ReminderEntity.toDomain(): Reminder = Reminder(
    id = id,
    text = text,
    tags = ReminderCodec.decodeTags(tags),
    rules = ReminderCodec.decodeRules(triggers),
    actions = ReminderCodec.decodeActions(actions),
    // A status this build does not know is treated as active: showing a reminder that a newer
    // build filed somewhere else beats hiding it.
    status = Status.entries.firstOrNull { it.name == status } ?: Status.ACTIVE,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    doneAt = doneAt?.let(Instant::ofEpochMilli),
    snoozedUntil = snoozedUntil?.let(Instant::ofEpochMilli),
    lastFiredAt = lastFiredAt?.let(Instant::ofEpochMilli),
    armedFor = armedFor?.let(Instant::ofEpochMilli),
    ruleMatch = RuleMatch.entries.firstOrNull { it.name == ruleMatch } ?: RuleMatch.ANY,
    armedRule = armedRule,
    firedRules = decodeIndices(firedRules),
    repeats = repeats,
)

fun Reminder.toEntity(): ReminderEntity = ReminderEntity(
    id = id,
    text = text,
    tags = ReminderCodec.encodeTags(tags),
    triggers = ReminderCodec.encodeRules(rules),
    actions = ReminderCodec.encodeActions(actions),
    status = status.name,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    doneAt = doneAt?.toEpochMilli(),
    snoozedUntil = snoozedUntil?.toEpochMilli(),
    lastFiredAt = lastFiredAt?.toEpochMilli(),
    armedFor = armedFor?.toEpochMilli(),
    ruleMatch = ruleMatch.name,
    armedRule = armedRule,
    firedRules = encodeIndices(firedRules),
    repeats = repeats,
)

/**
 * A handful of small non-negative integers: "0,2". Plain text rather than JSON because that is
 * all it will ever be, and a column somebody reads in a SQLite browser should be readable.
 */
fun encodeIndices(indices: Set<Int>): String = indices.sorted().joinToString(",")

fun decodeIndices(raw: String): Set<Int> =
    raw.split(',').mapNotNullTo(LinkedHashSet()) { it.trim().toIntOrNull()?.takeIf { index -> index >= 0 } }
