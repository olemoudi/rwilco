package dev.rwilco.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.ui.theme.Tokens
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxWithConstraints

/**
 * Every configurator sheet: a title, scrollable content, and the one confirm button kept out
 * of the scroll so it can never sink below the fold.
 *
 * **And the height is capped, or that last part is a wish.** A bottom sheet measures its content
 * with no height limit — that is how a sheet taller than the screen can be dragged — so a
 * `weight(1f, fill = false)` inside it does nothing at all: the content took whatever it wanted
 * and the button row was placed after it, off the bottom of the screen. It held only while every
 * sheet happened to be short enough, and the day the date sheet grew a row it stopped holding.
 * Bounded here to most of the window, the weight means what it says: the row is measured first
 * and the content scrolls in what is left.
 *
 * **A fling may not throw the form away.** The content scrolls inside the sheet, and when a
 * scroll back up to the top ran out of list with speed to spare, the nested-scroll contract
 * handed what was left to the sheet — which read a downward flick as "cerrar" and took a
 * half-filled place, radius and all, with it. Nothing about that gesture said "throw this
 * away": it was somebody going back to check what they had typed. So the sheet refuses to
 * *settle* into hidden, which is the state a drag or a fling asks for; it still follows a
 * finger on the handle and springs back, and every deliberate way out — the back gesture, the
 * scrim, "Cancelar" — is programmatic and untouched. It is the same rule as Home's swipes and
 * the hold buttons: the gestures that destroy something have to mean it.
 *
 * **And it does not bounce.** A sheet that is already as far up as it goes has two things that
 * answer a swipe upwards with a spring — the content's own scroll and the sheet's drag — and
 * neither of them has anywhere to go, so the gesture got two stretches and a rebound and said
 * nothing at all. Overscroll is a way of telling somebody a list has ended; on a form whose
 * end is a button in plain sight it is noise, and repeated (which is what a hand does when a
 * screen answers a swipe with a bounce) it is a screen that looks broken. The factory is turned
 * off for the whole sheet, drag included. Nothing else changes: the refusal to settle into
 * hidden is untouched, and it is still what a downward fling meets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetScaffold(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String = stringResource(R.string.sheet_done),
    confirmEnabled: Boolean = true,
    /**
     * Whether the sheet holds work worth a question: back and the scrim then ask before
     * throwing it away (0.68.0), as the editor behind the sheet always has for a one-letter
     * typo. "Cancelar" stays direct: it says what it does.
     */
    dirty: Boolean = false,
    content: @Composable () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    val haptics = Tokens.haptics
    val spacing = Tokens.spacing
    var askingToDiscard by remember { mutableStateOf(false) }
    if (askingToDiscard) {
        DiscardDialog(onKeep = { askingToDiscard = false }, onDiscard = { askingToDiscard = false; onDismiss() })
    }
    NoBounce {
        ModalBottomSheet(
            onDismissRequest = { if (dirty) askingToDiscard = true else onDismiss() },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            // The budget is what this slot is actually given, when it is given one; a bottom sheet
            // usually measures its content unbounded (that is how a sheet taller than the screen can
            // be dragged), and then the window minus the sheet's own furniture — the drag handle
            // above this, and the gap it leaves under the status bar — is the honest guess. Capping
            // against the whole window instead asked for more room than the sheet has and clipped
            // the confirm row all the same, only by less.
            BoxWithConstraints {
                val budget = if (constraints.hasBoundedHeight) maxHeight else LocalConfiguration.current.screenHeightDp.dp - SHEET_CHROME
                Column(
                    modifier = Modifier
                        .heightIn(max = budget)
                        .padding(horizontal = spacing.screen),
                ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(spacing.lg))
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(spacing.lg),
                ) {
                    content()
                }
                Spacer(Modifier.height(spacing.lg))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(bottom = spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.heightIn(min = Tokens.sizes.control),
                    ) {
                        Text(stringResource(R.string.sheet_cancel))
                    }
                    Button(
                        onClick = {
                            haptics.perform(HapticFeedbackType.Confirm)
                            onConfirm()
                        },
                        enabled = confirmEnabled,
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Tokens.sizes.primary),
                    ) {
                        Text(confirmLabel, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A sheet that answers a swipe with nothing rather than with a spring; see [SheetScaffold]'s
 * note on the bounce for why. Wrap the whole `ModalBottomSheet`, not its content: the sheet's
 * own drag has an overscroll effect of its own, and it is the louder of the two.
 */
@Composable
fun NoBounce(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalOverscrollFactory provides null, content = content)

/** The drag handle above the content, plus the gap the sheet leaves under the status bar. */
private val SHEET_CHROME = 96.dp
