package dev.rwilco.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.Snooze
import dev.rwilco.ui.format.snoozeLabel
import dev.rwilco.ui.theme.Tokens
import androidx.compose.ui.graphics.Color
import dev.rwilco.model.SnoozePlace
import dev.rwilco.model.TriggerFamily
import dev.rwilco.ui.format.placeOfferLabel
import dev.rwilco.ui.theme.color

/**
 * The snooze offers as a row of real buttons — thumb-sized, each its own target, none of them
 * a chip a hand has to aim at. Worn by the alert screen and by a card's menu on Home: the same
 * answers in the same order, wherever the question is asked.
 *
 * On the alert the buttons are held, not tapped ([guard]); on a card's menu, where a snooze
 * is a tap away from being undone, they are ordinary.
 */
@Composable
fun SnoozeOffers(
    offers: List<Snooze>,
    customMinutes: Int,
    onPick: (Snooze) -> Unit,
    modifier: Modifier = Modifier,
    /** The place answers, after the clock ones: "al llegar a casa", "al salir de aquí". */
    places: List<SnoozePlace> = emptyList(),
    onPickPlace: (SnoozePlace) -> Unit = {},
    guard: PressGuard? = null,
) {
    val spacing = Tokens.spacing
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
        modifier = modifier.fillMaxWidth(),
    ) {
        for (snooze in offers) {
            SnoozeButton(label = snoozeLabel(snooze, customMinutes), onClick = { onPick(snooze) }, guard = guard)
        }
        // In the place family's colour, because they are places: the one thing on this row
        // that is not a clock, said the way every place in the app is said.
        for (place in places) {
            SnoozeButton(
                label = placeOfferLabel(place),
                onClick = { onPickPlace(place) },
                accent = TriggerFamily.PLACE.color(),
                guard = guard,
                icon = Icons.Outlined.Place,
            )
        }
    }
}

/**
 * A snooze offer: a real button, thumb-sized, quiet enough not to compete with Done. With a
 * [guard] it answers only to a hold, and the indicator says "posponer · 10 min" while the
 * finger is on it and "pospuesto · 10 min" once it has been kept.
 */
@Composable
fun SnoozeButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    guard: PressGuard? = null,
    icon: ImageVector = Icons.Outlined.Snooze,
) {
    val haptics = Tokens.haptics
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val words = @Composable {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = accent ?: scheme.onSurface,
            modifier = Modifier
                .heightIn(min = Tokens.sizes.touch)
                .padding(horizontal = Tokens.spacing.lg, vertical = Tokens.spacing.md),
        )
    }
    if (guard != null) {
        val action = GuardedAction(
            icon = icon,
            holding = stringResource(R.string.alert_snooze),
            done = stringResource(R.string.alert_snoozed),
            detail = label,
        )
        Surface(
            shape = shape,
            color = scheme.surfaceContainerHigh,
            border = BorderStroke(Tokens.strokes.control, accent ?: scheme.outline),
            modifier = modifier.clip(shape).guarded(guard, action, onConfirmed = onClick),
            content = words,
        )
        return
    }
    Surface(
        onClick = {
            haptics.perform(HapticFeedbackType.ContextClick)
            onClick()
        },
        shape = shape,
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(Tokens.strokes.control, accent ?: scheme.outline),
        modifier = modifier,
        content = words,
    )
}
