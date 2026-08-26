package dev.rwilco.ui.alert

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import dev.rwilco.R
import dev.rwilco.model.Snooze
import dev.rwilco.model.kind
import dev.rwilco.ui.components.TagLabel
import dev.rwilco.ui.components.TriggerKeycap
import dev.rwilco.ui.components.lampGlow
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.format.triggerLine
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.icon

/**
 * The lamp at full brightness. The reminder's words as big as they fit, and one button the
 * thumb cannot miss. In phase 1 it is reached only as a preview; phase 2 hosts the same
 * composable in the activity a full-screen intent launches.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertScreen(
    content: AlertContent,
    preview: Boolean,
    onDone: () -> Unit,
    /** Reminders ringing behind this one, shown the instant it is answered. */
    waiting: Int = 0,
    onSnooze: (Snooze) -> Unit,
    onView: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val spacing = Tokens.spacing
    val haptics = Tokens.haptics
    val locale = currentLocale()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .lampGlow(scheme.primary, intensity = 1f)
            .systemBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(spacing.screen)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(if (preview) R.string.alert_preview_label else R.string.app_name).uppercase(locale),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold),
                    color = scheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (waiting > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.alert_waiting, waiting, waiting),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = spacing.sm),
                    )
                }
                content.trigger?.let { trigger ->
                    TriggerKeycap(family = content.family, icon = trigger.kind.icon, contentDescription = null)
                }
            }
            content.trigger?.let { trigger ->
                val line = triggerLine(trigger, content.today, content.defaultTime)
                Spacer(Modifier.height(spacing.sm))
                Text(
                    text = stringResource(R.string.alert_fired_by, line.primary + " · " + line.secondary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            BasicText(
                text = content.text.ifBlank { stringResource(R.string.alert_empty_text) },
                style = MaterialTheme.typography.displayMedium.copy(color = scheme.onBackground),
                autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = 56.sp, stepSize = 2.sp),
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (content.tags.isNotEmpty()) {
                Spacer(Modifier.height(spacing.lg))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    for (tag in content.tags.take(3)) TagLabel(tag)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.alert_snooze),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.sm))
            // Five answers, each its own tap target and none of them a chip a thumb has to aim
            // at. They sit clear of the Done button, because the two mean opposite things and a
            // half-awake hand should not be able to confuse them.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                for (snooze in Snooze.entries) {
                    SnoozeButton(label = stringResource(snooze.labelRes), onClick = { onSnooze(snooze) })
                }
            }
            Spacer(Modifier.height(spacing.lg))
            // "Ver" goes ABOVE "Hecho", not under it. The bottom of the screen is where the
            // thumb lands, and it belongs to the one answer this screen is asking for — an
            // alarm answered half awake must not be able to hand somebody the edit form
            // instead. Which is exactly what it did.
            TextButton(
                onClick = onView,
                colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurfaceVariant),
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.sizes.touch),
            ) {
                Text(stringResource(if (preview) R.string.alert_close_preview else R.string.alert_view))
            }
            Spacer(Modifier.height(spacing.sm))
            Button(
                onClick = {
                    haptics.perform(HapticFeedbackType.Confirm)
                    onDone()
                },
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.buttonColors(containerColor = scheme.onBackground, contentColor = scheme.background),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.sizes.primary),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.width(spacing.sm))
                Text(stringResource(R.string.alert_done), style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(spacing.sm))
        }
    }
}

/** A snooze offer: a real button, thumb-sized, quiet enough not to compete with Done. */
@Composable
private fun SnoozeButton(label: String, onClick: () -> Unit) {
    val haptics = Tokens.haptics
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = {
            haptics.perform(HapticFeedbackType.ContextClick)
            onClick()
        },
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(Tokens.strokes.control, scheme.outline),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurface,
            modifier = Modifier
                .heightIn(min = Tokens.sizes.touch)
                .padding(horizontal = Tokens.spacing.lg, vertical = Tokens.spacing.md),
        )
    }
}

/** What each offer is called. The words are the person's, not the duration's. */
val Snooze.labelRes: Int
    get() = when (this) {
        Snooze.TEN_MINUTES -> R.string.snooze_ten_minutes
        Snooze.TWO_HOURS -> R.string.snooze_two_hours
        Snooze.TOMORROW -> R.string.snooze_tomorrow
        Snooze.WEEKEND -> R.string.snooze_weekend
        Snooze.NEXT_WEEK -> R.string.snooze_next_week
    }
