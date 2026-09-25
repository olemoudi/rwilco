package dev.rwilco.cheer

import dev.rwilco.data.ReminderRepository
import dev.rwilco.data.SettingsStore
import dev.rwilco.model.Cheer
import dev.rwilco.model.CheerKind
import dev.rwilco.model.CheerPick
import dev.rwilco.model.CheerShown
import dev.rwilco.model.achievements
import dev.rwilco.model.asSubject
import dev.rwilco.model.cheers
import dev.rwilco.model.globalStats
import dev.rwilco.model.mergeUnlocked
import dev.rwilco.model.pickCheer
import dev.rwilco.model.slotSeed
import kotlinx.coroutines.flow.first
import java.time.Clock

/**
 * Where a line of encouragement comes from, for the two places that say one: Home's line and the
 * silent notification. Reads the whole history once, works out what is true and good to say
 * (`cheers`), keeps any milestone the reading proves on the way (so a line can be about one earned
 * this morning even if the Hechos screen has not been opened since), and picks against what was
 * said before. Suspend and off the main thread by the caller's choosing; nothing here is a screen.
 */
class Cheering(
    private val repository: ReminderRepository,
    private val settings: SettingsStore,
    private val store: CheerStore,
    private val clock: Clock,
) {

    /** Every true and good thing there is to say right now. */
    suspend fun candidates(): List<Cheer> {
        val now = clock.instant()
        val subjects = repository.allNow().map { it.asSubject() }.associateBy { it.id }
        val stats = globalStats(repository.allHistoryNow(), subjects, now, clock.zone)
        val derived = achievements(stats.tallies, now, clock.zone)
        runCatching { settings.keepUnlocked(derived) }
        val earned = mergeUnlocked(settings.settings.first().achievements, derived)
        return cheers(stats, earned, now, clock.zone)
    }

    /**
     * Home's line for [slot]: the same one all through the slot while it holds, remembered the
     * first time it is drawn so the next slot moves on.
     */
    suspend fun lineFor(slot: String, variants: Map<CheerKind, Int>): CheerPick? {
        val shown = store.shown()
        val pick = pickCheer(candidates(), shown, clock.instant(), variants, slotSeed(slot), slot) ?: return null
        if (shown.none { it.slot == slot && it.key == pick.cheer.key }) {
            store.remember(CheerShown(pick.cheer.key, pick.cheer.kind, pick.variant, clock.instant(), pick.cheer.subjectId, slot))
        }
        return pick
    }

    /** The notification's line, or null when there is nothing new to say; remembered when drawn. */
    suspend fun notice(variants: Map<CheerKind, Int>): CheerPick? {
        val now = clock.instant()
        val pick = pickCheer(candidates(), store.shown(), now, variants, seed = now.toEpochMilli()) ?: return null
        store.remember(CheerShown(pick.cheer.key, pick.cheer.kind, pick.variant, now, pick.cheer.subjectId, notified = true))
        return pick
    }
}
