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

/** How the copy is standing: turned off, never made, keeping up, or behind with changes waiting. */
enum class BackupFreshness { OFF, NEVER, FRESH, STALE }

/** Nothing counts as behind before this, whatever the cadence: a nag is not a backup. */
val BACKUP_STALE_FLOOR: Duration = Duration.ofHours(48)

/** How long changes may go on waiting before the copy is worth saying something about. */
fun backupStaleAfter(cadence: BackupCadence): Duration = maxOf(cadence.span.multipliedBy(3), BACKUP_STALE_FLOOR)

/**
 * Whether the copy is keeping up — **asked of what is waiting, not of the copy's age**.
 *
 * A copy made a month ago with nothing written since is not a problem: the remote has everything,
 * which is what the fingerprint in `pendingChanges` already answers. What is a problem is a phone
 * carrying changes it has not managed to send, which is the shape a run failing on the network for
 * weeks takes — and the shape nothing said out loud, because only three outcomes raise attention
 * and a network failure is not one of them.
 */
fun backupFreshness(
    enabled: Boolean,
    lastRunAt: Instant?,
    pending: Int,
    cadence: BackupCadence,
    now: Instant,
): BackupFreshness = when {
    !enabled -> BackupFreshness.OFF
    lastRunAt == null -> BackupFreshness.NEVER
    pending <= 0 -> BackupFreshness.FRESH
    now > lastRunAt.plus(backupStaleAfter(cadence)) -> BackupFreshness.STALE
    else -> BackupFreshness.FRESH
}

/**
 * Whether being behind is worth saying out loud again: once per staleness window, so a fortnight
 * of failing runs is a word every few days and not one every time the worker wakes up.
 */
fun backupNoticeDue(
    freshness: BackupFreshness,
    lastNoticeAt: Instant?,
    cadence: BackupCadence,
    now: Instant,
): Boolean = freshness == BackupFreshness.STALE &&
    (lastNoticeAt == null || now > lastNoticeAt.plus(backupStaleAfter(cadence)))

/** The wait from [now] until the next copy is due; zero when it is owed already. */
fun backupDelay(lastRunAt: Instant?, cadence: BackupCadence, now: Instant): Duration {
    val due = nextBackupDue(lastRunAt, cadence, now)
    val wait = Duration.between(now, due)
    return if (wait.isNegative) Duration.ZERO else wait
}
