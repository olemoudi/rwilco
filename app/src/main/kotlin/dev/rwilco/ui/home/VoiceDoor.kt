package dev.rwilco.ui.home

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import dev.rwilco.R
import dev.rwilco.ui.components.holdable
import dev.rwilco.ui.components.rememberHoldState
import dev.rwilco.ui.theme.Tokens

/**
 * The door to saying a reminder: a floating button with a microphone, beside "Nuevo" and as tall
 * as it (0.195.0, the owner's: within reach of the left thumb with the phone in the left hand).
 * From 0.176.0 it was a row the whole width of the top of Home.
 *
 * **Held, not tapped**, for exactly as long as "hecho" ([dev.rwilco.ui.components.HOLD_MILLIS],
 * through the same ring): it opens a microphone, and the corner is where a thumb rests. A tap
 * says why nothing opened rather than leaving somebody to wonder (0.191.0). A screen reader gets
 * an ordinary click that opens it, as every hold in the app gives it.
 *
 * Neutral, never amber: amber is what fires next.
 */
@Composable
fun VoiceDoor(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** A finger that only tapped (0.191.0): said why nothing opened, rather than left to wonder. */
    onTapped: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.home_dictate)
    val hold = rememberHoldState()
    val haptics = Tokens.haptics
    FloatingActionButton(
        // The button's own click is the tap; the hold is the holdable's, and a click that ends a
        // hold is not a tap.
        onClick = {
            if (!hold.held) {
                haptics.perform(HapticFeedbackType.Reject)
                onTapped()
            }
        },
        shape = MaterialTheme.shapes.large,
        containerColor = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        modifier = modifier
            .size(Tokens.sizes.primary)
            .holdable(Icons.Outlined.Mic, label, onOpen, hold)
            // Outside the button's own click, so it is the one a screen reader performs.
            .semantics {
                onClick(label) {
                    onOpen()
                    true
                }
            },
    ) {
        Icon(Icons.Outlined.Mic, contentDescription = label)
    }
}
