package dev.rwilco.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import java.time.Instant

/**
 * **What is waiting for an answer**, at the top of Home and above everything else on it.
 *
 * Every one of these was already on Home somewhere — in "vencidos", in an overdue routine's
 * row, in a contact's — saying *when it should have rung* and opening the form when tapped.
 * What none of them did was lead to the one screen that can answer it. This does: one card,
 * one row per thing waiting, and the tap is the alert.
 *
 * **One card and not a card each**, which the app has learnt twice now: a stack of red cards
 * over the hero is the same header repeated down the screen ([MoreOverdueRoutinesRow] says so
 * about the routines). So the header is said once and the rows underneath are the things
 * themselves. They are not capped: each is one tap from gone, and a list of answers owed with
 * the last three left off is the very complaint this card exists to answer.
 *
 * The error wash, which is Home's ink for "this got away from you" — never amber, which means
 * what fires *next*, and this is the opposite: what fired and is not finished.
 */
@Composable
fun WaitingCard(waiting: List<WaitingUi>, now: Instant, onAnswer: (String) -> Unit, modifier: Modifier = Modifier) {
    if (waiting.isEmpty()) return
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    val ink = scheme.onErrorContainer
    val header = stringResource(R.string.home_waiting_title)
    val opens = stringResource(R.string.home_waiting_open)
    val haptics = Tokens.haptics
    RwilcoCard(color = scheme.errorContainer, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = spacing.sm)) {
            // No glyph up here: every row under it carries one, and a bell over a bell was the
            // same word said twice at two sizes.
            Text(
                text = header,
                style = MaterialTheme.typography.labelMedium,
                color = ink,
                modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.xs),
            )
            for (item in waiting) {
                // How long it has been: the countdown says which side of now it is on, so the
                // row never says "hace" twice.
                val ago = countdownText(partsBetween(now, item.since))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = opens) { haptics.perform(HapticFeedbackType.Confirm); onAnswer(item.id) }
                        .heightIn(min = Tokens.sizes.touch)
                        .padding(horizontal = spacing.lg, vertical = spacing.sm)
                        .semantics { contentDescription = header + ": " + item.text + ". " + ago + ". " + opens },
                ) {
                    // A routine's own glyph, the one it wears everywhere else — the question it
                    // is asking is not the same thing as a reminder that rang.
                    Icon(
                        if (item.routine) Icons.Outlined.Autorenew else Icons.Outlined.NotificationsActive,
                        contentDescription = null,
                        tint = ink,
                        modifier = Modifier.size(Tokens.sizes.glyph),
                    )
                    Spacer(Modifier.width(spacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = item.text,
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
    }
}
