@file:UseSerializers(LocalTimeSerializer::class, LocalDateSerializer::class, DayOfWeekSerializer::class)

package dev.rwilco.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * A restriction on a trigger: the moment only counts if every condition attached to it holds.
 * "Al llegar a casa" becomes "al llegar a casa, y sólo si es por la tarde".
 *
 * Conditions are states, not events — they are asked "were you true at that moment?" — which is
 * what makes them safe to AND with anything. The discriminators are frozen like the triggers'.
 */
@Serializable
sealed interface Condition {

    /**
     * Between two times of day, on [days] (empty means every day) and on [date] (null means
     * every date those days allow).
     *
     * A window that ends before it starts crosses midnight, and the moment then belongs to the
     * day the window opened: 02:00 on Wednesday is inside "los martes, de 22:00 a 06:00".
     *
     * [date] is never typed by anybody — the "y sólo si" sheet offers hours and days, not dates
     * — and is the one thing a **dated** rule has to keep when it is folded into its siblings as
     * a state ([Trigger.asState]). "El domingo de 20:30 a 22:00, y a la vez en casa" is a state
     * about one Sunday evening; folded as hours alone it became every evening, and a set that
     * could not ring until Sunday rang on the Friday somebody walked through their own front
     * door. Written only when it is set, so nothing already on disk changes shape.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @SerialName("time_window")
    data class TimeWindow(
        val from: LocalTime,
        val to: LocalTime,
        val days: Set<DayOfWeek> = emptySet(),
        // Never written unless it is set, which is never: this one is synthesised at the moment
        // a rule is folded into its siblings and no sheet offers it. So the shape on disk does
        // not move for anything anybody has already typed — the frozen-shape test says so.
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val date: LocalDate? = null,
    ) : Condition

    /**
     * Between two days of the calendar, both included: "y sólo si estamos en agosto".
     *
     * The state [Trigger.DateRange] reads as, and the one condition that says nothing about the
     * hour — a day is either in the stretch or it is not. Days rather than instants on purpose:
     * "entre el 1 y el 15" means the whole of the 15th to everybody who says it.
     */
    @Serializable
    @SerialName("date_range")
    data class DateRange(val from: LocalDate, val to: LocalDate) : Condition

    /**
     * The days of the week and nothing else: "y sólo si es viernes".
     *
     * What [Trigger.Weekday] reads as, and the one thing [TimeWindow] could not say without
     * being given hours it was never asked for. Empty is every day — a fence that fences
     * nothing — because this is only ever synthesised from a trigger that has already been
     * checked, and a fence that silently never holds is the worse failure of the two.
     */
    @Serializable
    @SerialName("on_days")
    data class OnDays(val days: Set<DayOfWeek> = emptySet()) : Condition

    /**
     * The days of the month and nothing else: "y sólo el día 1".
     *
     * The one fence a weekday cannot put. "El día 1 de cada mes" is how rent, a filter and a
     * meter reading are actually said, and the app could only say it in "Vuelve" — where a
     * routine's answer is already taken by its span, so a routine had no way to ask its
     * question on a date at all (see `Prompt.kt`).
     *
     * A day past the end of a short month is that month's **last** day, which is the reading
     * [MonthlyOn.Day] has always had: "el 31" is the last day of every month, February
     * included, and picking 29, 30 and 31 together is one day in every month rather than three.
     * Empty is every day, for the same reason [OnDays] empty is: a fence that silently never
     * holds is the worse failure of the two.
     */
    @Serializable
    @SerialName("on_month_days")
    data class OnMonthDays(val days: Set<Int> = emptySet()) : Condition

    /**
     * Moving, at a speed between [minMps] and [maxMps]: "al salir de casa, y sólo si voy en coche".
     *
     * The fence that tells leaving *for the evening* from walking to the bins, and the reason a
     * routine can have a circle wide enough to be crossed reliably: "mover el coche" resets on
     * leaving a 150 m circle around the street, but only when the leaving was done at a speed
     * no walk reaches ([PlaceWatchPolicy.DRIVING_MPS]).
     *
     * **[maxMps] is what makes "andando" a word the app can honour.** A floor alone has only two
     * honest readings — moving at all, and moving fast — and a floor labelled "andando" says
     * something false, because a car clears it too. A ceiling turns the pair into a band, so the
     * three answers somebody actually means are all sayable: on foot, in a vehicle, or either.
     * See [MovingKind]. Exclusive at the top so the two bands meet without overlapping at
     * [PlaceWatchPolicy.DRIVING_MPS], and null is no ceiling — which is what every speed fence
     * written before this one has, and exactly what it meant.
     *
     * Read from the watch's own memory ([Fix.speedMps]), which is a speed worked out between
     * two looks — so like a place it cannot be asked about the future ([knownInAdvance]), and
     * unlike a place it is often not answerable at all: a crossing the phone's own geofence
     * reports can arrive with the last look an hour old. **What nobody can vouch for holds**,
     * as everywhere else here — except where holding it would make the app act on its own and
     * go quiet, which is the one place that is asked differently (`ReminderFiring.resetBy`,
     * [speedUnvouched]).
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @SerialName("moving")
    data class Moving(
        val minMps: Double = PlaceWatchPolicy.DRIVING_MPS,
        // Never written when it is not asked for, so no speed fence already on a phone changes
        // shape on disk over a field it does not use — the same reason a place carries its rate
        // that way, and what makes "the old ones mean what they always meant" true by
        // construction rather than by a migration.
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val maxMps: Double? = null,
    ) : Condition

    /**
     * Being somewhere, or not being there: "a las nueve, y sólo si estoy en casa".
     *
     * The state that matches [Trigger.Location]'s event, and the reason it is a condition and
     * not a trigger: arriving is something that happens once and can be waited for, whereas
     * being there is something that is simply true or not when somebody asks. Which is also why
     * it is the one condition nothing can answer in advance — see [holdsAt].
     */
    @Serializable
    @SerialName("at_place")
    data class AtPlace(
        val lat: Double,
        val lng: Double,
        val radiusM: Int,
        val label: String,
        /** True is "and only if I am there"; false is "and only if I am not". */
        val inside: Boolean = true,
    ) : Condition
}

/**
 * Whether the condition was true at [at], for a phone that was at [where].
 *
 * [where] is null when nobody knows — no fix, or one too old to speak for the moment — and a
 * place condition then **holds**. That is the house rule everywhere in this app: erring towards
 * ringing too often is the right way round, because the failure somebody notices is the one
 * that never arrives. A fix sloppier than the circle is the same thing said with a number: a
 * cell tower's kilometre of doubt cannot say which side of a two-hundred-metre line the phone
 * is on, and reading its centre as a confident "no" would silence "y sólo si estoy en casa"
 * for somebody sitting at home. A time window never needs any of it.
 */
fun Condition.holdsAt(at: Instant, zone: ZoneId, where: Fix? = null): Boolean = when (this) {
    is Condition.TimeWindow -> holdsAt(at.atZone(zone).toLocalDateTime())
    is Condition.DateRange -> at.atZone(zone).toLocalDate() in from..to
    is Condition.OnDays -> days.isEmpty() || at.atZone(zone).toLocalDate().dayOfWeek in days
    is Condition.OnMonthDays -> holdsOn(at.atZone(zone).toLocalDate())
    // Nobody could say how fast: it holds, which is the house rule and the safe way round for
    // everything that rings. The one caller that needs the other way round asks [speedUnvouched].
    is Condition.Moving -> speedAt(at, where)?.let { it >= minMps && (maxMps == null || it < maxMps) } ?: true
    is Condition.AtPlace -> {
        if (where == null || where.accuracyM > radiusM) true
        else (distanceMeters(where.lat, where.lng, lat, lng) <= radiusM) == inside
    }
}

/**
 * The same question of a wall-clock moment, which is all a window ever needed: the zone only
 * served to turn the instant into one. Asked directly wherever the moment is already on the
 * clock — a draw being narrowed to its fences, a gate being opened.
 */
fun Condition.TimeWindow.holdsAt(at: LocalDateTime): Boolean {
    val time = at.toLocalTime()
    val crossesMidnight = to <= from
    val inside = if (crossesMidnight) time >= from || time < to else time >= from && time < to
    // On the far side of midnight the moment still belongs to the day the window opened, which
    // is the day both the weekday and the date are asked of.
    val day = if (crossesMidnight && time < to) at.toLocalDate().minusDays(1) else at.toLocalDate()
    return inside && (days.isEmpty() || day.dayOfWeek in days) && (date == null || day == date)
}

/**
 * Whether [date] is one of the days of the month this fence names — clamped to the month's own
 * length, so "el 31" is the 28th in February and the 30th in April. See [Condition.OnMonthDays].
 */
fun Condition.OnMonthDays.holdsOn(date: LocalDate): Boolean =
    days.isEmpty() || days.any { date.dayOfMonth == it.coerceAtMost(date.lengthOfMonth()) }

/** The days a month day can be asked for: the calendar's own, and no thirty-second. */
val MONTH_DAYS = 1..31

/** Whether every condition on a rule held at [at]. */
fun List<Condition>.allHoldAt(at: Instant, zone: ZoneId, where: Fix? = null): Boolean =
    all { it.holdsAt(at, zone, where) }

/**
 * The conditions nothing can answer before the moment arrives.
 *
 * The scheduler works out when a rule will next fire by walking candidate moments and asking
 * each condition whether it would hold then ([nextFireOfRule]). A time window can answer that
 * about next Tuesday; a place cannot answer it about anywhere. So the scheduler leaves these
 * out and arms the alarm, and they are asked once, for real, when it goes off.
 */
val Condition.knownInAdvance: Boolean get() = this !is Condition.AtPlace && this !is Condition.Moving

/** The days a condition is limited to; empty when it says nothing about days. See [Trigger.namedDays]. */
val Condition.namedDays: Set<DayOfWeek>
    get() = when (this) {
        is Condition.TimeWindow -> days
        is Condition.OnDays -> days
        is Condition.DateRange, is Condition.AtPlace, is Condition.OnMonthDays, is Condition.Moving -> emptySet()
    }

/**
 * The speed [where] can vouch for at [at], or null when nothing can: no fix, no speed on it
 * (the first look of a run), or one too old to speak for the moment ([Fix.speaksFor]).
 */
fun speedAt(at: Instant, where: Fix?): Double? = where?.takeIf { it.speaksFor(at) }?.speedMps

/**
 * Whether a speed fence on this rule is one nothing can answer right now.
 *
 * Asked only where the app is about to act **on its own** — a place that counts a routine as
 * done (`TriggerRule.resets`) — because there the house rule points the wrong way: a fence let
 * through unchecked resets a count nobody asked to reset, and the reminder then says nothing
 * for three weeks, which is the failure nobody notices. Everywhere else an unanswerable fence
 * holds and the phone rings, which is the failure somebody can see and dismiss.
 */
fun List<Condition>.speedUnvouched(at: Instant, where: Fix?): Boolean =
    filterIsInstance<Condition.Moving>().any { speedAt(at, where) == null }

/** The circle a condition is about, for the conflict checks and for the watch to keep an eye on. */
val Condition.place: Condition.AtPlace? get() = this as? Condition.AtPlace

/**
 * The three answers the speed control offers, which are the three things somebody means by it.
 *
 * Here rather than in either sheet because two screens set this fence — "y sólo si" and the place
 * sheet — and a card, a sentence and a diagnostics line read it back. Four copies of "5 m/s means
 * a car" is three too many.
 *
 * [ANY] is the shape every speed fence written before the ceiling existed already had, so nothing
 * on a phone changes meaning: a bare floor at walking pace *is* "moving, either way".
 */
enum class MovingKind { ON_FOOT, DRIVING, ANY }

/** Which of the three a fence is. See [MovingKind]. */
val Condition.Moving.kind: MovingKind
    get() = when {
        maxMps != null -> MovingKind.ON_FOOT
        minMps >= PlaceWatchPolicy.DRIVING_MPS -> MovingKind.DRIVING
        else -> MovingKind.ANY
    }

/** The fence a chosen answer writes. The only place the numbers are named. */
fun movingOf(kind: MovingKind): Condition.Moving = when (kind) {
    MovingKind.ON_FOOT -> Condition.Moving(PlaceWatchPolicy.WALK_MPS, PlaceWatchPolicy.DRIVING_MPS)
    MovingKind.DRIVING -> Condition.Moving(PlaceWatchPolicy.DRIVING_MPS)
    MovingKind.ANY -> Condition.Moving(PlaceWatchPolicy.WALK_MPS)
}
