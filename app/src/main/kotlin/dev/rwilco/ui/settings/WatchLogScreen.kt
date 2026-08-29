package dev.rwilco.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rwilco.R
import dev.rwilco.model.FixTier
import dev.rwilco.model.NoteKind
import dev.rwilco.model.WatchNote
import dev.rwilco.model.WatchTally
import dev.rwilco.model.asEvents
import dev.rwilco.ui.components.EmptyState
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.format.rememberIs24h
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import java.time.Duration
import java.time.ZoneId
import java.util.Locale

/**
 * What the place watch has been doing, one line a look, newest first.
 *
 * This is a diagnostic screen and it is allowed to look like one: no colour that means anything
 * (amber is for what fires next and nothing here fires), no card per row, and every number in
 * the mono face because every number here is meant to be compared with the one above it. What
 * it is for is the question the battery graph cannot answer — *why* did it look then.
 */
@Composable
fun WatchLogScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val log by viewModel.watchLog.collectAsStateWithLifecycle()
    val polls by viewModel.pollsThisHour.collectAsStateWithLifecycle()
    val tally by viewModel.watchTally.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = spacing.sm)
                        .heightIn(min = Tokens.sizes.control),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                    Text(
                        text = stringResource(R.string.watch_log_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = spacing.sm),
                    )
                    if (log.notes.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearWatchLog) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.watch_log_clear))
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (log.notes.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
                EmptyState(title = stringResource(R.string.watch_log_empty), body = stringResource(R.string.watch_log_hint))
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = spacing.screen,
                end = spacing.screen,
                bottom = spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                RwilcoCard(modifier = Modifier.padding(top = spacing.md, bottom = spacing.sm)) {
                    Column(Modifier.padding(spacing.lg)) {
                        // The day first, because it is the answer; the hour after it, because it
                        // is the number the "looking too often" notice is about and somebody who
                        // arrived here from that notification is looking for it. It said zero on
                        // a quiet screen and read as the headline, which it is not.
                        TallyBlock(tally)
                        if (polls > 0) {
                            Spacer(Modifier.height(spacing.sm))
                            Text(
                                text = pluralStringResource(R.plurals.watch_log_summary, polls, polls),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(spacing.md))
                        Text(
                            text = stringResource(R.string.watch_log_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            itemsIndexed(log.notes.asEvents(), key = { index, note -> "${note.at.toEpochMilli()}-${note.kind}-$index" }) { _, note -> NoteRow(note) }
        }
    }
}

/**
 * The last day, said the way somebody would say it.
 *
 * It was a row of counts by radio tier — "39 wifi/red · 2 gratis · 15 ahorradas" — which is the
 * account the watch keeps of itself and not an answer to the only question a person actually has
 * here: *is this thing following me around, and is it costing me anything?* So it says the two
 * numbers that answer it — how often it looked, and how often it decided not to — and then what
 * set the pace, because the cadence is always the nearest place's ask and knowing which one that
 * was is the difference between a watch that is busy and a watch that is busy for a reason.
 *
 * The tiers are gone from here. They are still in the diagnostics report, which is where a
 * number that only means something to somebody reading this code belongs.
 */
@Composable
private fun TallyBlock(tally: WatchTally) {
    if (tally.looks == 0) return
    val spacing = Tokens.spacing
    val looked = tally.network + tally.gps + tally.coarse + tally.blind
    val saved = tally.cached + tally.rested
    Text(
        text = stringResource(R.string.watch_tally_title),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(spacing.xs))
    Text(
        text = pluralStringResource(R.plurals.watch_tally_looked, looked, looked),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (saved > 0) {
        Text(
            text = pluralStringResource(R.plurals.watch_tally_saved, saved, saved),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val paced = tally.pacedBy
    if (paced != null) {
        Spacer(Modifier.height(spacing.sm))
        Text(
            text = stringResource(R.string.watch_tally_paced_by, paced),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(spacing.sm))
    Text(
        text = stringResource(R.string.watch_tally_explain),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One line of the account, in words.
 *
 * This screen used to be the log itself: a kind ("lectura", "eco"), then every number the
 * cadence was argued from — metres to the line, speed, what the sensor felt, the still streak,
 * the battery, the radio tier. All of that is real and all of it is in the diagnostics report
 * (`DiagReport`, "-- place watch --"), which is where somebody debugging this goes. What was
 * left here was a diagnostic trace on a screen somebody opens to find out whether their phone is
 * watching them, written in words only its author could read — and, when a crossing arrived for
 * a circle the watch was no longer spending anything on, a raw geofence id: a UUID and a pin,
 * on screen, instead of "Club".
 *
 * So each line now says what happened and, under it, the little that helps: where you were, and
 * when it will look again. Nothing that needs the code open to be understood.
 */
@Composable
private fun NoteRow(note: WatchNote) {
    val spacing = Tokens.spacing
    val locale = currentLocale()
    val is24h = rememberIs24h()
    val zone = ZoneId.systemDefault()
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs)) {
        Text(
            text = TimeText.time(note.at.atZone(zone).toLocalTime(), is24h, locale),
            style = MonoStyles.date,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(Tokens.sizes.logTime),
        )
        Spacer(Modifier.width(spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = saidOf(note),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val under = detailOf(note, locale)
            if (under.isNotEmpty()) {
                Text(
                    text = under.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** What happened, in one sentence. */
@Composable
private fun saidOf(note: WatchNote): String = when (note.kind) {
    NoteKind.FIX -> stringResource(R.string.watch_said_look)
    // The two cheap ones say the same thing and differ in why, which is the line underneath.
    NoteKind.CACHE, NoteKind.REST -> stringResource(R.string.watch_said_free)
    NoteKind.BLIND -> stringResource(R.string.watch_said_blind)
    NoteKind.STIR -> stringResource(R.string.watch_said_stir)
    // The system re-reading a line the phone never crossed. It is on this screen because it is
    // the answer to "why did nothing ring when I got home?" — the app decided it already knew.
    NoteKind.ECHO -> note.place?.let { stringResource(R.string.watch_said_echo_at, it) }
        ?: stringResource(R.string.watch_said_echo)
    // A crossing is the only line here about something *you* did, and it is said that way. The
    // place may have no name left — its rule dealt with, its hours shut — and then it is said
    // without one rather than with an id.
    NoteKind.FENCE -> {
        val arrived = note.inside == true
        val place = note.place
        when {
            place != null && arrived -> stringResource(R.string.watch_said_arrived, place)
            place != null -> stringResource(R.string.watch_said_left, place)
            arrived -> stringResource(R.string.watch_said_arrived_somewhere)
            else -> stringResource(R.string.watch_said_left_somewhere)
        }
    }
}

/** The little under it that helps: why it was free, where you were, when it looks again. */
@Composable
private fun detailOf(note: WatchNote, locale: Locale): List<String> = buildList {
    when (note.kind) {
        NoteKind.CACHE -> add(stringResource(R.string.watch_why_known))
        NoteKind.REST -> add(stringResource(R.string.watch_why_still))
        NoteKind.BLIND -> add(stringResource(R.string.watch_why_no_signal))
        else -> Unit
    }
    // Where, said as a person would: inside the place, or a distance from it. A crossing has
    // already said both in its own line and adds nothing here.
    if (note.kind != NoteKind.FENCE && note.kind != NoteKind.ECHO) {
        val place = note.place
        val gap = note.gapM
        when {
            place != null && note.inside == true -> add(stringResource(R.string.watch_where_inside, place))
            place != null && gap != null -> add(stringResource(R.string.watch_where_near, spanOf(gap, locale), place))
            place != null -> add(place)
        }
    }
    note.waitS?.let { if (it > 0) add(stringResource(R.string.watch_next_look, spanOf(Duration.ofSeconds(it)))) }
}

/** "340 m", "4,2 km" — the same shape the Location card uses for the same numbers. */
@Composable
private fun spanOf(metres: Double, locale: Locale): String = if (metres < 1000) {
    stringResource(R.string.place_metres, metres.toInt())
} else {
    stringResource(R.string.place_kilometres, String.format(locale, "%.1f", metres / 1000))
}

/** "2 min", "1 h 30 min" — the same shape the Location card uses, without its "in". */
@Composable
private fun spanOf(span: Duration): String {
    val hours = span.toHours()
    val minutes = span.toMinutes() % 60
    return when {
        hours > 0 && minutes > 0 -> stringResource(R.string.countdown_hours, hours.toInt()) + " " + stringResource(R.string.countdown_minutes, minutes.toInt())
        hours > 0 -> stringResource(R.string.countdown_hours, hours.toInt())
        else -> stringResource(R.string.countdown_minutes, span.toMinutes().toInt().coerceAtLeast(1))
    }
}
