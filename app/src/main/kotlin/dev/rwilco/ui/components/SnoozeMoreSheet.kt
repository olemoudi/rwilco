package dev.rwilco.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.rwilco.R
import dev.rwilco.model.SnoozeBoard
import dev.rwilco.model.SnoozeOffer
import dev.rwilco.model.SnoozePlace
import dev.rwilco.model.SnoozeTerms
import dev.rwilco.model.TriggerFamily
import dev.rwilco.model.until
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.dayWord
import dev.rwilco.ui.format.placeOfferLabel
import dev.rwilco.ui.format.rememberWords
import dev.rwilco.ui.format.snoozeLabel
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.color
import java.time.ZonedDateTime
import kotlinx.coroutines.launch

/**
 * "A otro momento…": every way there is of putting a reminder off, in one list (0.137.0).
 *
 * The alert used to carry all of them, every time — up to ten held buttons on the one screen
 * that is answered half awake. It carries the ones somebody chose now, and this is the door to
 * the rest: the calendar first (it is literally "another moment", and the one answer that is not
 * a length), then **what was kept off the alert, the most used first**, then what is on it
 * anyway, so nobody has to remember which list an answer lives in. Each row says the moment it
 * would come back at — "mañana 09:00" — because "el finde" is a word, and the hour it means is a
 * setting three screens away.
 *
 * Plain taps, even over the alert: the button that opened it was held, the eyes have arrived,
 * and a list is read before it is pressed — the same reasoning the calendar's own "Listo" has.
 *
 * **And it slides away when it is answered** (0.139.0), as every configurator sheet has since
 * 0.133.0: a row that called straight back tore the sheet out of composition on the same frame,
 * and picking the calendar swapped two sheets in one frame with no transition between them.
 * There is no refusal to step around here — this sheet has nothing to throw away, so a fling may
 * close it like anything else — only an answer that waits for the way out to finish.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnoozeMoreSheet(
    board: SnoozeBoard,
    terms: SnoozeTerms,
    now: ZonedDateTime,
    onPick: (SnoozeOffer) -> Unit,
    onPickDate: () -> Unit,
    onDismiss: () -> Unit,
    /** The place answers this phone can give right now; drawn here only when they are kept off the alert. */
    places: List<SnoozePlace> = emptyList(),
    onPickPlace: (SnoozePlace) -> Unit = {},
) {
    val spacing = Tokens.spacing
    val words = rememberWords()
    val today = now.toLocalDate()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }

    /** Slides it out, then answers. Once: a second tap while it goes is not a second answer. */
    fun leave(then: () -> Unit) {
        if (leaving) return
        leaving = true
        scope.launch {
            try {
                sheetState.hide()
            } finally {
                then()
            }
        }
    }
    NoBounce {
        ModalBottomSheet(
            onDismissRequest = { leave(onDismiss) },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            SheetBounds(
                modifier = Modifier
                    .padding(horizontal = spacing.screen)
                    .navigationBarsPadding()
                    .padding(bottom = spacing.xl),
            ) {
                Text(stringResource(R.string.snooze_more_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(spacing.md))
                Column(
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    MoreRow(icon = Icons.Outlined.Event, label = stringResource(R.string.snooze_more_pick), moment = null, onClick = { leave(onPickDate) })
                    for (snooze in board.more + board.shown) {
                        // A part of today that has gone is not an answer, so it is not a row.
                        val back = snooze.until(now.toInstant(), now.zone, terms)?.atZone(now.zone) ?: continue
                        MoreRow(
                            icon = Icons.Outlined.Snooze,
                            label = snoozeLabel(snooze, terms.customMinutes),
                            moment = dayWord(words, back.toLocalDate(), today) + " " + TimeText.time(back.toLocalTime(), words.is24h, words.locale),
                            onClick = { leave { onPick(snooze) } },
                        )
                    }
                    if (!board.placesShown) {
                        for (place in places) {
                            MoreRow(
                                icon = Icons.Outlined.Place,
                                label = placeOfferLabel(place),
                                moment = null,
                                accent = TriggerFamily.PLACE.color(),
                                onClick = { leave { onPickPlace(place) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One answer: what it is called, and — where it is a moment — when that comes to. */
@Composable
private fun MoreRow(icon: ImageVector, label: String, moment: String?, onClick: () -> Unit, accent: Color? = null) {
    val haptics = Tokens.haptics
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = {
            haptics.perform(HapticFeedbackType.Confirm)
            onClick()
        },
        shape = MaterialTheme.shapes.small,
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(Tokens.strokes.control, accent ?: scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = Tokens.sizes.control)
                .padding(horizontal = Tokens.spacing.lg, vertical = Tokens.spacing.sm),
        ) {
            Icon(icon, contentDescription = null, tint = accent ?: scheme.onSurfaceVariant, modifier = Modifier.size(Tokens.sizes.glyphMedium))
            Spacer(Modifier.width(Tokens.spacing.md))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = accent ?: scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (moment != null) {
                Spacer(Modifier.width(Tokens.spacing.sm))
                Text(text = moment, style = MonoStyles.date, color = scheme.onSurfaceVariant)
            }
        }
    }
}
