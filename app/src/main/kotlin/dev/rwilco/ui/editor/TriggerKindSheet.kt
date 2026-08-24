package dev.rwilco.ui.editor

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.model.TriggerKind
import dev.rwilco.ui.components.TriggerKeycap
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.icon

/** Six big tiles, two by three: what kind of "when" to add. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriggerKindSheet(onPick: (TriggerKind) -> Unit, onDismiss: () -> Unit) {
    val spacing = Tokens.spacing
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = spacing.screen)
                .navigationBarsPadding()
                .padding(bottom = spacing.xl),
        ) {
            Text(stringResource(R.string.editor_add_trigger), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(spacing.lg))
            val kinds = TriggerKind.entries
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                for (row in kinds.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        for (kind in row) {
                            KindTile(kind = kind, onClick = { onPick(kind) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KindTile(kind: TriggerKind, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = Tokens.haptics
    Surface(
        onClick = {
            haptics.perform(HapticFeedbackType.Confirm)
            onClick()
        },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.heightIn(min = 104.dp),
    ) {
        Column(modifier = Modifier.padding(Tokens.spacing.lg)) {
            TriggerKeycap(family = kind.family, icon = kind.icon, contentDescription = null)
            Spacer(Modifier.height(Tokens.spacing.md))
            Text(stringResource(kind.titleRes), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(kind.hintRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

val TriggerKind.titleRes: Int
    get() = when (this) {
        TriggerKind.DATE_TIME -> R.string.kind_date_time
        TriggerKind.DATE -> R.string.kind_date
        TriggerKind.REPEAT_TIME -> R.string.kind_repeat_time
        TriggerKind.COUNTDOWN -> R.string.kind_countdown
        TriggerKind.PLACE -> R.string.kind_place
        TriggerKind.RANDOM -> R.string.kind_random
    }

private val TriggerKind.hintRes: Int
    get() = when (this) {
        TriggerKind.DATE_TIME -> R.string.kind_date_time_hint
        TriggerKind.DATE -> R.string.kind_date_hint
        TriggerKind.REPEAT_TIME -> R.string.kind_repeat_time_hint
        TriggerKind.COUNTDOWN -> R.string.kind_countdown_hint
        TriggerKind.PLACE -> R.string.kind_place_hint
        TriggerKind.RANDOM -> R.string.kind_random_hint
    }
