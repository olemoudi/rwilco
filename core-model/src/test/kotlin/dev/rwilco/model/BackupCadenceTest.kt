package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** The anacron reading: the clock starts at the copy that worked, not at the one that was due. */
class BackupCadenceTest {

    private val now = Instant.parse("2026-08-26T10:00:00Z")

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
