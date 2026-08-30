package dev.rwilco.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.rwilco.R
import dev.rwilco.data.FiringEvent
import dev.rwilco.data.FiringKind
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.dayWord
import dev.rwilco.ui.format.rememberWords
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What has happened to this reminder, newest first, in the words the app uses for it: *ayer
 * 09:00 · sonó*, *ayer 09:04 · hecho*, *hoy 20:45 · pospuesto hasta las 22:45*.
 *
 * The row keeps one of each stamp and the diagnostics ring keeps a week of everything, so
 * "¿sonó ayer?" — the question under half the reports from the phone — had no answer a person
 * could find. This is that answer, on the reminder itself, where somebody looking for it looks.
 */
@Composable
fun HistoryList(history: List<FiringEvent>, today: LocalDate, zone: ZoneId) {
    val words = rememberWords()
    val spacing = Tokens.spacing
    Column {
        for (event in history) {
            val at = event.at.atZone(zone)
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs)) {
                Text(
                    text = dayWord(words, at.toLocalDate(), today) + " " + TimeText.time(at.toLocalTime(), words.is24h, words.locale),
                    style = MonoStyles.date,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(Tokens.sizes.historyStamp),
                )
                Spacer(Modifier.width(spacing.md))
                Text(
                    text = eventWords(event, today, zone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun eventWords(event: FiringEvent, today: LocalDate, zone: ZoneId): String {
    val words = rememberWords()
    return when (event.kind) {
        FiringKind.RANG -> stringResource(R.string.history_rang)
        FiringKind.MISSED -> stringResource(R.string.history_missed)
        FiringKind.NET -> stringResource(R.string.history_net)
        FiringKind.DEALT -> stringResource(R.string.history_dealt)
        FiringKind.SKIPPED -> stringResource(R.string.history_skipped)
        FiringKind.UNTICKED -> stringResource(R.string.history_unticked)
        FiringKind.SNOOZED -> {
            // "Until" is the part worth saying: which offer it was matters less than when it came back.
            val until = event.detail?.let { runCatching { Instant.parse(it) }.getOrNull() }?.atZone(zone)
            if (until == null) {
                stringResource(R.string.history_snoozed)
            } else {
                stringResource(R.string.history_snoozed_until, dayWord(words, until.toLocalDate(), today) + " " + TimeText.time(until.toLocalTime(), words.is24h, words.locale))
            }
        }
    }
}
