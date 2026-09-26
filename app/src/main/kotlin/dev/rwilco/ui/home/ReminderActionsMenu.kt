package dev.rwilco.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CopyAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.rwilco.R
import dev.rwilco.ui.theme.Tokens
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.AlarmOff
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import dev.rwilco.model.AppSettings
import java.time.Clock
import dev.rwilco.model.SnoozeBoard
import dev.rwilco.model.SnoozeOffer
import dev.rwilco.model.SnoozeTerms
import dev.rwilco.model.snoozeBoard
import dev.rwilco.model.snoozeTerms
import dev.rwilco.model.standingAt
import dev.rwilco.ui.components.SnoozeOffers
import dev.rwilco.ui.components.WordActions
import dev.rwilco.model.Actionable
import dev.rwilco.model.SnoozePlace
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * What can be done to one reminder, asked by holding its card.
 *
 * **At the top of the screen, and that is the whole reason it is not the chooser.** A held press
 * lands wherever the card is, which on a list is the middle of the screen — and a menu that
 * opens under the thumb that opened it is a menu you have to move your hand to read. "Nuevo"
 * asks its question in the middle because the thumb is on a button at the bottom by then; this
 * one has no such luxury, so it goes where nothing was being pressed.
 *
 * The words of the reminder are the title: a long press does not say which card it caught, and
 * on a list of five the answer matters before anything is chosen.
 *
 * The three answers a swipe or a hold already gives — hecho, pausar, borrar — sit in one row of
 * tiles under the title, because they are the ones somebody reaches for and a menu of seven
 * full rows does not fit under a title. Under them, the things only this menu can do: put it
 * off (only where that is an answer, see `ReminderCardUi.snoozeOffered`), take a snooze back,
 * clone it, keep its shape as a preset.
 */
@Composable
fun ReminderActionsMenu(
    words: String,
    paused: Boolean,
    snoozeOffered: Boolean,
    snoozed: Boolean,
    customMinutes: Int,
    /** The moment "saltar la próxima" would let pass, already in words; null where there is none. */
    skipHint: String? = null,
    onDone: () -> Unit,
    onSkip: () -> Unit = {},
    onPause: () -> Unit,
    onDelete: () -> Unit,
    onSnooze: (SnoozeOffer) -> Unit,
    /**
     * "A otro momento…": the menu closes and the screen behind it opens the list of every other
     * answer, the calendar first among them (it was "a una fecha" until 0.137.0).
     */
    onSnoozeMore: () -> Unit = {},
    /** Which offers the row shows; the rest are behind [onSnoozeMore]. Every one, by default. */
    board: SnoozeBoard = snoozeBoard(AppSettings()),
    /** What a part of today is read against: one that has gone ("esta tarde", at nine) is not offered. */
    terms: SnoozeTerms = AppSettings().snoozeTerms,
    /** The app's clock, so what is on the row can be driven by a fake one. */
    clock: Clock = Clock.systemDefaultZone(),
    onCancelSnooze: () -> Unit,
    /** The place answers, after the clock ones; empty on a phone that cannot give them. */
    places: List<SnoozePlace> = emptyList(),
    onSnoozeToPlace: (SnoozePlace) -> Unit = {},
    onClone: () -> Unit,
    onKeepAsPreset: () -> Unit,
    onDismiss: () -> Unit,
    /** About a routine: the rows that name the thing name it as one. */
    routine: Boolean = false,
    /**
     * "Lo hice otro día…": a routine's "hecho" dated to a day that is not today. Null wherever it
     * is not an answer — anything that is not a routine, and a routine that rests.
     */
    onDoneEarlier: (() -> Unit)? = null,
    /**
     * "Lo hice a su hora": the moment a routine's "hecho" would be dated to — its deadline — in
     * words; null wherever that is not an answer (see [dev.rwilco.model.doneOnTimeAt]).
     */
    onTimeHint: String? = null,
    onDoneOnTime: () -> Unit = {},
    /** A contact, whose deed is a conversation: "hablamos otro día". */
    contact: Boolean = false,
    /** What can be done with the words themselves — ring the number, open the link, copy them. */
    wordActions: WordActions? = null,
) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    var choosingSnooze by rememberSaveable { mutableStateOf(false) }
    // The menu is composed when it is opened, so this is the board as it stands at that moment.
    val standing = remember(board, terms) { board.standingAt(clock.instant(), clock.zone, terms) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // The dialog's own window covers the screen, so "outside" has to be made by hand: the
        // box takes the taps that miss the menu and answers them with a dismissal.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    // A screen-sized nameless button to a screen reader, otherwise (0.94.0).
                    onClickLabel = stringResource(R.string.common_close),
                    onClick = onDismiss,
                )
                .safeDrawingPadding()
                .padding(spacing.lg),
            contentAlignment = Alignment.TopCenter,
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = scheme.surfaceContainer,
                border = BorderStroke(Tokens.strokes.edge, scheme.outlineVariant),
                // A tap on the menu itself is not a tap outside it: consumed here, or the box
                // underneath would close the thing being aimed at.
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(
                    modifier = Modifier
                        .padding(spacing.lg)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Text(
                        text = words,
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
                        ActionTile(Icons.Outlined.Check, stringResource(R.string.home_menu_done), onDone, Modifier.weight(1f))
                        ActionTile(
                            icon = if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                            label = stringResource(if (paused) R.string.card_resume else R.string.card_pause),
                            onClick = onPause,
                            modifier = Modifier.weight(1f),
                        )
                        ActionTile(Icons.Outlined.Delete, stringResource(R.string.home_menu_delete), onDelete, Modifier.weight(1f))
                    }
                    // "Hecho" is *now*; this is the same answer counted from when it was due, so a
                    // routine said on Wednesday about Monday keeps its Mondays (0.157.0). Above
                    // "otro día": it is the one that needs no calendar.
                    if (onTimeHint != null) {
                        ActionRow(
                            icon = Icons.Outlined.History,
                            label = stringResource(R.string.routines_done_on_time),
                            hint = onTimeHint,
                            onClick = onDoneOnTime,
                        )
                    }
                    // "Hecho" is always *now*, which is right for the tap and wrong for the day
                    // after: watered on Saturday, remembered on Tuesday, and the count was three
                    // days off for three weeks. The same answer, about another day (0.134.0).
                    if (onDoneEarlier != null) {
                        ActionRow(
                            icon = Icons.Outlined.EventAvailable,
                            label = stringResource(if (contact) R.string.routines_talked_earlier else R.string.routines_done_earlier),
                            hint = stringResource(if (contact) R.string.routines_talked_earlier_hint else R.string.routines_done_earlier_hint),
                            onClick = onDoneEarlier,
                        )
                    }
                    if (snoozeOffered) {
                        if (choosingSnooze) {
                            Text(
                                text = stringResource(R.string.home_snooze),
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = spacing.sm),
                            )
                            SnoozeOffers(
                                offers = standing.shown,
                                customMinutes = customMinutes,
                                onPick = onSnooze,
                                places = if (board.placesShown) places else emptyList(),
                                onPickPlace = onSnoozeToPlace,
                                onMore = onSnoozeMore,
                                moreFirst = routine,
                            )
                        } else {
                            ActionRow(
                                icon = Icons.Outlined.Snooze,
                                label = stringResource(R.string.home_snooze),
                                hint = stringResource(R.string.home_snooze_hint),
                                onClick = { choosingSnooze = true },
                            )
                        }
                    }
                    // A round let pass on purpose. It is a "hecho" underneath, and the tile above
                    // does the same thing to the same reminder — but "hecho" on something that has
                    // not rung reads as a lie, and the one honest word for it was nowhere.
                    if (skipHint != null) {
                        ActionRow(
                            icon = Icons.Outlined.SkipNext,
                            label = stringResource(R.string.home_skip),
                            hint = skipHint,
                            onClick = onSkip,
                        )
                    }
                    if (snoozed) {
                        ActionRow(
                            icon = Icons.Outlined.AlarmOff,
                            label = stringResource(R.string.home_cancel_snooze),
                            hint = stringResource(R.string.home_cancel_snooze_hint),
                            onClick = onCancelSnooze,
                        )
                    }
                    // **The words are good for something** (0.135.0). A number in them could only be
                    // carried by eye into the dialer, and a link shared into the app could not be
                    // opened from anywhere. Rows only for what is actually there; the copy always.
                    if (wordActions != null) {
                        for (actionable in wordActions.found) {
                            when (actionable) {
                                is Actionable.Phone -> ActionRow(
                                    icon = Icons.Outlined.Call,
                                    label = stringResource(R.string.menu_call, actionable.raw),
                                    hint = stringResource(R.string.menu_call_hint),
                                    onClick = { wordActions.open(actionable) },
                                )
                                is Actionable.Link -> ActionRow(
                                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                                    label = stringResource(R.string.menu_open_link, actionable.host),
                                    hint = stringResource(R.string.menu_open_link_hint),
                                    onClick = { wordActions.open(actionable) },
                                )
                            }
                        }
                        ActionRow(
                            icon = Icons.Outlined.CopyAll,
                            label = stringResource(R.string.menu_copy_text),
                            hint = stringResource(R.string.menu_copy_text_hint),
                            onClick = wordActions.copy,
                        )
                    }
                    ActionRow(
                        icon = Icons.Outlined.ContentCopy,
                        label = stringResource(if (routine) R.string.home_clone_routine else R.string.home_clone),
                        hint = stringResource(R.string.home_clone_hint),
                        onClick = onClone,
                    )
                    ActionRow(
                        icon = Icons.Outlined.Bookmarks,
                        label = stringResource(R.string.home_keep_preset),
                        hint = stringResource(R.string.home_keep_preset_hint),
                        onClick = onKeepAsPreset,
                    )
                }
            }
        }
    }
}

/** One of the three quick answers: a glyph over its verb, a third of the row wide. */
@Composable
private fun ActionTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = Tokens.haptics
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = {
            haptics.perform(HapticFeedbackType.Confirm)
            onClick()
        },
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(Tokens.strokes.control, scheme.outline),
        modifier = modifier.heightIn(min = Tokens.sizes.tile),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(Tokens.spacing.sm),
        ) {
            Icon(icon, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(Tokens.sizes.glyphLarge))
            Spacer(Modifier.height(Tokens.spacing.xs))
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
        }
    }
}

/** One thing that can be done: its glyph, its verb, and the line that says what it will do. */
@Composable
private fun ActionRow(icon: ImageVector, label: String, hint: String, onClick: () -> Unit) {
    val haptics = Tokens.haptics
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = {
            haptics.perform(HapticFeedbackType.Confirm)
            onClick()
        },
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(Tokens.strokes.control, scheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.sizes.tile),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(Tokens.spacing.md),
        ) {
            Icon(icon, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(Tokens.sizes.glyphLarge))
            Spacer(Modifier.width(Tokens.spacing.md))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = label, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                Text(text = hint, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}
