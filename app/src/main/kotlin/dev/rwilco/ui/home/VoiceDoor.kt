package dev.rwilco.ui.home

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import dev.rwilco.R
import dev.rwilco.ui.components.HOLD_MILLIS
import dev.rwilco.ui.components.holdable
import dev.rwilco.ui.components.rememberHoldState
import dev.rwilco.ui.theme.Tokens
import kotlinx.coroutines.delay

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
 * **The microphone opens while the finger is still holding** (0.209.0, the owner: "empiezo a
 * hablar demasiado pronto"): the recognizer takes longer to open than the sheet takes to appear,
 * and the buzz that ends the hold reads as "go". So once a press has outlasted any tap
 * ([WARM_AFTER_MS]) the ear is [onWarm]ed, and by the time the sheet is up it is listening — what
 * it heard meanwhile goes to the sheet ([dev.rwilco.voice.EarlyEar]). A press let go before the
 * hold went through, or a hold whose sheet never took the ear, [onCool]s it: a microphone is never
 * left open with nothing on the screen saying so.
 *
 * Neutral, never amber: amber is what fires next.
 */
@Composable
fun VoiceDoor(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** A finger that only tapped (0.191.0): said why nothing opened, rather than left to wonder. */
    onTapped: () -> Unit = {},
    /** A press that is becoming a hold: the ear starts now, for the sheet to take over (0.209.0). */
    onWarm: () -> Unit = {},
    /** The press ended without a sheet to take the ear: it is let go of. Never touches an ear the sheet took. */
    onCool: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.home_dictate)
    val hold = rememberHoldState()
    val haptics = Tokens.haptics
    val presses = remember { MutableInteractionSource() }
    val pressed by presses.collectIsPressedAsState()
    val warm by rememberUpdatedState(onWarm)
    val cool by rememberUpdatedState(onCool)
    LaunchedEffect(pressed) {
        if (pressed) {
            delay(WARM_AFTER_MS.toLong())
            warm()
            // A finger that stays down without the hold going through — it wandered — does not
            // keep a microphone open for nobody.
            delay((HOLD_MILLIS - WARM_AFTER_MS + WARM_PATIENCE_MS).toLong())
        } else if (hold.held) {
            // The hold went through and the sheet is on its way: it takes the ear within a frame
            // or two. One it never took is let go of all the same.
            delay(WARM_PATIENCE_MS.toLong())
        }
        cool()
    }
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
        interactionSource = presses,
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

/** How long a press lasts before the ear is warmed: past any tap, so a brushed button opens no microphone. */
private const val WARM_AFTER_MS = 150

/** How long a warmed ear waits to be taken before it is let go of. */
private const val WARM_PATIENCE_MS = 1_500
