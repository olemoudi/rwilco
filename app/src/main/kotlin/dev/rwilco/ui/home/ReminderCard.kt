package dev.rwilco.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.IconButton
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.model.Action
import dev.rwilco.model.countsFromRinging
import dev.rwilco.model.RuleStanding
import dev.rwilco.model.Recurrence
import dev.rwilco.model.TriggerFamily
import dev.rwilco.model.kind
import dev.rwilco.ui.components.FittingRow
import dev.rwilco.ui.components.HoldButton
import dev.rwilco.ui.components.RuleTree
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.TagLabel
import dev.rwilco.ui.components.TriggerKeycap
import dev.rwilco.ui.components.rememberNow
import dev.rwilco.ui.LocalClock
import dev.rwilco.model.partsBetween
import dev.rwilco.ui.format.countdownText
import dev.rwilco.model.conditions
import dev.rwilco.ui.editor.titleRes
import dev.rwilco.ui.format.recurrenceLabel
import dev.rwilco.ui.format.rememberWords
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.conditionLabel
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.format.dayWord
import dev.rwilco.ui.format.rememberIs24h
import dev.rwilco.ui.format.triggerLine
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.LocalDarkTheme
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.familyColor
import dev.rwilco.ui.theme.tagColor
import dev.rwilco.ui.theme.icon
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import dev.rwilco.ui.format.placePhrase
import dev.rwilco.model.Trigger

/**
 * One reminder at a glance. [modifier] is where Home hangs the accessibility actions for the
 * swipes: a gesture is not a thing a screen reader can do, so Done and Delete are offered as
 * actions on the card itself.
 *
 * **[compact] is the same reminder as one line** — the words, then a row of small glyphs for
 * what rings it and what it does, and its tags on the right. It is a way of reading the *list*
 * rather than the reminder: thirty cards at full height is a lot of scrolling to answer "what
 * have I got on". What it costs is said plainly — the rules lose their words and keep only
 * their kind, the standing marks go with them, and the pause control goes (the held menu still
 * has it).
 *
 * **The tap is the fold, both ways** (0.71.0): a card opens out under it and closes back under
 * it, so the one gesture the whole list answers to is the one that costs nothing and can be
 * taken back by doing it again. The form is behind the pencil in the corner ([onEdit]) —
 * named, deliberate, and never somewhere a thumb arrives by accident. It used to be the tap on
 * an open card, which meant that reading a reminder and leaving for a form were the same
 * gesture, told apart only by what the card happened to be doing at the time.
 */
@Composable
fun ReminderCard(
    card: ReminderCardUi,
    today: LocalDate,
    defaultTime: LocalTime,
    zone: ZoneId,
    /** The pencil: the form for this reminder. */
    onEdit: () -> Unit,
    onTogglePause: () -> Unit,
    /** Held: the menu of what can be done to this reminder. */
    onLongClick: () -> Unit = {},
    longClickLabel: String? = null,
    compact: Boolean = false,
    /** The tap, on a card of either height: out on a folded one, away on an open one. */
    onToggleCompact: () -> Unit = {},
    /** Just saved and just arrived at: lit for a moment so the eye knows which card moved. */
    marked: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (compact) {
        CompactCard(card, onToggleCompact, onLongClick, longClickLabel, marked, modifier)
        return
    }
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    val textColor = if (card.paused) scheme.onSurfaceVariant else scheme.onSurface
    // A paused reminder is not going to ring at all, so its band drops the colour with the rest
    // of the card and goes the same grey.
    val rail = card.railTag?.let { if (card.paused) scheme.onSurfaceVariant else tagColor(it) }
    RwilcoCard(
        onClick = onToggleCompact,
        onLongClick = onLongClick,
        longClickLabel = longClickLabel,
        clickLabel = stringResource(R.string.card_compact),
        modifier = modifier,
        rail = rail,
        color = markedColour(marked),
    ) {
        // A card is a glance, not a page — but the words are the glance, so they get the width
        // and the size, and the one control goes down to the footer with the rest of the
        // furniture. Under the title: the rules, then the read-only footer.
        Column(modifier = Modifier.padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = card.text,
                    style = MaterialTheme.typography.titleLarge,
                    color = textColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // The one thing on an open card that is not the tap, and the only way to the
                // form: everything else a card does can be taken back by doing it again, and
                // leaving the list is not that.
                // At the size every other icon that *acts* is drawn, not the 16dp of the
                // read-only marks below it: muted enough to stay out of the way of the words,
                // legible enough to be seen as a thing to press.
                val editHaptics = Tokens.haptics
                IconButton(onClick = { editHaptics.perform(HapticFeedbackType.ContextClick); onEdit() }) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        // Named after the reminder it belongs to: a list of thirty cards is a
                        // list of thirty pencils, and "edit this one" said thirty times over
                        // does not say which one to a screen reader.
                        contentDescription = stringResource(R.string.card_edit, card.text),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(spacing.md))
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                // Before everything: a moment that got away is the one thing this card is
                // about, and the rows under it go on describing the rule as if it were ahead.
                card.missedAt?.let { MissedRow(it) }
                // First, because it is what happens next: a snooze outranks every rule under it
                // until it has rung.
                card.snoozedUntil?.let { SnoozedRow(it, today, zone, muted = card.paused) }
                card.snoozedToPlace?.let { SnoozedPlaceRow(it, muted = card.paused) }
                // More than one rule is an arrangement, and the tree is what says which one.
                // A single rule is just itself, and hangs off nothing.
                if (card.match != null && card.triggers.size > 1) {
                    RuleTree(match = card.match, count = card.triggers.size, muted = card.paused) { index ->
                        TriggerRow(card.triggers[index], today, defaultTime, muted = card.paused)
                    }
                } else {
                    for (row in card.triggers) TriggerRow(row, today, defaultTime, muted = card.paused)
                }
                // Last, because that is the order the two answer in: the triggers say when it
                // rings the first time and the recurrence says when it comes back.
                card.recurrence?.let { RecurrenceRow(it, today, zone, card.returnsAt, muted = card.paused) }
            }
            Spacer(Modifier.height(spacing.md))
            CardFooter(
                tags = card.tags,
                actions = card.actions,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // The card's one pressable thing, at the end of the row of read-only glyphs that
                // say what it will do. It still holds to fire and it still says the verb; what
                // it no longer does is take a column out of the line with the words in it.
                Spacer(Modifier.width(spacing.sm))
                HoldButton(
                    icon = if (card.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    label = stringResource(if (card.paused) R.string.card_resume else R.string.card_pause),
                    onHoldComplete = onTogglePause,
                    compact = true,
                )
            }
        }
    }
}

/**
 * The same reminder, one line tall.
 *
 * The words get the line and nothing else does — a reminder is its words, and a list is read by
 * them. Under them the one row that is left: what rings it and what it does, as small glyphs in
 * the muted ink, and the tags against the right edge with a mark when they do not all fit
 * ([FittingRow]).
 *
 * The glyphs are in the order the card says things in when it is open — what it is waiting for
 * first (a snooze outranks every rule under it), then the rules, then the recurrence, then what
 * happens when it fires — separated by a gap rather than a line, because a rule at this size
 * cannot say anything except which kind it is, and a divider would be claiming more.
 */
@Composable
private fun CompactCard(
    card: ReminderCardUi,
    onExpand: () -> Unit,
    onLongClick: () -> Unit,
    longClickLabel: String?,
    marked: Boolean,
    modifier: Modifier = Modifier,
) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    val rail = card.railTag?.let { if (card.paused) muted else tagColor(it) }
    RwilcoCard(
        onClick = onExpand,
        onLongClick = onLongClick,
        longClickLabel = longClickLabel,
        clickLabel = stringResource(R.string.card_expand),
        modifier = modifier,
        rail = rail,
        color = markedColour(marked),
    ) {
        Column(modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
            Text(
                text = card.text,
                style = MaterialTheme.typography.titleMedium,
                color = if (card.paused) muted else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(spacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    if (card.snoozedUntil != null || card.snoozedToPlace != null) {
                        CompactGlyph(Icons.Outlined.Snooze, stringResource(R.string.card_snoozed))
                    }
                    for (row in card.triggers) {
                        CompactGlyph(row.trigger.kind.icon, stringResource(row.trigger.kind.titleRes))
                    }
                    if (card.recurrence != null) {
                        CompactGlyph(Icons.Outlined.Autorenew, stringResource(R.string.card_recurrence))
                    }
                    // What it does when it fires, a step quieter than what rings it: the same
                    // glyphs the open card's footer wears, after a gap that says they are a
                    // different answer.
                    if (card.actions.isNotEmpty()) Spacer(Modifier.width(spacing.sm))
                    for (action in Action.entries) {
                        if (action in card.actions) {
                            CompactGlyph(action.icon, stringResource(action.labelRes))
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (card.tags.isNotEmpty()) {
                    Spacer(Modifier.width(spacing.sm))
                    FittingRow(
                        gap = spacing.xs,
                        more = {
                            val moreTags = stringResource(R.string.card_tags_more_description)
                            Text(
                                text = stringResource(R.string.card_tags_more),
                                style = MaterialTheme.typography.labelSmall,
                                color = muted,
                                modifier = Modifier.semantics { contentDescription = moreTags },
                            )
                        },
                    ) {
                        for (tag in card.tags) TagLabel(tag)
                    }
                }
            }
        }
    }
}

/**
 * The card's own colour, a step brighter while it is the one just saved.
 *
 * A step and not the amber: amber is what fires next, and "this is the one you were editing" is
 * not that. It fades rather than switching, because what the eye is being asked to catch is the
 * *change*, and a card that simply is a different colour says nothing about which of them moved.
 */
@Composable
internal fun markedColour(marked: Boolean): Color {
    val scheme = MaterialTheme.colorScheme
    val colour by animateColorAsState(
        targetValue = if (marked) scheme.surfaceContainerHighest else scheme.surfaceContainer,
        animationSpec = tween(Tokens.motion.medium),
        label = "cardMarked",
    )
    return colour
}

/** One mark on a compact card: read-only, muted, and the same size as an action glyph. */
@Composable
private fun CompactGlyph(icon: ImageVector, contentDescription: String) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(Tokens.sizes.glyph),
    )
}

/**
 * The recurrence as a row of its own, in the same language as the triggers above it.
 *
 * A reminder whose only arrangement is "cada 6 h" carries no trigger at all, so without this its
 * card said nothing about when it rings — the shape was real, armed and invisible. The second
 * line is the part people get wrong about it: the clock starts at the "hecho", not at the ring.
 */
@Composable
fun RecurrenceRow(
    recurrence: Recurrence,
    today: LocalDate,
    zone: ZoneId,
    /** When it comes back, while it is resting between two rounds; see [ReminderCardUi.returnsAt]. */
    returnsAt: Instant? = null,
    muted: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TriggerKeycap(
            family = TriggerFamily.TIME,
            icon = Icons.Outlined.Autorenew,
            contentDescription = stringResource(R.string.card_recurrence),
            size = Tokens.sizes.badge,
        )
        Spacer(Modifier.width(Tokens.spacing.sm))
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = recurrenceLabel(rememberWords(), recurrence, today),
                style = MaterialTheme.typography.titleSmall,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The second line is the half of the sentence the first one cannot say: "cada
            // semana" is the same words whether the week is counted from the calendar, from
            // the ringing or from you.
            //
            // **While it rests, that half is *when*.** Where the span counts from is a thing to
            // know once; which day it comes back on is what somebody has the card open to find
            // out, and the rules above cannot say it — they describe every Monday, including
            // the one just dealt with (0.74.0).
            val at = returnsAt?.atZone(zone)
            Text(
                text = if (at != null) {
                    stringResource(
                        R.string.card_recurrence_returns,
                        dayWord(rememberWords(), at.toLocalDate(), today) + " " + TimeText.time(at.toLocalTime(), rememberIs24h(), currentLocale()),
                    )
                } else stringResource(
                    when {
                        recurrence == Recurrence.ByTrigger || recurrence is Recurrence.MonthlyWeekday ||
                            recurrence is Recurrence.Calendar -> R.string.card_recurrence_from_calendar
                        recurrence.countsFromRinging -> R.string.card_recurrence_from_ringing
                        else -> R.string.card_recurrence_from_done
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The calendar's fences read like a rule's, because that is what they are.
            if (recurrence.conditions.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.editor_only_if_prefix,
                        recurrence.conditions.map { conditionLabel(it) }.joinToString(" · "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * "Debía sonar hace 3 h": the moment an overdue card missed, in the error ink and above
 * everything else on it. Ticks by the minute, like the hero's countdown at the same distance.
 */
@Composable
fun MissedRow(missedAt: Instant) {
    val now by rememberNow(60_000, LocalClock.current)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(Tokens.sizes.badge),
        )
        Spacer(Modifier.width(Tokens.spacing.sm))
        Text(
            text = stringResource(R.string.card_missed, countdownText(partsBetween(now, missedAt))),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * "Pospuesto · hoy", and the hour it comes back at — a row in the same language as the rules
 * above it, because that is what somebody is reading the card for.
 *
 * Only the plain cards carry it. The hero says the same thing in its own words, over a
 * countdown to the very moment, and saying it twice on one card is noise.
 */
@Composable
fun SnoozedRow(until: Instant, today: LocalDate, zone: ZoneId, muted: Boolean = false) {
    val locale = currentLocale()
    val is24h = rememberIs24h()
    val at = until.atZone(zone)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TriggerKeycap(
            family = TriggerFamily.TIME,
            icon = Icons.Outlined.Snooze,
            contentDescription = stringResource(R.string.card_snoozed),
            size = Tokens.sizes.badge,
        )
        Spacer(Modifier.width(Tokens.spacing.sm))
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = TimeText.time(at.toLocalTime(), is24h, locale),
                style = MonoStyles.label,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.card_snoozed) + " · " + dayWord(rememberWords(), at.toLocalDate(), today),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun TriggerRow(row: TriggerRowUi, today: LocalDate, defaultTime: LocalTime, muted: Boolean = false) {
    val line = triggerLine(row.trigger, today, defaultTime)
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The keycap says which kind of "when" this is; sighted by colour and glyph, spoken by
        // name — and wearing, in its corner, where this rule stands in its set.
        Box {
            TriggerKeycap(
                family = row.family,
                icon = row.trigger.kind.icon,
                contentDescription = stringResource(row.trigger.kind.titleRes),
                size = Tokens.sizes.badge,
            )
            row.standing?.let { standing ->
                StandingDot(
                    watched = row.watched,
                    standing = standing,
                    muted = muted,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = DOT_OUT, y = -DOT_OUT),
                )
            }
        }
        Spacer(Modifier.width(Tokens.spacing.sm))
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = line.primary,
                style = if (line.primaryMono) MonoStyles.label else MaterialTheme.typography.titleSmall,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // An empty second line would still cost its line height on every card.
            if (line.secondary.isNotEmpty()) {
                Text(
                    text = line.secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // **A circle finer than most of this phone's positions says so on the card**
            // (0.80.0). The editor says it while a radius is being dragged, which reaches the
            // next place somebody writes and none of the ones written months ago — and those
            // are the reminders that are quietly unreliable.
            //
            // "Unreliable", not "broken" (0.81.0). The number is the middle of the recent
            // looks, so this is a circle more than half of them cannot settle — not one none
            // of them can: the same fifty metres is entered off the ±15 m the street gives and
            // missed off the ±70 m of a wifi position indoors. The first wording said the
            // phone could not measure it, and the owner rightly answered that these reminders
            // have been working for months.
            row.doubtM?.let { doubt ->
                Text(
                    text = stringResource(R.string.card_place_under_doubt, doubt),
                    style = MaterialTheme.typography.bodySmall,
                    // Not the error colour: the app is still trying, the next good fix settles
                    // the same circle, and the system's own geofences are a second eye. This is
                    // a note about why one evening was quiet, in the ink the rule's own second
                    // line is written in — not a verdict on the reminder.
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.conditions.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.editor_only_if_prefix, row.conditions.map { conditionLabel(it) }.joinToString(" · ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Where a rule stands, worn in the corner of its own keycap.
 *
 * Small enough to be read as a property of the icon rather than as a thing of its own, so what
 * carries the meaning is fill: solid for a rule that is met, hollow for one that is not. The
 * ring of card colour around it is what keeps it from smudging into the keycap.
 *
 * The shape is the other question, and it is about the battery rather than the rule: a circle
 * the watch is not spending anything on wears a pause instead of a dot. Two of those — a rule
 * nobody has been able to check yet, and one nobody is checking — and they are the same fact
 * from either end. Two bars are legible at this size where a glyph is not, and being the odd
 * shape out is the point.
 *
 * The two questions stay apart, which is what makes a green pause mean something: a circle
 * whose gate is shut still knows where the phone is, because it is judged for nothing on the
 * positions the other circles pay for. Costing nothing and holding are not the same news.
 */
@Composable
private fun StandingDot(standing: RuleStanding, watched: Boolean, muted: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val label = if (watched) {
        stringResource(standing.labelRes)
    } else {
        stringResource(R.string.card_rule_dot_paused, stringResource(standing.labelRes))
    }
    val met = standing == RuleStanding.DONE || standing == RuleStanding.HOLDING
    val ink = when {
        met && !muted -> familyColor(TriggerFamily.PLACE, LocalDarkTheme.current)
        met -> scheme.onSurfaceVariant
        // onSurfaceVariant, not outline: on the dark scheme a hairline in the outline colour
        // over a dark keycap is a smudge, and the mark has to be readable to mean anything.
        else -> scheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .size(DOT + HALO * 2)
            .background(scheme.surfaceContainer, CircleShape)
            .padding(HALO),
        contentAlignment = Alignment.Center,
    ) {
        // Two bars for the two ways a circle costs nothing: nobody has looked yet, or nobody
        // is looking — and the ink says the answer anyway, because a circle whose gate is shut
        // still knows where the phone is (it rides along on everybody else's positions).
        if (standing == RuleStanding.UNKNOWN || !watched) {
            Canvas(
                modifier = Modifier
                    .size(DOT)
                    .semantics { contentDescription = label },
            ) {
                // Drawn rather than an icon: the Material pause is two hairlines inside a 24dp
                // box, and a third of that is a smudge. These bars are a third of the width
                // each, which is what makes them read at three millimetres.
                val bar = size.width * 0.32f
                val gap = size.width * 0.16f
                val left = (size.width - (bar * 2 + gap)) / 2f
                val top = size.height * 0.06f
                val tall = size.height * 0.88f
                val round = CornerRadius(bar * 0.4f, bar * 0.4f)
                drawRoundRect(ink, Offset(left, top), Size(bar, tall), round)
                drawRoundRect(ink, Offset(left + bar + gap, top), Size(bar, tall), round)
            }
        } else {
            Box(
                modifier = Modifier
                    .size(DOT)
                    // Card colour rather than nothing behind the hollow ones: over a coloured
                    // keycap a transparent middle shows the blue through it and the ring loses
                    // its edge, which on the dark scheme is most of what made these hard to read.
                    .background(if (met) ink else scheme.surfaceContainer, CircleShape)
                    .border(STROKE, ink, CircleShape)
                    .semantics { contentDescription = label },
            )
        }
    }
}

/**
 * The mark, its own line, the ring of card colour that separates it from the keycap, and how far
 * the whole thing sits outside the corner. Bigger and brighter than it started: at seven across
 * with a hairline it was there and not quite readable, which is the worst size for a mark whose
 * whole job is to be read at a glance.
 */
private val DOT = 9.dp
private val STROKE = 2.dp
private val HALO = 2.dp
private val DOT_OUT = 3.dp

/** What each mark means, said out loud for a screen reader. */
private val RuleStanding.labelRes: Int
    get() = when (this) {
        RuleStanding.DONE -> R.string.card_rule_happened
        RuleStanding.PENDING -> R.string.card_rule_pending
        RuleStanding.HOLDING -> R.string.card_rule_holding
        RuleStanding.NOT_HOLDING -> R.string.card_rule_not_holding
        RuleStanding.UNKNOWN -> R.string.card_rule_unknown
    }

/**
 * The same row for a reminder waiting at a place: "Al llegar a Casa" where the clock row has
 * the hour, in the place family's keycap, because that is what rings it next.
 */
@Composable
fun SnoozedPlaceRow(place: Trigger.Location, muted: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TriggerKeycap(
            family = TriggerFamily.PLACE,
            icon = Icons.Outlined.Snooze,
            contentDescription = stringResource(R.string.card_snoozed),
            size = Tokens.sizes.badge,
        )
        Spacer(Modifier.width(Tokens.spacing.sm))
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = stringResource(placePhrase(place.presence, place.onCrossing), place.label),
                style = MaterialTheme.typography.titleSmall,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.card_snoozed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
