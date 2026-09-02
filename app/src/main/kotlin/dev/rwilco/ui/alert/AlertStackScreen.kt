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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rwilco.R
import dev.rwilco.model.Snooze
import dev.rwilco.model.kind
import dev.rwilco.ui.components.GuardIndicator
import dev.rwilco.ui.components.GuardedAction
import dev.rwilco.ui.components.PressGuard
import dev.rwilco.ui.components.TagLabel
import dev.rwilco.ui.components.TriggerKeycap
import dev.rwilco.ui.components.asleepUntilArmed
import dev.rwilco.ui.components.guarded
import dev.rwilco.ui.components.lampGlow
import dev.rwilco.ui.components.rememberPressGuard
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.format.triggerLine
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.icon
import dev.rwilco.model.AppSettings
import dev.rwilco.model.DEFAULT_SNOOZE_MINUTES
import dev.rwilco.model.NOTIFICATION_SNOOZES
import dev.rwilco.ui.format.snoozeLabel
import dev.rwilco.model.notificationSnoozeOffers

/** One reminder on the alert screen: its id, for the answer, and what it shows. */
data class AlertItem(val id: String, val content: AlertContent)

/**
 * Several reminders ringing at once, the screen split into a strip per reminder. Each strip is
 * the alert screen in small: the words, what fired it, and its own "Hecho" where the thumb
 * lands — with the two snoozes people actually use, because five do not fit beside a button.
 * Up to three share the height; more than that scroll.
 *
 * Guarded like the single alert (0.66.0, [rememberPressGuard]): two seconds in which only
 * Silence answers, then every answer a held finger — one guard for the whole screen, reporting
 * at the top, and it starts over whenever a strip leaves, because the ones left take its
 * height and move under the thumb.
 */
@Composable
fun AlertStackScreen(
    items: List<AlertItem>,
    onDone: (String) -> Unit,
    onSnooze: (String, Snooze) -> Unit,
    onView: (String) -> Unit,
    /** The two offers a strip has room for — the notification's own — and the custom one's length. */
    snoozes: List<Snooze> = AppSettings().notificationSnoozeOffers,
    customMinutes: Int = DEFAULT_SNOOZE_MINUTES,
    /** One answer for all of them: five ringing at once used to be five taps down a scroll. */
    onDoneAll: () -> Unit = {},
    onSnoozeAll: (Snooze) -> Unit = {},
    /** Whether it is making a noise right this second; see [SilenceRow]. */
    ringing: Boolean = false,
    onSilence: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val spacing = Tokens.spacing
    val locale = currentLocale()
    val guard = rememberPressGuard(items.map { it.id })
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
            Spacer(Modifier.height(spacing.sm))
            GuardIndicator(guard, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(spacing.md))
            if (items.size <= SHARED_STRIPS) {
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    for (item in items) {
                        key(item.id) {
                            Strip(item, guard, onDone, onSnooze, onView, snoozes, customMinutes, Modifier.weight(1f))
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    items(items, key = { it.id }) { item ->
                        Strip(item, guard, onDone, onSnooze, onView, snoozes, customMinutes, Modifier.heightIn(min = STRIP_MIN_HEIGHT))
                    }
                }
            }
            Spacer(Modifier.height(spacing.md))
            SilenceRow(ringing, onSilence)
            AllRow(guard, snoozes, customMinutes, onDoneAll, onSnoozeAll)
        }
    }
}

/**
 * "Silenciar", across the width, while there is a noise that keeps going to silence.
 *
 * Which noises those are is [dev.rwilco.model.asksToBeSilenced]: the buzz and the insistent
 * tone, never a single "sonido" that is over before a thumb could reach this.
 *
 * A row of its own here rather than the swap the single alert does with its "Hecho". There is no
 * one button on this screen to take over — every strip has its own, and each of them belongs to
 * a reminder somebody has to have *read* to answer — so nothing is taken away: the loudest thing
 * on the screen becomes the one that stops the noise, and everything else stays where it was.
 * It leaves with the noise, and the strips take the height back. And it is the one tap on the
 * screen: silencing confirms nothing.
 */
@Composable
private fun SilenceRow(ringing: Boolean, onSilence: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val spacing = Tokens.spacing
    val haptics = Tokens.haptics
    AnimatedVisibility(visible = ringing) {
        Column {
            Button(
                onClick = {
                    haptics.perform(HapticFeedbackType.ContextClick)
                    onSilence()
                },
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.errorContainer,
                    contentColor = scheme.onErrorContainer,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.sizes.control),
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeOff, contentDescription = null)
                Spacer(Modifier.width(spacing.sm))
                Text(stringResource(R.string.alert_silence), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(spacing.sm))
        }
    }
}

/**
 * The answer given to all of them at once, in the thumb zone under the strips. "Hecho con
 * todos" is the screen's guarded hold like every other answer — it is three in the morning and
 * five reminders are gone on release — and "posponer todos" unfolds the same two offers a
 * strip has, once, for everyone. Unfolding is not an answer, so it stays a tap; it only sleeps
 * through the countdown with the rest.
 */
@Composable
private fun AllRow(guard: PressGuard, snoozes: List<Snooze>, customMinutes: Int, onDoneAll: () -> Unit, onSnoozeAll: (Snooze) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val spacing = Tokens.spacing
    var snoozingAll by rememberSaveable { mutableStateOf(false) }
    val snoozeAllLabel = stringResource(R.string.alert_snooze_all)
    val snoozedAllLabel = stringResource(R.string.alert_snoozed_all)
    val doneAllLabel = stringResource(R.string.alert_done_all)
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        if (snoozingAll) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
                for (snooze in snoozes.take(NOTIFICATION_SNOOZES)) {
                    val label = snoozeLabel(snooze, customMinutes)
                    HeldPill(
                        guard = guard,
                        action = GuardedAction(Icons.Outlined.Snooze, holding = snoozeAllLabel, done = snoozedAllLabel, detail = label),
                        onConfirmed = { onSnoozeAll(snooze) },
                        label = label,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Surface(
                onClick = { snoozingAll = !snoozingAll },
                enabled = guard.armed,
                shape = MaterialTheme.shapes.medium,
                color = if (snoozingAll) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh,
                border = BorderStroke(Tokens.strokes.control, scheme.outline),
                modifier = Modifier.weight(1f).asleepUntilArmed(guard),
            ) {
                Box(modifier = Modifier.heightIn(min = Tokens.sizes.control), contentAlignment = Alignment.Center) {
                    Text(text = snoozeAllLabel, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
                }
            }
            HeldPill(
                guard = guard,
                action = GuardedAction(Icons.Filled.Check, holding = doneAllLabel),
                onConfirmed = onDoneAll,
                label = doneAllLabel,
                icon = Icons.Filled.Check,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Strip(
    item: AlertItem,
    guard: PressGuard,
    onDone: (String) -> Unit,
    onSnooze: (String, Snooze) -> Unit,
    onView: (String) -> Unit,
    snoozes: List<Snooze>,
    customMinutes: Int,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val spacing = Tokens.spacing
    val content = item.content
    val viewLabel = stringResource(R.string.alert_view)
    val snoozeVerb = stringResource(R.string.alert_snooze)
    val snoozedWord = stringResource(R.string.alert_snoozed)
    val doneLabel = stringResource(R.string.alert_done)
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
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .heightIn(min = Tokens.sizes.touch)
                        .clip(MaterialTheme.shapes.medium)
                        .guarded(guard, GuardedAction(Icons.AutoMirrored.Outlined.OpenInNew, holding = viewLabel), onConfirmed = { onView(item.id) })
                        .padding(horizontal = spacing.md),
                ) {
                    Text(text = viewLabel, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                for (snooze in snoozes.take(NOTIFICATION_SNOOZES)) {
                    val label = snoozeLabel(snooze, customMinutes)
                    HeldPill(
                        guard = guard,
                        action = GuardedAction(Icons.Outlined.Snooze, holding = snoozeVerb, done = snoozedWord, detail = label),
                        onConfirmed = { onSnooze(item.id, snooze) },
                        label = label,
                        minHeight = Tokens.sizes.touch,
                    )
                }
                HeldPill(
                    guard = guard,
                    action = GuardedAction(Icons.Filled.Check, holding = doneLabel),
                    onConfirmed = { onDone(item.id) },
                    label = doneLabel,
                    icon = Icons.Filled.Check,
                    loud = true,
                    textStyle = MaterialTheme.typography.titleSmall,
                    minHeight = Tokens.sizes.touch,
                )
            }
        }
    }
}

/**
 * A pill that answers to a held finger: the strips' snoozes and "Hecho", and the row for all of
 * them. Quiet by default; [loud] is the strip's own "Hecho", in the colours the single alert's
 * big button wears.
 */
@Composable
private fun HeldPill(
    guard: PressGuard,
    action: GuardedAction,
    onConfirmed: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    loud: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    minHeight: Dp = Tokens.sizes.control,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Surface(
        shape = shape,
        color = if (loud) scheme.onBackground else scheme.surfaceContainerHigh,
        contentColor = if (loud) scheme.background else scheme.onSurface,
        border = if (loud) null else BorderStroke(Tokens.strokes.control, scheme.outline),
        modifier = modifier.clip(shape).guarded(guard, action, onConfirmed),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.heightIn(min = minHeight).padding(horizontal = Tokens.spacing.md),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null)
                Spacer(Modifier.width(Tokens.spacing.xs))
            }
            Text(text = label, style = textStyle)
        }
    }
}

/** Up to this many strips share the height; past it they scroll at a fixed minimum. */
private const val SHARED_STRIPS = 3
private val STRIP_MIN_HEIGHT = 220.dp
