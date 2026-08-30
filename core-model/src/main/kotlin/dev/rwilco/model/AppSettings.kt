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
    /**
     * And when it stops. Sunday evening: the lie-in is still the weekend's but the early night
     * is the week's, which is what makes a Sunday feel like a Sunday. Together with
     * [weekendDay]/[weekendTime] this is the span [AwakeHours] is read against — see [awakeOn].
     */
    val weekendEndDay: DayOfWeek = DayOfWeek.SUNDAY,
    val weekendEndTime: LocalTime = LocalTime.of(22, 0),
    /**
     * The hours this person is up, which is the window "at random during the day" draws from.
     * Ignored the moment a trigger is given a time of its own: an explicit hour is somebody
     * saying exactly when, and nothing here is allowed to argue with it.
     */
    val awake: AwakeHours = AwakeHours(),
    /** What's-new sheet bookkeeping: the last versionCode whose notes were shown. */
    val lastSeenVersionCode: Int = 0,
    /** Places named once and offered whole whenever a rule needs one: home, work, the gym. */
    val savedPlaces: List<SavedPlace> = emptyList(),
    /**
     * Stretches of the day named once and offered wherever one is asked for: "a la hora de
     * comer", "por la tarde". The same idea as [savedPlaces] — a thing you answer over and over
     * is worth answering once — and, like a place, what a trigger keeps is the two times rather
     * than a reference, so renaming or deleting one never reaches back into a reminder.
     */
    val savedWindows: List<SavedWindow> = emptyList(),
    /**
     * What a blank reminder starts with. The old default is still the default; this is for
     * somebody who never wants a sound, or always wants the screen.
     */
    @Serializable(with = TolerantActions::class)
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
    /**
     * What a reminder sounds like: the phone's own alarm tone, one of the app's chimes, or a
     * file. The phone's by default, and deliberately — the chimes are *subtler* than an alarm
     * tone, and changing what somebody's alarm sounds like without asking is how an alarm gets
     * slept through. They are one tap away in Settings.
     */
    @Serializable(with = TolerantSound::class)
    val alertSound: AlertSound = AlertSound.System,
    /**
     * A second tone for the reminders that keep asking ([Action.SOUND_UNTIL_ANSWERED]), or null
     * to use [alertSound] for both.
     *
     * Null and not a default of its own, because the two are not equal choices: one of them is
     * *the* sound and the other is a distinction somebody draws on purpose. A default would also
     * have quietly changed what half of everybody's reminders sound like on the update, which is
     * the one thing an alarm is never allowed to do. See [soundFor].
     */
    @Serializable(with = TolerantSoundOrNull::class)
    val insistentSound: AlertSound? = null,
    /** For [Action.SOUND_UNTIL_ANSWERED]: how many plays in a round, and how far apart. */
    val soundPlays: Int = SoundLimits.DEFAULT_PLAYS,
    val soundGapMinutes: Int = SoundLimits.DEFAULT_GAP_MINUTES,
    /**
     * Two full-screen reminders within moments of each other: one after the other (the next
     * appears the instant the first is answered) or all at once, the screen split into a
     * strip per reminder. Sequential by default: it is what a single alert already looks
     * like, and nothing changes under a thumb that is mid-answer.
     */
    val alertStacking: AlertStacking = AlertStacking.SEQUENTIAL,
    /**
     * Check for and download updates only where the data is not being paid for by the megabyte.
     * The APK is fifty megabytes; the check itself is a hundred bytes, but downloading what it
     * finds is what somebody notices on their bill. "Buscar ahora" is always allowed: a tap is
     * somebody deciding for themselves.
     */
    val updatesWifiOnly: Boolean = false,
    /**
     * Send a reminder's sound to the headphones when any are connected, instead of letting it
     * out of the phone's own speaker as an alarm otherwise would. On, because somebody wearing
     * headphones is the person most likely to miss the speaker — and off is the honest setting
     * for anybody whose earbuds live in a drawer, where a reminder would play to nobody.
     */
    val alertToHeadphones: Boolean = true,
    /**
     * How long the safety net waits before saying anything, and what shapes it will not be
     * armed on at all. See [SafetyNetSettings], which holds the whole of the reasoning.
     */
    val safetyNet: SafetyNetSettings = SafetyNetSettings(),
    /** How long [Snooze.CUSTOM] is: the one snooze length that is the person's own. */
    val snoozeCustomMinutes: Int = DEFAULT_SNOOZE_MINUTES,
    /**
     * The two offers on the notification, which has room for no more (three actions, and
     * "hecho" is one). The alert screen offers every [Snooze] regardless.
     *
     * **Names, not the enum.** A settings blob is decoded all at once, so an unreadable member
     * here — a vault taken to an older build, a name that changes one day — would not cost a
     * snooze offer, it would reset every setting there is. Read back through
     * [notificationSnoozeOffers], which drops what it does not recognise.
     */
    val notificationSnoozes: List<String> = DEFAULT_NOTIFICATION_SNOOZES.map { it.name },
    /**
     * The alert problems Home's strip has been told "not now" about, by name. Cleared whenever
     * everything is granted, so a phone fixed and then broken again is told again.
     */
    val dismissedAlertProblems: Set<String> = emptySet(),
)

/** How the alert screen holds more than one reminder. See [AppSettings.alertStacking]. */
@Serializable
enum class AlertStacking { SEQUENTIAL, STRIPS }

/** Nine in the morning, until somebody says otherwise. */
val DEFAULT_DAY_START: LocalTime = LocalTime.of(9, 0)
