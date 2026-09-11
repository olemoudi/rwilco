package dev.rwilco.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.rwilco.R
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.theme.Tokens
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * One foldable group of settings.
 *
 * A settings screen this size cannot be one scroll: thirty controls in a row is a screen
 * nobody reads, only endures. So it is an index of ten rows, each of which opens where it
 * stands. [summary] is what earns the fold — the current value on the closed row, so most
 * questions are answered without opening anything at all.
 *
 * The rows stay independent on purpose: closing one because another opened would move the
 * header out from under the thumb that just tapped it.
 */
@Composable
fun SettingsGroup(
    icon: ImageVector,
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** Something inside is broken, which is the one thing a fold must never hide. */
    attention: Boolean = false,
    /**
     * A mark on the corner of the group's icon, handed the colour of the card under it: something
     * inside worth seeing before the fold is opened — a downloaded update waiting, say.
     */
    corner: (@Composable (ground: Color) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = Tokens.spacing
    val motion = Tokens.motion
    val haptics = Tokens.haptics
    val scheme = MaterialTheme.colorScheme
    val turn by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(motion.medium, easing = motion.emphasized),
        label = "caret",
    )

    // A group opened by a tap comes into view (0.94.0): "Acerca de" at the bottom of eleven
    // rows unfolded entirely below the fold. Only for a tap — a group that opens itself on
    // arrival, or one restored open, must not move the screen — and once the fold has grown,
    // or the request measures a row that is still a line tall.
    val bringIntoView = remember { BringIntoViewRequester() }
    var wasExpanded by remember { mutableStateOf(expanded) }
    LaunchedEffect(expanded) {
        if (expanded && !wasExpanded) {
            delay(motion.medium.toLong())
            bringIntoView.bringIntoView()
        }
        wasExpanded = expanded
    }
    // Open is a step brighter, which is what ties the cards underneath to this row.
    val ground = if (expanded) scheme.surfaceContainerHigh else scheme.surfaceContainer
    Column(modifier.padding(top = spacing.md)) {
        RwilcoCard(
            onClick = {
                haptics.perform(HapticFeedbackType.SegmentTick)
                onToggle()
            },
            color = ground,
        ) {
            SettingsRow(icon = icon, attention = attention, corner = corner?.let { mark -> @Composable { mark(ground) } }) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (summary.isNotEmpty()) {
                        // Two lines, not one (0.94.0): the summary is the whole point of the
                        // fold, and at a large font scale one line was "Alerta · fuer…".
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (attention) scheme.error else scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(spacing.sm))
                // The caret, because it is the one icon people read as "this opens here"
                // rather than "this goes somewhere else".
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.settings_group_collapse else R.string.settings_group_expand,
                    ),
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.rotate(turn),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(motion.medium, easing = motion.emphasized)) + fadeIn(tween(motion.fast)),
            exit = shrinkVertically(tween(motion.fast, easing = motion.emphasized)) + fadeOut(tween(motion.fast)),
        ) {
            Column(
                modifier = Modifier
                    .padding(top = spacing.sm)
                    .bringIntoViewRequester(bringIntoView),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                content = content,
            )
        }
    }
}

/**
 * A row that goes somewhere else: the arrow says so, where a group's caret says "opens here".
 * The two are the only two things this screen does, and they must never be confused.
 */
@Composable
fun SettingsLinkRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String = "",
    icon: ImageVector? = null,
    attention: Boolean = false,
    /**
     * Whether this row stands in the index itself rather than inside a group.
     *
     * The index is a list of headings and one of them — the backup — is a row instead of a fold,
     * because a group whose whole content is a single link is a fold that costs a tap. It has to
     * *read* like the headings it stands among all the same, and it did not: the same word in a
     * lighter face, which looks like a mistake because it is one.
     */
    topLevel: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    RwilcoCard(onClick = onClick, modifier = modifier) {
        SettingsRow(icon = icon, attention = attention) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = if (topLevel) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    // Set like a heading and announced as one: a screen reader walks the index
                    // by its headings, and this row stands in that list.
                    modifier = if (topLevel) Modifier.semantics { heading() } else Modifier,
                )
                if (summary.isNotEmpty()) {
                    // Trimmed like a fold's, so the two kinds of row in one index agree.
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (attention) scheme.error else scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(Tokens.spacing.sm))
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A setting that is on or off, with its explanation behind the (i). Four of these were the same
 * twenty lines four times over; the switch's inverted colours are the app's "on" everywhere.
 */
@Composable
fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    info: String? = null,
) {
    val haptics = Tokens.haptics
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        SettingTitle(title = title, info = info, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Tokens.spacing.md))
        Switch(
            checked = checked,
            onCheckedChange = { on ->
                if (on) haptics.perform(HapticFeedbackType.ToggleOn)
                onCheckedChange(on)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.surface,
                checkedTrackColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
    }
}

/** The shared geometry of every row on this screen: badge, body, trailing mark. */
@Composable
private fun SettingsRow(
    icon: ImageVector?,
    attention: Boolean,
    corner: (@Composable () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val spacing = Tokens.spacing
    Row(
        modifier = Modifier
            .heightIn(min = Tokens.sizes.control)
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            GroupBadge(icon, attention, corner)
            Spacer(Modifier.width(spacing.md))
        }
        content()
    }
}

/**
 * The small square that carries a group's icon — the same one the editor gives a section, so
 * the two screens read as one app. Neutral by design: amber means "what fires next" and the
 * family hues mean a kind of trigger, and a group of settings is neither. [corner], when there is
 * one, is laid on its top-end corner.
 */
@Composable
private fun GroupBadge(icon: ImageVector, attention: Boolean, corner: (@Composable () -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    Box {
        Surface(
            shape = RoundedCornerShape(Tokens.sizes.badge / 3),
            color = if (attention) scheme.errorContainer else scheme.surfaceContainerHighest,
            modifier = Modifier.size(Tokens.sizes.badge),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (attention) scheme.onErrorContainer else scheme.onSurfaceVariant,
                    modifier = Modifier.size(Tokens.sizes.glyph),
                )
            }
        }
        if (corner != null) Box(Modifier.align(Alignment.TopEnd)) { corner() }
    }
}
