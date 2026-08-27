package dev.rwilco.diag

import dev.rwilco.model.Action
import dev.rwilco.model.AppSettings
import dev.rwilco.model.Condition
import dev.rwilco.model.DiagNote
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.Reminder
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.Status
import dev.rwilco.model.Presence
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/**
 * The report is the thing somebody pastes into a conversation when nothing rang, so what it
 * must do is two-sided: hold everything the firing path decided from, and hold nothing that
 * belongs to the person rather than to the bug.
 */
class DiagReportTest {

    private val now = Instant.parse("2026-08-26T10:00:00Z")

    private val reminder = Reminder(
        id = "0f1e2d3c-1111-4000-8000-000000000001",
        text = "Llamar a Marta por lo del piso",
        tags = listOf("familia", "piso"),
        rules = listOf(
            TriggerRule(Trigger.AtTime(LocalTime.of(9, 0), setOf(DayOfWeek.MONDAY))),
            TriggerRule(
                Trigger.Location(40.4169, -3.7035, 150, Presence.INSIDE, "Casa de mis padres"),
                listOf(Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0))),
            ),
        ),
        ruleMatch = RuleMatch.ANY,
        actions = setOf(Action.FULL_SCREEN, Action.VIBRATE),
        recurrence = Recurrence.After(6, RecurrenceUnit.HOURS),
        createdAt = now.minusSeconds(86_400),
        updatedAt = now.minusSeconds(3_600),
        lastFiredAt = now.minusSeconds(1_800),
        armedFor = now.plusSeconds(600),
        armedRule = 0,
    )

    private fun diagnostics(
        reminders: List<Reminder> = listOf(reminder),
        notes: List<DiagNote> = listOf(DiagNote(now, "fire", "r=0f1e2d3c dropped: nothing armed (armed=null)")),
    ) = Diagnostics(
        env = DiagEnv("0.15.0", 37, 35, "Google Pixel 8", "es-ES", "Europe/Madrid", now, Duration.ofMinutes(75)),
        permissions = DiagPermissions(
            notifications = true,
            anyChannelMuted = false,
            fullScreenIntent = false,
            exactAlarms = true,
            overlay = true,
            usageAccess = false,
            ignoresBatteryOptimisation = false,
            backgroundRestricted = false,
            dnd = "priority/policyAccess=n",
            alarmVolume = "7/15",
            location = "fine+background/services=on",
            playServices = true,
        ),
        settings = AppSettings(),
        reminders = reminders,
        vault = DiagVault(true, "ole/rwilco-vault", "EVERY_4_HOURS", false, now.minusSeconds(600), now.minusSeconds(600), 12_345, "UPLOADED", 3),
        notes = notes,
        watch = emptyList(),
    )

    @Test
    fun `it holds what a firing is decided from`() {
        val report = diagnostics().report()
        assertTrue(report.contains("#0f1e2d3c"), "the id, short enough to follow and long enough to be one")
        assertTrue(report.contains("armed=2026-08-26 12:10:00"), "in the phone's own zone")
        assertTrue(report.contains("fired=2026-08-26 11:30:00"))
        assertTrue(report.contains("time 09:00 d=1"))
        assertTrue(report.contains("place 150m INSIDE"))
        assertTrue(report.contains("if(win 18:00-22:00"))
        assertTrue(report.contains("rec=after 6 HOURS"))
        assertTrue(report.contains("next="))
        assertTrue(report.contains("fullScreenIntent=n"), "the grant that was refused")
        assertTrue(report.contains("dnd=priority"))
        assertTrue(report.contains("alarmVolume=7/15"))
        assertTrue(report.contains("dropped: nothing armed"), "the log itself")
        assertTrue(report.contains("cadence=EVERY_4_HOURS"))
    }

    @Test
    fun `it holds nothing that belongs to the person`() {
        val report = diagnostics().report()
        assertFalse(report.contains("Marta"), "no reminder text")
        assertFalse(report.contains("familia"), "no tag names")
        assertFalse(report.contains("Casa de mis padres"), "no place names")
        assertFalse(report.contains("40.4169"), "no exact coordinates")
        assertTrue(report.contains("@40.42,-3.70"), "a circle rounded to about a kilometre, so two can be told apart")
        assertTrue(report.contains("t=30 g=2"), "how much text and how many tags, which is all a bug needs")
    }

    @Test
    fun `a long list is cut, and says so`() {
        val many = (1..DIAG_REMINDERS + 5).map { reminder.copy(id = "id-$it", updatedAt = now.minusSeconds(it.toLong())) }
        val report = diagnostics(reminders = many).report()
        assertTrue(report.contains("-- reminders: 35 "), "the count is the whole truth")
        assertTrue(report.contains("... 5 more not listed"))
        assertFalse(report.contains("#id-35"), "the oldest by last edit are the ones dropped")
    }

    @Test
    fun `an empty phone still makes a report`() {
        val bare = diagnostics(reminders = emptyList(), notes = emptyList()).copy(vault = null)
        val report = bare.report()
        assertTrue(report.contains("-- reminders: 0 "))
        assertTrue(report.contains("-- log: last 0 of 0 --"))
        assertFalse(report.contains("-- backup --"), "nothing to say about a backup that is off")
        assertTrue(report.trim().endsWith("== end =="))
    }
}
