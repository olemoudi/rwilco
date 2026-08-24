package dev.rwilco.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Shared clock for the model tests: a Thursday afternoon in Madrid, late August 2026. */
object Fixtures {
    val zone: ZoneId = ZoneId.of("Europe/Madrid")
    val defaultTime: LocalTime = LocalTime.of(9, 0)

    /** 2026-08-27 15:00 in Madrid (13:00Z). */
    val now: Instant = local(2026, 8, 27, 15, 0)

    fun local(year: Int, month: Int, day: Int, hour: Int, minute: Int): Instant =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant()

    fun reminder(
        vararg triggers: Trigger,
        conditions: List<Condition> = emptyList(),
        id: String = "r1",
        text: String = "Water the plants",
        tags: List<String> = emptyList(),
        status: Status = Status.ACTIVE,
        updatedAt: Instant = now.minusSeconds(3600),
    ) = Reminder(
        id = id,
        text = text,
        tags = tags,
        rules = triggers.map { TriggerRule(it, conditions) },
        status = status,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )
}
