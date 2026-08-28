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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.model.OFFERED_KINDS
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.TriggerKind
import dev.rwilco.ui.components.TriggerKeycap
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.icon

/**
 * One row per kind: what kind of "when" to add. [preferred] — the kind chosen in Settings —
 * leads and says so; the other five keep their order behind it, because a favourite is a
 * shortcut, not a filter. [kinds] is that order, which Settings can also hand over sorted by
 * what actually gets used.
 *
 * Rows and not a grid. Six things in two columns is a shape that has to be *read* — the eye
 * goes across, then down, then back — and with an odd count the last cell is a hole. One column
 * is one list: the names line up, the hints line up, and the order the sheet is trying to say
 * something with ("your favourite first") is the order you actually see.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriggerKindSheet(
    preferred: TriggerKind?,
    onPick: (TriggerKind) -> Unit,
    onDismiss: () -> Unit,
    kinds: List<TriggerKind> = OFFERED_KINDS,
) {
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
            val ordered = remember(preferred, kinds) {
                if (preferred == null) kinds else listOf(preferred) + kinds.filter { it != preferred }
            }
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                for (kind in ordered) {
                    KindRow(
                        kind = kind,
                        isDefault = kind == preferred,
                        onClick = { onPick(kind) },
                    )
                }
            }
        }
    }
}

@Composable
private fun KindRow(kind: TriggerKind, isDefault: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = Tokens.haptics
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = {
            haptics.perform(HapticFeedbackType.Confirm)
            onClick()
        },
        shape = MaterialTheme.shapes.medium,
        color = if (isDefault) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh,
        // A firmer line, not amber: amber says "this is what fires next" and nothing else.
        border = BorderStroke(1.dp, if (isDefault) scheme.outline else scheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = Tokens.sizes.primary)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TriggerKeycap(family = kind.family, icon = kind.icon, contentDescription = null)
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(stringResource(kind.titleRes), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(if (isDefault) R.string.kind_default_badge else kind.hintRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

val RuleMatch.labelRes: Int
    get() = when (this) {
        RuleMatch.ANY -> R.string.editor_match_any
        RuleMatch.ALL -> R.string.editor_match_all
        RuleMatch.TOGETHER -> R.string.editor_match_together
    }

val RuleMatch.hintRes: Int
    get() = when (this) {
        RuleMatch.ANY -> R.string.editor_match_any_hint
        RuleMatch.ALL -> R.string.editor_match_all_hint
        RuleMatch.TOGETHER -> R.string.editor_match_together_hint
    }

val TriggerKind.titleRes: Int
    get() = when (this) {
        TriggerKind.DATE_TIME -> R.string.kind_date_time
        TriggerKind.DATE -> R.string.kind_date
        TriggerKind.DATE_RANGE -> R.string.kind_date_range
        TriggerKind.REPEAT_TIME -> R.string.kind_repeat_time
        TriggerKind.INTERVAL -> R.string.kind_interval
        TriggerKind.COUNTDOWN -> R.string.kind_countdown
        TriggerKind.PLACE -> R.string.kind_place
        TriggerKind.RANDOM -> R.string.kind_random
    }

private val TriggerKind.hintRes: Int
    get() = when (this) {
        TriggerKind.DATE_TIME -> R.string.kind_date_time_hint
        TriggerKind.DATE -> R.string.kind_date_hint
        TriggerKind.DATE_RANGE -> R.string.kind_date_range_hint
        TriggerKind.REPEAT_TIME -> R.string.kind_repeat_time_hint
        TriggerKind.INTERVAL -> R.string.kind_interval_hint
        TriggerKind.COUNTDOWN -> R.string.kind_countdown_hint
        TriggerKind.PLACE -> R.string.kind_place_hint
        TriggerKind.RANDOM -> R.string.kind_random_hint
    }
