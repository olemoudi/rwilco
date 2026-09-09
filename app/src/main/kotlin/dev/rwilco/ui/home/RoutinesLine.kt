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
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.rwilco.R
import dev.rwilco.model.ContactKind
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
fun RoutinesDoor(total: Int, onOpen: () -> Unit, modifier: Modifier = Modifier, nextDue: RoutineDueUi? = null, now: Instant = Instant.now()) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    // "Al día" said nothing about *how* al día: the door names the next one to run out, so the
    // line is worth a glance even when nothing is owed.
    val words = when {
        total == 0 -> stringResource(R.string.home_routines_title)
        nextDue != null -> stringResource(R.string.home_routines_next, nextDue.text, countdownText(partsBetween(now, nextDue.at)))
        else -> stringResource(R.string.home_routines_ok)
    }
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
    // The header first: "todavía no has hecho: mover el coche" is the whole of what the row
    // says, and a description that started with the words alone dropped the half that made
    // them a complaint.
    val header = stringResource(R.string.home_routines_overdue_title)
    RwilcoCard(
        onClick = onOpen,
        color = scheme.errorContainer,
        modifier = modifier.semantics { contentDescription = header + " " + routine.text + ". " + ago + ". " + opens },
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

/**
 * One contact whose turn has come and gone unanswered: their name, how long it has been, and a
 * tap that lands on them in the list.
 *
 * **Not the error wash the overdue routines wear**, and that is the one deliberate departure
 * from "a card like a vencida". A routine that has run out is a thing you failed to do; a
 * contact whose turn has come is an invitation, and the budget above it exists precisely so
 * this never feels like a debt. So it wears the routines' own colour with the kind's glyph, and
 * red stays for what is actually going wrong.
 */
@Composable
fun ContactRow(contact: ContactNameUi, now: Instant, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    val accent = routineColor()
    val ago = countdownText(partsBetween(now, contact.since))
    val opens = stringResource(R.string.home_routines_open)
    val header = stringResource(R.string.home_contacts_title)
    val kindWords = stringResource(
        if (contact.kind == ContactKind.WORK) R.string.routines_filter_work else R.string.routines_filter_personal,
    )
    RwilcoCard(
        onClick = onOpen,
        rail = accent,
        modifier = modifier.semantics { contentDescription = header + " " + contact.text + ". " + ago + ". " + opens },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Tokens.sizes.touch)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
        ) {
            Icon(
                if (contact.kind == ContactKind.WORK) Icons.Outlined.WorkOutline else Icons.Outlined.Person,
                contentDescription = kindWords,
                tint = accent,
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(text = header, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Text(
                    text = contact.text,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = ago, style = MonoStyles.date, color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(spacing.sm))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant)
        }
    }
}

/**
 * The overdue routines Home does not list one by one — past [HOME_ROUTINE_ROWS], eight red
 * cards stacked over the hero were the list with its own header eight times — counted in one
 * row, in the same wash, opening the routines.
 */
@Composable
fun MoreOverdueRoutinesRow(more: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    val ink = scheme.onErrorContainer
    val words = pluralStringResource(R.plurals.home_routines_more, more, more)
    val opens = stringResource(R.string.home_routines_open)
    RwilcoCard(
        onClick = onOpen,
        color = scheme.errorContainer,
        modifier = modifier.semantics { contentDescription = words + ". " + opens },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Tokens.sizes.touch)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
        ) {
            Text(text = words, style = MaterialTheme.typography.titleMedium, color = ink, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(spacing.sm))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = ink)
        }
    }
}

/** The contacts past [HOME_CONTACT_ROWS], counted in one row rather than stacked. */
@Composable
fun MoreContactsRow(more: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    val words = pluralStringResource(R.plurals.home_contacts_more, more, more)
    val opens = stringResource(R.string.home_routines_open)
    RwilcoCard(
        onClick = onOpen,
        rail = routineColor(),
        modifier = modifier.semantics { contentDescription = words + ". " + opens },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Tokens.sizes.touch)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
        ) {
            Text(text = words, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(spacing.sm))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant)
        }
    }
}
