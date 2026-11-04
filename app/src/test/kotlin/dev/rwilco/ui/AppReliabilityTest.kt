package dev.rwilco.ui

import dev.rwilco.model.Condition
import dev.rwilco.model.DayShape
import dev.rwilco.model.Presence
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceFrom
import dev.rwilco.model.RecurrenceHour
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.RelativeDay
import dev.rwilco.model.RelativeUnit
import dev.rwilco.model.Reminder
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.Status
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.model.adoptingHourOf
import dev.rwilco.model.questionsNeverAsked
import dev.rwilco.model.withSpanOf
import dev.rwilco.ui.editor.Draft
import dev.rwilco.ui.editor.EditorUiState
import dev.rwilco.ui.editor.SetCaveat
import dev.rwilco.ui.editor.commitTrigger
import dev.rwilco.ui.editor.setCaveatOf
import dev.rwilco.ui.editor.setRuleMatch
import dev.rwilco.ui.editor.toReminder
import dev.rwilco.ui.editor.widenMoment
import dev.rwilco.ui.editor.widenedMoment
import dev.rwilco.ui.home.ReminderCardUi
import dev.rwilco.ui.home.buildHomeState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The app's own pure pieces of the trigger-contract round (2026-11-04), held to what the model
 * does underneath them: the editor's offers and caveats, Home's "quitar el posponer", the draft's
 * "mañana" at night, a routine's question that is never put, and the words in both languages.
 */
class AppReliabilityTest {

    private val zone = ZoneId.of("Europe/Madrid")
    private val defaultTime = LocalTime.of(9, 0)
    private val now: Instant = local(2026, 11, 4, 15, 0)
    private val blank = EditorUiState(loaded = true)
    private val door = Trigger.Location(40.4168, -3.7038, 150, Presence.INSIDE, "Casa", onCrossing = true)
    private val home = door.copy(onCrossing = false)
    private val away = home.copy(presence = Presence.OUTSIDE, label = "Oficina")
    private val atHome = Condition.AtPlace(home.lat, home.lng, home.radiusM, "Casa")

    private fun local(year: Int, month: Int, day: Int, hour: Int, minute: Int): Instant =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant()

    private fun t(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)

    private fun cardOf(reminder: Reminder, at: Instant = now): ReminderCardUi {
        val state = buildHomeState(listOf(reminder), defaultTime, at, zone, selectedTag = null)
        return (listOfNotNull(state.hero?.card) + state.sections.flatMap { it.cards }).single { it.id == reminder.id }
    }

    // The caveat on the next-ring line.

    @Test
    fun `the caveat names the state place under a la vez, and the side it asks for`() {
        val hourAway = listOf(TriggerRule(Trigger.TimeOfDay(t(19))), TriggerRule(away))
        assertEquals(SetCaveat.IfThere("Oficina", inside = false), setCaveatOf(hourAway, RuleMatch.TOGETHER))
        // A doorway is a moment, not somewhere to be at that minute: nothing to warn about.
        assertNull(setCaveatOf(listOf(TriggerRule(Trigger.TimeOfDay(t(19))), TriggerRule(door)), RuleMatch.TOGETHER))
        assertNull(setCaveatOf(listOf(TriggerRule(home)), RuleMatch.TOGETHER), "one rule is no set")
        assertEquals(SetCaveat.WhenAllHappened, setCaveatOf(listOf(TriggerRule(Trigger.TimeOfDay(t(19))), TriggerRule(door)), RuleMatch.ALL))
        assertNull(setCaveatOf(hourAway, RuleMatch.ANY))
    }

    // The offer to turn the hour into a stretch.

    @Test
    fun `a late hour widens across midnight and keeps its days and its own conditions`() {
        val fridays = setOf(DayOfWeek.FRIDAY)
        val draft = Draft(
            rules = listOf(TriggerRule(Trigger.TimeOfDay(t(22), fridays), conditions = listOf(Condition.OnMonthDays(setOf(1)))), TriggerRule(home)),
            ruleMatch = RuleMatch.TOGETHER,
        )
        assertEquals(0 to Trigger.Interval(t(22), t(1), fridays), draft.widenedMoment())
        val widened = blank.copy(draft = draft).widenMoment().draft
        assertEquals(Trigger.Interval(t(22), t(1), fridays), widened.rules[0].trigger)
        assertEquals(listOf<Condition>(Condition.OnMonthDays(setOf(1))), widened.rules[0].conditions, "the rule's own fences stay on it")
        assertEquals(TriggerRule(home), widened.rules[1])
    }

    @Test
    fun `the stretch is only offered for one hour beside a state under a la vez`() {
        val pair = listOf(TriggerRule(Trigger.TimeOfDay(t(19))), TriggerRule(home))
        assertNull(Draft(rules = pair, ruleMatch = RuleMatch.ANY).widenedMoment())
        assertNull(Draft(rules = pair, ruleMatch = RuleMatch.ALL).widenedMoment())
        assertNull(Draft(rules = listOf(TriggerRule(Trigger.TimeOfDay(t(19))), TriggerRule(door)), ruleMatch = RuleMatch.TOGETHER).widenedMoment(), "a doorway rings on its own")
        assertNull(
            Draft(rules = pair + TriggerRule(Trigger.TimeOfDay(t(21))), ruleMatch = RuleMatch.TOGETHER).widenedMoment(),
            "two hours: which one would it widen",
        )
        val unchanged = blank.copy(draft = Draft(rules = pair, ruleMatch = RuleMatch.ANY))
        assertEquals(unchanged, unchanged.widenMoment(), "nothing on offer, nothing changes")
    }

    // An edit that turns a moment into a state, or back.

    @Test
    fun `an hour made a stretch beside a doorway asks a la vez, and made an hour again asks cualquiera`() {
        val pair = blank.commitTrigger(null, Trigger.TimeOfDay(t(19)), now = now, zone = zone).commitTrigger(null, door, now = now, zone = zone)
        assertEquals(RuleMatch.ANY, pair.draft.ruleMatch, "two moments")
        val stretch = pair.commitTrigger(0, Trigger.Interval(t(19), t(22)), now = now, zone = zone)
        assertEquals(RuleMatch.TOGETHER, stretch.draft.ruleMatch, "a stretch and a doorway are the sentence a la vez is for")
        val hourAgain = stretch.commitTrigger(0, Trigger.TimeOfDay(t(20)), now = now, zone = zone)
        assertEquals(RuleMatch.ANY, hourAgain.draft.ruleMatch, "a la vez over two moments would never ring")
    }

    @Test
    fun `an edit that keeps the kind of the rule leaves the answer somebody chose`() {
        val chosen = blank.commitTrigger(null, Trigger.TimeOfDay(t(19)), now = now, zone = zone)
            .commitTrigger(null, home, now = now, zone = zone)
            .setRuleMatch(RuleMatch.ANY)
        val later = chosen.commitTrigger(0, Trigger.TimeOfDay(t(20)), now = now, zone = zone)
        assertEquals(RuleMatch.ANY, later.draft.ruleMatch, "a new hour is no flip")
        val elsewhere = chosen.commitTrigger(1, away, now = now, zone = zone)
        assertEquals(RuleMatch.ANY, elsewhere.draft.ruleMatch, "a state for a state is no flip either")
    }

    @Test
    fun `a routine stays cualquiera whatever its rules are flipped to`() {
        val routine = blank.copy(draft = Draft(recurrence = Recurrence.Since(3, RecurrenceUnit.DAYS)))
            .commitTrigger(null, Trigger.TimeOfDay(t(19)), now = now, zone = zone)
            .commitTrigger(null, door, now = now, zone = zone)
        val flipped = routine.commitTrigger(1, home, now = now, zone = zone)
        assertEquals(RuleMatch.ANY, flipped.draft.ruleMatch)
    }

    // Home's "quitar el posponer".

    @Test
    fun `taking the snooze off a date already gone says it leaves nothing to ring`() {
        val gone = Reminder(
            id = "gone", text = "x",
            rules = listOf(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 11, 3, 9, 0)))),
            createdAt = local(2026, 11, 1, 12, 0), updatedAt = local(2026, 11, 1, 12, 0),
            snoozedUntil = local(2026, 11, 6, 9, 0),
        )
        assertTrue(cardOf(gone).unsnoozeLeavesNothing)
        assertFalse(cardOf(gone.copy(snoozedUntil = null)).unsnoozeLeavesNothing, "nothing to take off")
        assertFalse(cardOf(gone.copy(status = Status.PAUSED)).unsnoozeLeavesNothing, "a paused card offers no snooze to take off")
    }

    @Test
    fun `taking the snooze off a desde el hecho rung and unanswered leaves its come-back`() {
        val rung = local(2026, 11, 3, 9, 0)
        val pills = Reminder(
            id = "pills", text = "x",
            rules = listOf(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 11, 3, 9, 0)))),
            recurrence = Recurrence.After(1, RecurrenceUnit.DAYS, RecurrenceFrom.DEALT),
            createdAt = local(2026, 11, 1, 12, 0), updatedAt = local(2026, 11, 1, 12, 0),
            lastFiredAt = rung, lastFiredRule = 0,
            snoozedUntil = local(2026, 11, 6, 9, 0),
        )
        assertFalse(cardOf(pills).unsnoozeLeavesNothing, "it comes back a span after the ring")
    }

    @Test
    fun `taking the snooze off a place reminder leaves the place`() {
        val placed = Reminder(
            id = "placed", text = "x", rules = listOf(TriggerRule(door)),
            createdAt = local(2026, 11, 1, 12, 0), updatedAt = local(2026, 11, 1, 12, 0),
            snoozedToPlace = away.copy(onCrossing = true),
        )
        assertFalse(cardOf(placed).unsnoozeLeavesNothing)
    }

    // "Mañana" past midnight, as the draft settles it.

    @Test
    fun `a draft saved at a quarter past midnight settles tomorrow on the morning about to come`() {
        val tomorrowAtTen = Draft(text = "x", rules = listOf(TriggerRule(Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.DAYS), time = t(10)))))
        val night = local(2026, 11, 4, 0, 15)
        val saved = tomorrowAtTen.toReminder("n", night, night, Status.ACTIVE, zone = zone, shape = DayShape.DEFAULT)
        assertEquals(Trigger.AtDateTime(LocalDateTime.of(2026, 11, 4, 10, 0)), saved.rules.single().trigger)
        val afternoon = tomorrowAtTen.toReminder("a", now, now, Status.ACTIVE, zone = zone, shape = DayShape.DEFAULT)
        assertEquals(Trigger.AtDateTime(LocalDateTime.of(2026, 11, 5, 10, 0)), afternoon.rules.single().trigger)
        // "Hoy" stays on the calendar: a quarter past midnight is already today.
        val today = Draft(text = "x", rules = listOf(TriggerRule(Trigger.RelativeDate(RelativeDay.In(0, RelativeUnit.DAYS), time = t(10)))))
        assertEquals(Trigger.AtDateTime(LocalDateTime.of(2026, 11, 4, 10, 0)), today.toReminder("h", night, night, Status.ACTIVE, zone = zone).rules.single().trigger)
    }

    // A routine's question the editor warns about, from the draft it is drawn from.

    @Test
    fun `a daily question after a one-day routine's deadline is never asked, and one inside its last quarter is`() {
        val evening = Draft(text = "x", rules = listOf(TriggerRule(Trigger.TimeOfDay(t(21)))), recurrence = Recurrence.Since(1, RecurrenceUnit.DAYS))
        val drafted = evening.toReminder("rt", now, now, Status.ACTIVE, zone = zone)
        assertEquals(listOf(0), drafted.questionsNeverAsked(zone, defaultTime))
        val early = evening.copy(rules = listOf(TriggerRule(Trigger.TimeOfDay(t(8)))))
        assertEquals(emptyList<Int>(), early.toReminder("rt", now, now, Status.ACTIVE, zone = zone).questionsNeverAsked(zone, defaultTime))
        // A question on some days only is not "never": the warning keeps out of it.
        val mondays = evening.copy(rules = listOf(TriggerRule(Trigger.TimeOfDay(t(21), setOf(DayOfWeek.MONDAY)))))
        assertEquals(emptyList<Int>(), mondays.toReminder("rt", now, now, Status.ACTIVE, zone = zone).questionsNeverAsked(zone, defaultTime))
    }

    // Picking a span under a dated rule, as pickRecurrencePreset chains it.

    @Test
    fun `picking every month under an evening date takes the evening, and nothing else does`() {
        val month = Recurrence.After(1, RecurrenceUnit.MONTHS)
        val evening = listOf(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 11, 26, 20, 0))))
        assertEquals(RecurrenceHour.At(t(20)), (Recurrence.None.withSpanOf(month).adoptingHourOf(evening) as Recurrence.After).hour)
        // Counted from the hecho, the anchor already chosen stays chosen, and the hour comes too.
        val fromDone = Recurrence.After(1, RecurrenceUnit.WEEKS, RecurrenceFrom.DEALT)
        val picked = fromDone.withSpanOf(month).adoptingHourOf(evening) as Recurrence.After
        assertEquals(RecurrenceFrom.DEALT, picked.from)
        assertEquals(RecurrenceHour.At(t(20)), picked.hour)
        // An hour somebody chose is theirs.
        val chosen = Recurrence.After(1, RecurrenceUnit.WEEKS, hour = RecurrenceHour.At(t(7)))
        assertEquals(RecurrenceHour.At(t(7)), (chosen.withSpanOf(month).adoptingHourOf(evening) as Recurrence.After).hour)
        // Two dated rules at two hours, or a place beside the date: no one hour to take.
        val two = evening + TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 11, 27, 8, 0)))
        assertEquals(RecurrenceHour.DayStart, (Recurrence.None.withSpanOf(month).adoptingHourOf(two) as Recurrence.After).hour)
        val placed = evening + TriggerRule(door)
        assertEquals(RecurrenceHour.DayStart, (Recurrence.None.withSpanOf(month).adoptingHourOf(placed) as Recurrence.After).hour)
        // A span in hours is exact, and has no hour of the day to take.
        assertEquals(Recurrence.After(6, RecurrenceUnit.HOURS), Recurrence.None.withSpanOf(Recurrence.After(6, RecurrenceUnit.HOURS)).adoptingHourOf(evening))
    }

    // The words of the round, in both languages.

    private val roundKeys = listOf(
        "notif_swipe_not_done", "home_cancel_snooze_hint_gone", "snooze_skips", "snooze_skips_many",
        "editor_warning_question_never_asked", "trigger_arrive_at_after", "trigger_leave_from_after",
        "recur_hour_once_note", "card_recurrence_from_calendar_rules", "editor_will_ring_when_all",
        "editor_will_ring_if_at", "editor_will_ring_if_away", "editor_widen_moment", "random_days_hint",
        "settings_default_time", "settings_default_time_hint", "place_watch_how", "place_on_arrival_after",
        "place_on_leaving_after", "place_means_inside", "place_means_outside", "settings_awake_hint",
        "deadline_hint_once", "editor_sentence_deadline_window",
    )

    private fun stringsOf(relativePath: String): String =
        sequenceOf(File(relativePath), File("app/$relativePath")).first { it.exists() }.readText()

    /** The body of a string or plurals entry by its name, or null when the file has none. */
    private fun entryOf(xml: String, name: String): String? =
        Regex("<(string|plurals) name=\"$name\"[^>]*>(.*?)</(string|plurals)>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(2)

    private fun placeholders(text: String): List<String> =
        Regex("%(\\d+\\$)?[sd]").findAll(text).map { it.value }.toList().sorted()

    @Test
    fun `every string of the round has the same placeholders in English and Spanish`() {
        val english = stringsOf("src/main/res/values/strings.xml")
        val spanish = stringsOf("src/main/res/values-es/strings.xml")
        for (key in roundKeys) {
            val en = entryOf(english, key) ?: error("$key missing in English")
            val es = entryOf(spanish, key) ?: error("$key missing in Spanish")
            assertEquals(placeholders(en).distinct(), placeholders(es).distinct(), "$key: the placeholders differ")
        }
    }

    @Test
    fun `the skips plural says one and other in both languages`() {
        for (path in listOf("src/main/res/values/strings.xml", "src/main/res/values-es/strings.xml")) {
            val body = entryOf(stringsOf(path), "snooze_skips") ?: error("no snooze_skips in $path")
            assertTrue("quantity=\"one\"" in body && "quantity=\"other\"" in body, "$path: $body")
        }
    }

    @Test
    fun `no string of the round leaves an apostrophe unescaped`() {
        val english = stringsOf("src/main/res/values/strings.xml")
        val notes = Regex("<string-array name=\"whats_new_0_20[0-4]_0\">(.*?)</string-array>", RegexOption.DOT_MATCHES_ALL)
            .findAll(english).map { it.groupValues[1] }.toList()
        assertEquals(4, notes.size, "the four release notes of the round")
        for (text in roundKeys.mapNotNull { entryOf(english, it) } + notes) {
            assertFalse(Regex("(?<!\\\\)'").containsMatchIn(text), "an apostrophe aapt would reject: $text")
        }
    }
}
