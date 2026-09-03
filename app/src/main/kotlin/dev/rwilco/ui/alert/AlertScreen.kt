package dev.rwilco.ui.alert

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import dev.rwilco.R
import dev.rwilco.model.Snooze
import dev.rwilco.model.kind
import dev.rwilco.ui.components.GuardIndicator
import dev.rwilco.ui.components.GuardedAction
import dev.rwilco.ui.components.TagLabel
import dev.rwilco.ui.components.TriggerKeycap
import dev.rwilco.ui.components.guarded
import dev.rwilco.ui.components.lampGlow
import dev.rwilco.ui.components.rememberPressGuard
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.format.triggerLine
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.Tracking
import dev.rwilco.ui.theme.icon
import dev.rwilco.model.DEFAULT_SNOOZE_MINUTES
import dev.rwilco.ui.components.SnoozeOffers
import dev.rwilco.model.SnoozePlace

/**
 * The lamp at full brightness. The reminder's words as big as they fit, and one button the
 * thumb cannot miss. In phase 1 it is reached only as a preview; phase 2 hosts the same
 * composable in the activity a full-screen intent launches.
 *
 * **Every answer on it is guarded** (0.66.0, [rememberPressGuard]): for a second after the
 * screen shows nothing but Silence takes a touch, and after that "Hecho", the snoozes and
 * "Ver" answer only to a finger kept on them — the ring at the top fills, the
 * tick comes up, and the answer is given when the finger lifts. The screen is what comes up
 * under a hand reaching into a pocket, and its answers are the kind that cannot be taken
 * back; the preview keeps the guard because it is a preview of exactly that.
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
    /**
     * Whether somebody chose to see this — a card or a note tapped — rather than the screen
     * taking over. The guard keeps its hold and skips its countdown; see [rememberPressGuard].
     */
    openedOnPurpose: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val spacing = Tokens.spacing
    val haptics = Tokens.haptics
    val locale = currentLocale()
    // A new reminder on the same screen is a new guard: the thumb that answered the last one
    // is still where this one's "Hecho" is.
    val guard = rememberPressGuard(content, openedOnPurpose = openedOnPurpose)
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
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = Tracking.eyebrow, fontWeight = FontWeight.SemiBold),
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
            // Where the guard reports: the countdown, the filling ring, the tick. Up here and
            // not around the button, because the hand holding the button is over the button.
            Spacer(Modifier.height(spacing.md))
            GuardIndicator(guard, modifier = Modifier.fillMaxWidth())
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
            SnoozeOffers(offers = Snooze.entries, customMinutes = customMinutes, onPick = onSnooze, places = places, onPickPlace = onSnoozeToPlace, guard = guard)
            Spacer(Modifier.height(spacing.lg))
            // "Ver" goes ABOVE "Hecho", not under it. The bottom of the screen is where the
            // thumb lands, and it belongs to the one answer this screen is asking for — an
            // alarm answered half awake must not be able to hand somebody the edit form
            // instead. Which is exactly what it did.
            val viewLabel = stringResource(if (preview) R.string.alert_close_preview else R.string.alert_view)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.sizes.touch)
                    .clip(MaterialTheme.shapes.medium)
                    .then(
                        // "Cerrar la vista previa" is a control of the preview, not an answer
                        // to a reminder: a plain tap (0.68.0). "Hecho" and the snoozes keep
                        // the guard, because showing the real gesture is what the preview is for.
                        if (preview) Modifier.clickable(role = Role.Button, onClick = onView)
                        else Modifier.guarded(guard, GuardedAction(icon = Icons.AutoMirrored.Outlined.OpenInNew, holding = viewLabel), onConfirmed = onView),
                    ),
            ) {
                Text(text = viewLabel, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
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
            //
            // **And it is the one thing on the screen that is still a tap.** Silencing confirms
            // nothing and can be reflexive; "Hecho" cannot, so it is a hold like everything
            // else here (see [rememberPressGuard]).
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
            val doneLabel = stringResource(R.string.alert_done)
            Surface(
                shape = MaterialTheme.shapes.large,
                color = fill,
                contentColor = ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.sizes.primary)
                    .clip(MaterialTheme.shapes.large)
                    .then(
                        if (silencing) {
                            Modifier.clickable(role = Role.Button) {
                                haptics.perform(HapticFeedbackType.ContextClick)
                                onSilence()
                            }
                        } else {
                            Modifier.guarded(guard, GuardedAction(icon = Icons.Filled.Check, holding = doneLabel), onConfirmed = onDone)
                        },
                    ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(ButtonDefaults.ContentPadding),
                ) {
                    Icon(if (silencing) Icons.AutoMirrored.Filled.VolumeOff else Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(spacing.sm))
                    Text(
                        text = if (silencing) stringResource(R.string.alert_silence) else doneLabel,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
            Spacer(Modifier.height(spacing.sm))
        }
    }
}
