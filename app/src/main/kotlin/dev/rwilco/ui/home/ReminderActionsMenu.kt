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
import androidx.compose.material.icons.outlined.ContentCopy
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
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.rwilco.model.Snooze
import dev.rwilco.ui.components.SnoozeOffers

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
    onDone: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
    onSnooze: (Snooze) -> Unit,
    onCancelSnooze: () -> Unit,
    onClone: () -> Unit,
    onKeepAsPreset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    var choosingSnooze by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // The dialog's own window covers the screen, so "outside" has to be made by hand: the
        // box takes the taps that miss the menu and answers them with a dismissal.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
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
                    if (snoozeOffered) {
                        if (choosingSnooze) {
                            Text(
                                text = stringResource(R.string.home_snooze),
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = spacing.sm),
                            )
                            SnoozeOffers(offers = Snooze.entries, customMinutes = customMinutes, onPick = onSnooze)
                        } else {
                            ActionRow(
                                icon = Icons.Outlined.Snooze,
                                label = stringResource(R.string.home_snooze),
                                hint = stringResource(R.string.home_snooze_hint),
                                onClick = { choosingSnooze = true },
                            )
                        }
                    }
                    if (snoozed) {
                        ActionRow(
                            icon = Icons.Outlined.AlarmOff,
                            label = stringResource(R.string.home_cancel_snooze),
                            hint = stringResource(R.string.home_cancel_snooze_hint),
                            onClick = onCancelSnooze,
                        )
                    }
                    ActionRow(
                        icon = Icons.Outlined.ContentCopy,
                        label = stringResource(R.string.home_clone),
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
        modifier = modifier.heightIn(min = 72.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(Tokens.spacing.sm),
        ) {
            Icon(icon, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(24.dp))
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
            .heightIn(min = 72.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(Tokens.spacing.md),
        ) {
            Icon(icon, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(Tokens.spacing.md))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = label, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                Text(text = hint, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}
