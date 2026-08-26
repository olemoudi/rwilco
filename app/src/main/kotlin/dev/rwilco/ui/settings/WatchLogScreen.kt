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
import dev.rwilco.model.NoteKind
import dev.rwilco.model.WatchNote
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
                        Text(
                            text = pluralStringResource(R.plurals.watch_log_summary, polls, polls),
                            style = MonoStyles.label,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(spacing.xs))
                        Text(
                            text = stringResource(R.string.watch_log_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            itemsIndexed(log.notes, key = { index, note -> "${note.at.toEpochMilli()}-${note.kind}-$index" }) { _, note -> NoteRow(note) }
        }
    }
}

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
                text = kindLabel(note),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val factors = factorsOf(note, locale)
            if (factors.isNotEmpty()) {
                Text(
                    text = factors.joinToString(" · "),
                    style = MonoStyles.date,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "read · GPS", "skipped", "no fix" — what the look came to, and what it cost. */
@Composable
private fun kindLabel(note: WatchNote): String {
    val kind = stringResource(
        when (note.kind) {
            NoteKind.FIX -> R.string.watch_kind_fix
            NoteKind.REST -> R.string.watch_kind_rest
            NoteKind.BLIND -> R.string.watch_kind_blind
            NoteKind.STIR -> R.string.watch_kind_stir
            NoteKind.FENCE -> R.string.watch_kind_fence
            NoteKind.ECHO -> R.string.watch_kind_echo
        },
    )
    if (note.kind != NoteKind.FIX) return kind
    val how = stringResource(if (note.precise) R.string.watch_field_gps else R.string.watch_field_network)
    return "$kind · $how"
}

/**
 * Everything the cadence was decided from, in the order it is argued in: where the line is, how
 * fast the phone was going, what the sensor made of it, how long it had been still, what was
 * left in the battery — and then the wait all of that came to.
 */
@Composable
private fun factorsOf(note: WatchNote, locale: Locale): List<String> = buildList {
    val gap = note.gapM?.let { spanOf(it, locale) }
    val place = note.place
    when {
        // A crossing knows the place and no distance; a stir knows the distance and no place.
        gap != null && place != null -> add(stringResource(R.string.watch_field_from, gap, place))
        gap != null -> add(gap)
        place != null -> add(place)
    }
    if (note.inside == true) add(stringResource(R.string.watch_field_inside))
    note.speedMps?.let { add(stringResource(R.string.watch_field_speed, String.format(locale, "%.1f", it))) }
    note.movedM?.let { if (it >= 1.0) add(stringResource(R.string.watch_field_moved, spanOf(it, locale))) }
    note.sensed?.let { add(stringResource(if (it) R.string.watch_field_sensed_yes else R.string.watch_field_sensed_no)) }
    if (note.stillStreak > 0) add(stringResource(R.string.watch_field_still, note.stillStreak))
    note.charge?.let { add(stringResource(R.string.watch_field_charge, it)) }
    note.waitS?.let { if (it > 0) add(stringResource(R.string.watch_field_next, spanOf(Duration.ofSeconds(it)))) }
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
