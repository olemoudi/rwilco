package dev.rwilco.model

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/** How often the encrypted copy is made, when nobody asks for one by hand. */
@Serializable
enum class BackupCadence(val hours: Long) {
    HOURLY(1),
    EVERY_4_HOURS(4),
    EVERY_8_HOURS(8),
    DAILY(24),
    EVERY_3_DAYS(72),
    WEEKLY(24 * 7),
    ;

    val span: Duration get() = Duration.ofHours(hours)
}

/** Four hours: the promise the backup was built to keep — lose at most an afternoon's writing. */
val DEFAULT_BACKUP_CADENCE = BackupCadence.EVERY_4_HOURS

/**
 * When the next copy is due, counted from the last one that **worked** — the way anacron counts.
 *
 * A run that fails is not a run: it is retried until it goes through, and only then does the
 * clock start again. Three days of failing, a copy on the fourth and a weekly cadence puts the
 * next one on the eleventh day, not on the seventh — which is the honest reading of "every
 * week", because a week without a copy is not a week that had one.
 *
 * Never run before: due now. Overdue: due now, and the moment the phone can, it goes.
 */
fun nextBackupDue(lastRunAt: Instant?, cadence: BackupCadence, now: Instant): Instant {
    val due = lastRunAt?.plus(cadence.span) ?: return now
    return if (due.isBefore(now)) now else due
}

/** The wait from [now] until the next copy is due; zero when it is owed already. */
fun backupDelay(lastRunAt: Instant?, cadence: BackupCadence, now: Instant): Duration {
    val due = nextBackupDue(lastRunAt, cadence, now)
    val wait = Duration.between(now, due)
    return if (wait.isNegative) Duration.ZERO else wait
}
