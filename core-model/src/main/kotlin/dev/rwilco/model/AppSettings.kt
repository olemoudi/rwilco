@file:UseSerializers(LocalTimeSerializer::class)

package dev.rwilco.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalTime

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Everything the person can set. Stored as one JSON blob; every field has a default and the
 * decoder ignores unknown keys, so adding a field never needs a migration.
 */
@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** When a date-only reminder rings. */
    val defaultTime: LocalTime = LocalTime.of(9, 0),
    /** Touch feedback in the UI; unrelated to a reminder's own VIBRATE action. */
    val haptics: Boolean = true,
    /**
     * The kind of trigger offered first when adding one. Null means "no favourite": the six
     * tiles come up in their usual order. Only the order and the mark change — every kind is
     * still one tap away, because the answer to "when?" is not the same twice running.
     */
    val defaultTriggerKind: TriggerKind? = null,
    /** What's-new sheet bookkeeping: the last versionCode whose notes were shown. */
    val lastSeenVersionCode: Int = 0,
)
