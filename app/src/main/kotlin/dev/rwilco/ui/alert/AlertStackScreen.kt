package dev.rwilco.ui.alert

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/** One reminder on the alert screen: its id, for the answer, and what it shows. */
data class AlertItem(val id: String, val content: AlertContent)

/**
 * Several reminders ringing at once, the screen split into a strip per reminder. Each strip is
 * the alert screen in small: the words, what fired it, and its own "Hecho" where the thumb
 * lands — with the two snoozes people actually use, because five do not fit beside a button.
 * Up to three share the height; more than that scroll.
 */
@Composable
fun AlertStackScreen(
    items: List<AlertItem>,
    onDone: (String) -> Unit,
    onSnooze: (String, Snooze) -> Unit,
    onView: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val spacing = Tokens.spacing
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
                    text = stringResource(R.string.app_name).uppercase(locale),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold),
                    color = scheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = pluralStringResource(R.plurals.alert_count, items.size, items.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(spacing.md))
            if (items.size <= SHARED_STRIPS) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    for (item in items) {
                        key(item.id) {
                            Strip(item, onDone, onSnooze, onView, Modifier.weight(1f))
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    items(items, key = { it.id }) { item ->
                        Strip(item, onDone, onSnooze, onView, Modifier.heightIn(min = STRIP_MIN_HEIGHT))
                    }
                }
            }
        }
    }
}

@Composable
private fun Strip(
    item: AlertItem,
    onDone: (String) -> Unit,
    onSnooze: (String, Snooze) -> Unit,
    onView: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val spacing = Tokens.spacing
    val haptics = Tokens.haptics
    val content = item.content
    Surface(
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(Tokens.strokes.edge, scheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxSize().padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                content.trigger?.let { trigger ->
                    TriggerKeycap(family = content.family, icon = trigger.kind.icon, contentDescription = null)
                    Spacer(Modifier.width(spacing.sm))
                    val line = triggerLine(trigger, content.today, content.defaultTime)
                    Text(
                        text = line.primary + " · " + line.secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(spacing.sm))
            BasicText(
                text = content.text.ifBlank { stringResource(R.string.alert_empty_text) },
                style = MaterialTheme.typography.headlineMedium.copy(color = scheme.onBackground),
                autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 36.sp, stepSize = 2.sp),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            )
            if (content.tags.isNotEmpty()) {
                Spacer(Modifier.height(spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    for (tag in content.tags.take(3)) TagLabel(tag)
                }
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(spacing.md))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                TextButton(
                    onClick = { onView(item.id) },
                    colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurfaceVariant),
                ) { Text(stringResource(R.string.alert_view)) }
                Spacer(Modifier.weight(1f))
                for (snooze in listOf(Snooze.TEN_MINUTES, Snooze.TWO_HOURS)) {
                    Surface(
                        onClick = {
                            haptics.perform(HapticFeedbackType.ContextClick)
                            onSnooze(item.id, snooze)
                        },
                        shape = MaterialTheme.shapes.medium,
                        color = scheme.surfaceContainerHigh,
                        border = BorderStroke(Tokens.strokes.control, scheme.outline),
                    ) {
                        Box(
                            modifier = Modifier.heightIn(min = Tokens.sizes.touch).padding(horizontal = spacing.md),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = stringResource(snooze.labelRes), style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
                        }
                    }
                }
                Button(
                    onClick = {
                        haptics.perform(HapticFeedbackType.Confirm)
                        onDone(item.id)
                    },
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.onBackground, contentColor = scheme.background),
                    modifier = Modifier.heightIn(min = Tokens.sizes.touch),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(spacing.xs))
                    Text(stringResource(R.string.alert_done), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

/** Up to this many strips share the height; past it they scroll at a fixed minimum. */
private const val SHARED_STRIPS = 3
private val STRIP_MIN_HEIGHT = 220.dp
