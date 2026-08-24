package dev.rwilco.ui.alert

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import dev.rwilco.R
import dev.rwilco.model.Snooze
import dev.rwilco.model.kind
import dev.rwilco.ui.components.PresetChip
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
@Composable
fun AlertScreen(
    content: AlertContent,
    preview: Boolean,
    onDone: () -> Unit,
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
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                PresetChip(stringResource(R.string.countdown_minutes, Snooze.TEN_MINUTES.minutes.toInt()), onClick = { onSnooze(Snooze.TEN_MINUTES) })
                PresetChip(stringResource(R.string.countdown_hours, 1), onClick = { onSnooze(Snooze.ONE_HOUR) })
                PresetChip(stringResource(R.string.alert_snooze_tomorrow), onClick = { onSnooze(Snooze.TOMORROW) })
            }
            Spacer(Modifier.height(spacing.lg))
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
            TextButton(
                onClick = onView,
                colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurfaceVariant),
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.sizes.touch),
            ) {
                Text(stringResource(if (preview) R.string.alert_close_preview else R.string.alert_view))
            }
        }
    }
}
