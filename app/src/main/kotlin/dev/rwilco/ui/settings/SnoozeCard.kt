package dev.rwilco.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.rwilco.R
import dev.rwilco.model.AppSettings
import dev.rwilco.model.RemovedCustomSnooze
import dev.rwilco.model.SNOOZE_PLACES
import dev.rwilco.model.Snooze
import dev.rwilco.model.SnoozeLimits
import dev.rwilco.model.SnoozeOffer
import dev.rwilco.model.SnoozeSpec
import dev.rwilco.model.expires
import dev.rwilco.model.removedCustomSnooze
import dev.rwilco.model.snoozeOffers
import dev.rwilco.model.snoozeTerms
import dev.rwilco.model.until
import dev.rwilco.ui.components.LocalSnackbar
import dev.rwilco.ui.components.PresetChip
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.Stepper
import dev.rwilco.ui.components.rememberNow
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.dayWord
import dev.rwilco.ui.format.durationText
import dev.rwilco.ui.format.rememberWords
import dev.rwilco.ui.format.snoozeLabel
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import java.time.Clock

/**
 * The things about a snooze that are the person's to say: how long "a little later" is, the
 * snoozes that are their own (0.138.0), which offers the alert shows (0.137.0), and which two the
 * notification carries — it has room for three buttons and "hecho" is one.
 *
 * **Nothing chosen here takes an answer away.** The alert used to show every offer every time —
 * up to ten held buttons on the one screen answered half awake — and an offer switched off in
 * the third row is one door further, behind "a otro momento", not gone ([dev.rwilco.model.snoozeBoard]).
 *
 * The person's own are offers like any other from the moment they exist: the two rows under them
 * list the app's and theirs together, in the one order the alert uses. Only a part of today stays
 * off the notification's row — a card can sit in the shade long after "esta tarde" has gone.
 */
@Composable
fun SnoozeCard(
    settings: AppSettings,
    /** The app's clock: what "ahora mismo" on each of the person's own is worked out from. */
    clock: Clock,
    onCustomMinutes: (Int) -> Unit,
    /** One of the notification's two, by its key. */
    onPick: (String) -> Unit,
    /** An offer on the alert, or behind "a otro momento": by its key, or [SNOOZE_PLACES]. */
    onShown: (String, Boolean) -> Unit = { _, _ -> },
    onAddOwn: (SnoozeSpec) -> Unit = {},
    onRemoveOwn: (String) -> Unit = {},
    /** The undo: everything that went with it, back as it was. */
    onRestoreOwn: (RemovedCustomSnooze) -> Unit = {},
) {
    val spacing = Tokens.spacing
    val words = rememberWords()
    val offers = settings.snoozeOffers
    var adding by rememberSaveable { mutableStateOf(false) }
    RwilcoCard {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
            Column {
                SettingTitle(
                    title = stringResource(R.string.settings_snooze_custom),
                    info = stringResource(R.string.settings_snooze_custom_hint),
                )
                Spacer(Modifier.height(spacing.sm))
                Stepper(
                    valueLabel = durationText(words, settings.snoozeCustomMinutes),
                    onDecrement = { onCustomMinutes(settings.snoozeCustomMinutes - SnoozeLimits.STEP) },
                    onIncrement = { onCustomMinutes(settings.snoozeCustomMinutes + SnoozeLimits.STEP) },
                    decrementEnabled = settings.snoozeCustomMinutes > SnoozeLimits.CUSTOM_MINUTES.first,
                    incrementEnabled = settings.snoozeCustomMinutes < SnoozeLimits.CUSTOM_MINUTES.last,
                )
            }
            OwnSnoozes(
                settings = settings,
                clock = clock,
                own = offers.filterIsInstance<SnoozeOffer.Custom>(),
                onAdd = { adding = true },
                onRemove = onRemoveOwn,
                onRestore = onRestoreOwn,
            )
            Column {
                SettingTitle(
                    title = stringResource(R.string.settings_snooze_shown),
                    info = stringResource(R.string.settings_snooze_shown_hint),
                )
                Spacer(Modifier.height(spacing.sm))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    for (offer in offers) {
                        val shown = offer.key !in settings.hiddenSnoozes
                        PresetChip(
                            label = snoozeLabel(words, offer, settings.snoozeCustomMinutes),
                            selected = shown,
                            onClick = { onShown(offer.key, !shown) },
                        )
                    }
                    // The phone's own offers, as one: "al llegar a casa", "al salir de aquí".
                    val placesShown = SNOOZE_PLACES !in settings.hiddenSnoozes
                    PresetChip(
                        label = stringResource(R.string.settings_snooze_shown_places),
                        selected = placesShown,
                        onClick = { onShown(SNOOZE_PLACES, !placesShown) },
                    )
                }
            }
            Column {
                SettingTitle(
                    title = stringResource(R.string.settings_snooze_notification),
                    info = stringResource(R.string.settings_snooze_notification_hint),
                )
                Spacer(Modifier.height(spacing.sm))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    for (offer in offers.filterNot { it.expires }) {
                        PresetChip(
                            label = snoozeLabel(words, offer, settings.snoozeCustomMinutes),
                            selected = offer.key in settings.notificationSnoozes,
                            onClick = { onPick(offer.key) },
                        )
                    }
                }
            }
        }
    }
    if (adding) {
        SnoozeBuilderSheet(
            settings = settings,
            clock = clock,
            onAdd = { spec ->
                onAddOwn(spec)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}

/**
 * "Tus posponer": each with what its button says and the moment it would come back at if it were
 * pressed now — which is how somebody finds out that their "finde por la noche" is Saturday's.
 * The bin forgets one from everywhere it was named, and the snackbar puts all of that back.
 */
@Composable
private fun OwnSnoozes(
    settings: AppSettings,
    clock: Clock,
    own: List<SnoozeOffer.Custom>,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onRestore: (RemovedCustomSnooze) -> Unit,
) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    val words = rememberWords()
    val snackbar = LocalSnackbar.current
    val removedMessage = stringResource(R.string.settings_snooze_own_removed)
    val undoLabel = stringResource(R.string.common_undo)
    val now by rememberNow(60_000, clock)
    val today = now.atZone(clock.zone).toLocalDate()
    Column {
        SettingTitle(
            title = stringResource(R.string.settings_snooze_own),
            info = stringResource(R.string.settings_snooze_own_hint),
        )
        Spacer(Modifier.height(spacing.sm))
        if (own.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_snooze_own_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            for (offer in own) {
                val back = offer.spec.until(now, clock.zone, settings.snoozeTerms)?.atZone(clock.zone)
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = scheme.surfaceContainerHigh,
                    border = BorderStroke(Tokens.strokes.control, scheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = spacing.md, top = spacing.sm, bottom = spacing.sm),
                    ) {
                        Icon(Icons.Outlined.Snooze, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(Tokens.sizes.glyphMedium))
                        Spacer(Modifier.width(spacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = snoozeLabel(words, offer.spec),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (back == null) stringResource(R.string.settings_snooze_own_gone)
                                else stringResource(R.string.settings_snooze_own_now, dayWord(words, back.toLocalDate(), today) + " " + TimeText.time(back.toLocalTime(), words.is24h, words.locale)),
                                style = MonoStyles.date,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            val removed = settings.removedCustomSnooze(offer.key) ?: return@IconButton
                            onRemove(offer.key)
                            snackbar.show(removedMessage, undoLabel) { onRestore(removed) }
                        }) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.settings_snooze_own_remove, snoozeLabel(words, offer.spec)),
                                tint = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(spacing.md))
        OutlinedButton(
            onClick = onAdd,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(Tokens.strokes.control, scheme.outline),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = scheme.surfaceContainerHigh,
                contentColor = scheme.onSurface,
            ),
            contentPadding = PaddingValues(horizontal = spacing.lg),
            modifier = Modifier.heightIn(min = Tokens.sizes.touch),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(Tokens.sizes.glyphSmall))
            Spacer(Modifier.width(spacing.sm))
            Text(stringResource(R.string.settings_snooze_own_add), style = MaterialTheme.typography.labelLarge)
        }
    }
}
