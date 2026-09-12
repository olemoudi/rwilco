package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** The anacron reading: the clock starts at the copy that worked, not at the one that was due. */
class BackupCadenceTest {

    private val now = Instant.parse("2026-08-26T10:00:00Z")

    @Test
    fun `nothing pending is never stale, however old the copy`() {
        // The remote has everything; an old copy of a phone nobody has written on is not a fault,
        // and a badge that said so would be crying about arithmetic.
        val old = now.minus(Duration.ofDays(90))
        assertEquals(BackupFreshness.FRESH, backupFreshness(enabled = true, lastRunAt = old, pending = 0, cadence = BackupCadence.DAILY, now = now))
    }

    @Test
    fun `changes waiting past three cadences are stale`() {
        val cadence = BackupCadence.DAILY
        val insideIt = now.minus(Duration.ofDays(3)).plusSeconds(60)
        assertEquals(BackupFreshness.FRESH, backupFreshness(true, insideIt, 2, cadence, now))
        assertEquals(BackupFreshness.STALE, backupFreshness(true, now.minus(Duration.ofDays(5)), 2, cadence, now))
    }

    @Test
    fun `an hourly cadence is not behind until two days have gone`() {
        // Three cadences of an hourly copy is three hours, and three hours of a poor connection
        // is not something to say out loud.
        val cadence = BackupCadence.HOURLY
        assertEquals(Duration.ofHours(48), backupStaleAfter(cadence))
        assertEquals(BackupFreshness.FRESH, backupFreshness(true, now.minus(Duration.ofHours(6)), 1, cadence, now))
        assertEquals(BackupFreshness.STALE, backupFreshness(true, now.minus(Duration.ofHours(50)), 1, cadence, now))
    }

    @Test
    fun `a vault that never ran is new rather than behind, and one turned off is neither`() {
        assertEquals(BackupFreshness.NEVER, backupFreshness(true, null, 3, BackupCadence.DAILY, now))
        assertEquals(BackupFreshness.OFF, backupFreshness(false, null, 3, BackupCadence.DAILY, now))
    }

    @Test
    fun `being behind is said once per window`() {
        val cadence = BackupCadence.DAILY
        assertTrue(backupNoticeDue(BackupFreshness.STALE, null, cadence, now), "never said before")
        assertFalse(backupNoticeDue(BackupFreshness.STALE, now.minus(Duration.ofDays(1)), cadence, now), "said yesterday")
        assertTrue(backupNoticeDue(BackupFreshness.STALE, now.minus(Duration.ofDays(4)), cadence, now), "a window later")
        assertFalse(backupNoticeDue(BackupFreshness.FRESH, null, cadence, now), "nothing to say")
    }

    @Test
    fun `never copied is due now`() {
        assertEquals(now, nextBackupDue(null, BackupCadence.WEEKLY, now))
        assertEquals(Duration.ZERO, backupDelay(null, BackupCadence.WEEKLY, now))
    }

    @Test
    fun `a copy that worked starts the clock`() {
        val ran = now.minus(Duration.ofHours(1))
        assertEquals(ran.plus(Duration.ofHours(4)), nextBackupDue(ran, BackupCadence.EVERY_4_HOURS, now))
        assertEquals(Duration.ofHours(3), backupDelay(ran, BackupCadence.EVERY_4_HOURS, now))
    }

    @Test
    fun `three days of failing and a copy on the fourth puts the weekly one on the eleventh`() {
        // The failures never move the anchor: what moves it is the run that went through.
        val started = Instant.parse("2026-08-01T09:00:00Z")
        val succeededOnTheFourth = started.plus(Duration.ofDays(4))
        assertEquals(
            started.plus(Duration.ofDays(11)),
            nextBackupDue(succeededOnTheFourth, BackupCadence.WEEKLY, succeededOnTheFourth.plusSeconds(1)),
        )
    }

    @Test
    fun `overdue is due now, not late by however long the phone was off`() {
        val ran = now.minus(Duration.ofDays(30))
        assertEquals(now, nextBackupDue(ran, BackupCadence.DAILY, now))
        assertEquals(Duration.ZERO, backupDelay(ran, BackupCadence.DAILY, now))
    }

    @Test
    fun `every cadence is a whole number of hours, four by default`() {
        assertEquals(BackupCadence.EVERY_4_HOURS, DEFAULT_BACKUP_CADENCE)
        assertEquals(listOf(1L, 4L, 8L, 24L, 72L, 168L), BackupCadence.entries.map { it.hours })
    }
}
