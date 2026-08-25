@file:UseSerializers(LocalTimeSerializer::class, DayOfWeekSerializer::class)

package dev.rwilco.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
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
    /**
     * Or let the tiles sort themselves by what actually gets used, which is a favourite nobody
     * has to keep choosing. Wins over [defaultTriggerKind] when both are set, and the two are
     * offered as one row of answers, so that only happens to a settings file edited by hand.
     */
    val popularTriggersFirst: Boolean = false,
    /**
     * What "el finde" means to this person when they postpone something to it. Friday evening
     * by default: the weekend starts when the week stops asking, not on Saturday morning.
     */
    val weekendDay: DayOfWeek = DayOfWeek.FRIDAY,
    val weekendTime: LocalTime = LocalTime.of(20, 30),
    /** What's-new sheet bookkeeping: the last versionCode whose notes were shown. */
    val lastSeenVersionCode: Int = 0,
    /** Places named once and offered whole whenever a rule needs one: home, work, the gym. */
    val savedPlaces: List<SavedPlace> = emptyList(),
    /**
     * What a blank reminder starts with. The old default is still the default; this is for
     * somebody who never wants a sound, or always wants the screen.
     */
    val defaultActions: Set<Action> = DEFAULT_ACTIONS,
    /** Reminders kept by shape, under a name: see [Preset]. */
    val presets: List<Preset> = emptyList(),
    /** Phrases dismissed from the "or reuse one" offers; the reminders that used them stay. */
    val hiddenTexts: List<String> = emptyList(),
    /**
     * What "the next day" means: the hour a recurrence measured in days, weeks or months lands
     * on. Never earlier — a reminder dealt with at midnight comes back in the morning, not at
     * one minute past.
     */
    val dayStart: LocalTime = DEFAULT_DAY_START,
    /** Recurrences kept under a name, plus the four everybody needs before they need any others. */
    val recurrencePresets: List<RecurrencePreset> = defaultRecurrencePresets(),
    /**
     * Say so when the place watch reads the phone's location more than
     * [PlaceWatchPolicy.BUSY_POLLS] times in an hour. Off by default: it is a notification about
     * the app's own behaviour, which is a thing to go looking for and not a thing to be handed.
     */
    val busyWatchNotice: Boolean = false,
    /**
     * What a reminder feels like: how hard it buzzes and whether it buzzes in one stretch. The
     * default is what the app did before there was a choice, so nobody's phone changes
     * character by updating. See [waveformFor] for the minute it is capped at.
     */
    val vibration: VibrationPattern = VibrationPattern(),
    /** What a reminder sounds like: one of the app's own chimes, the phone's, or a file. */
    val alertSound: AlertSound = AlertSound.Bundled(Chime.ALERT),
    /** For [Action.SOUND_UNTIL_ANSWERED]: how many plays in a round, and how far apart. */
    val soundPlays: Int = SoundLimits.DEFAULT_PLAYS,
    val soundGapMinutes: Int = SoundLimits.DEFAULT_GAP_MINUTES,
)

/** Nine in the morning, until somebody says otherwise. */
val DEFAULT_DAY_START: LocalTime = LocalTime.of(9, 0)
