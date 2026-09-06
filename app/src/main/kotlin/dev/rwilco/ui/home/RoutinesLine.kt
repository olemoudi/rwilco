package dev.rwilco.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.rwilco.R
import dev.rwilco.model.partsBetween
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.format.countdownText
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.routineColor
import java.time.Instant

/**
 * Home's way to the routines: one row when nothing is owed, and **one row per overdue routine**
 * when something is.
 *
 * The routines live on their own screen (see `Routines.kt`), and the owner asked for the way
 * there to be a row a thumb finds rather than an icon in the header. A box counting them was
 * the first answer and the wrong one: "3 vencidas" is a number to go and decode, while a row
 * per routine is the thing itself — it says which, it says how long it has been, and the tap
 * lands on the routine it names ([onOpen] carries the id, and the routines screen scrolls to
 * it). Amber is never used here: it means what fires next, and none of this is due to ring.
 */
@Composable
fun RoutinesDoor(total: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    val words = stringResource(if (total == 0) R.string.home_routines_title else R.string.home_routines_ok)
    val opens = stringResource(R.string.home_routines_open)
    RwilcoCard(
        onClick = onOpen,
        color = scheme.surfaceContainer,
        // The row's own words first and the door second: a description of just "abrir las
        // rutinas" replaces everything under it for a screen reader.
        modifier = modifier.semantics { contentDescription = words + ". " + opens },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Tokens.sizes.touch)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
        ) {
            Icon(Icons.Outlined.Autorenew, contentDescription = null, tint = routineColor())
            Spacer(Modifier.width(spacing.md))
            Text(
                text = words,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(spacing.sm))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant)
        }
    }
}

/**
 * One routine whose span has run out: its own words, how long it has been, and a tap that lands
 * on it in the routines screen. In the error wash, which is what "the span ran out" looks like
 * everywhere in this app — the same ink the routine's own row wears over there.
 */
@Composable
fun OverdueRoutineRow(routine: RoutineNameUi, now: Instant, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    val ink = scheme.onErrorContainer
    // Already "hace 30 d": the countdown says which side of now it is on, and saying it twice
    // is what the first draft of this row did.
    val ago = countdownText(partsBetween(now, routine.since))
    val opens = stringResource(R.string.home_routines_open)
    RwilcoCard(
        onClick = onOpen,
        color = scheme.errorContainer,
        modifier = modifier.semantics { contentDescription = routine.text + ". " + ago + ". " + opens },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Tokens.sizes.touch)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
        ) {
            Icon(Icons.Outlined.Autorenew, contentDescription = null, tint = ink)
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_routines_overdue_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = ink,
                )
                Text(
                    text = routine.text,
                    style = MaterialTheme.typography.titleMedium,
                    color = ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = ago, style = MonoStyles.date, color = ink)
            }
            Spacer(Modifier.width(spacing.sm))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = ink)
        }
    }
}
