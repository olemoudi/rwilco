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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.ui.theme.Tokens

/**
 * Every configurator sheet: a title, scrollable content, and the one confirm button kept out
 * of the scroll so it can never sink below the fold.
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetScaffold(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String = stringResource(R.string.sheet_done),
    confirmEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    val haptics = Tokens.haptics
    val spacing = Tokens.spacing
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(modifier = Modifier.padding(horizontal = spacing.screen)) {
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
                        .heightIn(min = Tokens.sizes.control),
                ) {
                    Text(confirmLabel, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
