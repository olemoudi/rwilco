package dev.rwilco.ui.alert

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import dev.rwilco.model.DEFAULT_SNOOZE_MINUTES
import dev.rwilco.ui.components.SnoozeOffers
import dev.rwilco.model.SnoozePlace

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
    /** How long the custom offer is, for its label. */
    customMinutes: Int = DEFAULT_SNOOZE_MINUTES,
    /** The place answers this phone can give: "al llegar a casa", "al salir de aquí". */
    places: List<SnoozePlace> = emptyList(),
    onSnoozeToPlace: (SnoozePlace) -> Unit = {},
    /**
     * Whether there is a noise going on that will still be going when a thumb arrives — a buzz
     * or the insistent tone, never a single one that has already stopped. While there is, the
     * one big button silences instead of dismissing; see the button itself, and
     * [dev.rwilco.model.asksToBeSilenced] for which noises count.
     */
    ringing: Boolean = false,
    onSilence: () -> Unit = {},
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
            // **The words are what gives.** They used to sit between two weighted spacers,
            // which centres them beautifully and lets everything below overflow: seven snooze
            // offers (five, once) plus a six-line reminder at a large font scale pushed "Hecho"
            // — the one answer this screen exists to take — off the bottom of a ringing alarm,
            // with no scroll to reach it. Weighted, this block is measured with whatever is
            // left once the buttons have had theirs, and the auto-sizing text steps down into
            // it; the centring is kept by the arrangement rather than by the spacers.
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
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
            }
            Spacer(Modifier.height(spacing.lg))
            Text(
                text = stringResource(R.string.alert_snooze),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.sm))
            // Every answer, each its own tap target and none of them a chip a thumb has to aim
            // at. They sit clear of the Done button, because the two mean opposite things and a
            // half-awake hand should not be able to confuse them.
            SnoozeOffers(offers = Snooze.entries, customMinutes = customMinutes, onPick = onSnooze, places = places, onPickPlace = onSnoozeToPlace)
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
            // **While it is ringing, the big button is "Silenciar" and not "Hecho".**
            //
            // The one place a thumb lands on a screen that woke somebody up should not be the
            // one that files the reminder away. Half awake, with the phone buzzing, "make it
            // stop" and "I have done that" are the same reflex and only one of them is true —
            // and a reminder dismissed without being read is gone for good. So the noise is
            // answered first, on its own button, and the screen stays exactly as it was: the
            // words, the snoozes and "Ver" all still there to decide with.
            //
            // **Only where there is still a noise to make that mistake with.** A plain "sonido"
            // says its tone once and stops after a second or two; the step used to stand for
            // the whole minute after it, holding "hecho" out of reach in a silent room, which
            // is a step somebody pays for and gets nothing back from. See [asksToBeSilenced].
            //
            // One button and not two, so nothing moves under the thumb: it changes colour,
            // glyph and word in place — red container to the plain white "Hecho" — and that
            // change is the whole of the feedback that the tap did something. Two buttons
            // stacked would put "Hecho" where the eye already is and make the silence a step
            // somebody skips.
            val silencing = ringing
            val fill by animateColorAsState(
                targetValue = if (silencing) scheme.errorContainer else scheme.onBackground,
                animationSpec = tween(Tokens.motion.medium),
                label = "alertPrimaryFill",
            )
            val ink by animateColorAsState(
                targetValue = if (silencing) scheme.onErrorContainer else scheme.background,
                animationSpec = tween(Tokens.motion.medium),
                label = "alertPrimaryInk",
            )
            Button(
                onClick = {
                    // Silencing confirms nothing; the reminder is still owed an answer.
                    haptics.perform(if (silencing) HapticFeedbackType.ContextClick else HapticFeedbackType.Confirm)
                    if (silencing) onSilence() else onDone()
                },
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = ink),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.sizes.primary),
            ) {
                Icon(if (silencing) Icons.AutoMirrored.Filled.VolumeOff else Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.width(spacing.sm))
                Text(
                    text = stringResource(if (silencing) R.string.alert_silence else R.string.alert_done),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Spacer(Modifier.height(spacing.sm))
        }
    }
}
