package dev.rwilco.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.rwilco.model.Reminder
import dev.rwilco.model.ReminderCodec
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
    val triggers: String,
    val actions: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val doneAt: Long?,
    val snoozedUntil: Long? = null,
    val lastFiredAt: Long? = null,
    val armedFor: Long? = null,
)

fun ReminderEntity.toDomain(): Reminder = Reminder(
    id = id,
    text = text,
    tags = ReminderCodec.decodeTags(tags),
    triggers = ReminderCodec.decodeTriggers(triggers),
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
)

fun Reminder.toEntity(): ReminderEntity = ReminderEntity(
    id = id,
    text = text,
    tags = ReminderCodec.encodeTags(tags),
    triggers = ReminderCodec.encodeTriggers(triggers),
    actions = ReminderCodec.encodeActions(actions),
    status = status.name,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    doneAt = doneAt?.toEpochMilli(),
    snoozedUntil = snoozedUntil?.toEpochMilli(),
    lastFiredAt = lastFiredAt?.toEpochMilli(),
    armedFor = armedFor?.toEpochMilli(),
)
