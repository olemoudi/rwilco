package dev.rwilco.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.ui.theme.LocalDarkTheme
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.familyColor
import dev.rwilco.model.TriggerFamily
import dev.rwilco.vault.VaultCenter
import dev.rwilco.vault.VaultWorker
import dev.rwilco.vault.pendingChanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/**
 * How much is waiting to be copied, in the corner of the screen — and the way to say "now".
 *
 * It is there only while it has something to say: with the backup off, or with everything
 * already copied, there is no disc at all. A tap makes the copy on the spot: the number becomes
 * a turning ring, and a tick when it lands, and then it goes, which is the whole conversation.
 *
 * Red, and the only red on Home. Amber means "what fires next" and belongs to the reminders;
 * this is the one thing on the screen that is about the app rather than about them.
 */
@Composable
fun BackupBadge(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as RwilcoApplication
    val activity by VaultCenter.activity.collectAsStateWithLifecycle()
    val haptics = Tokens.haptics

    // Off the main thread: this hashes every reminder to decide, which is cheap but not free,
    // and it runs again on every edit.
    val pending by produceState(initialValue = 0, app) {
        combine(app.vaultStore.state, app.repository.rows, app.settingsStore.raw) { state, rows, raw ->
            Triple(state, rows, raw.orEmpty())
        }.collect { (state, rows, raw) ->
            value = withContext(Dispatchers.Default) { pendingChanges(rows, raw, state) }
        }
    }

    // A copy that lands leaves a tick behind for a moment, so a tap has an answer.
    var justDone by remember { mutableStateOf(false) }
    LaunchedEffect(activity.copies) {
        if (activity.copies > 0) {
            justDone = true
            delay(TICK_MILLIS)
            justDone = false
        }
    }

    val shown = activity.working || justDone || pending > 0
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(Tokens.motion.fast)) + scaleIn(tween(Tokens.motion.medium), initialScale = 0.6f),
        exit = fadeOut(tween(Tokens.motion.fast)) + scaleOut(tween(Tokens.motion.fast), targetScale = 0.6f),
        modifier = modifier,
    ) {
        val scheme = MaterialTheme.colorScheme
        val done = familyColor(TriggerFamily.PLACE, LocalDarkTheme.current)
        val label = when {
            justDone -> stringResource(R.string.home_backup_done)
            activity.working -> stringResource(R.string.home_backup_working)
            else -> pluralStringResource(R.plurals.home_backup_pending, pending, pending)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(Tokens.sizes.touch)
                .clickable(enabled = !activity.working && !justDone) {
                    haptics.perform(HapticFeedbackType.ContextClick)
                    VaultWorker.runNow(context)
                }
                .semantics { contentDescription = label },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(DISC)
                    .background(if (justDone) done else scheme.error, CircleShape),
            ) {
                when {
                    justDone -> Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = scheme.surface,
                        modifier = Modifier.size(GLYPH),
                    )
                    // The one turning thing on Home, and only while a copy is going up.
                    activity.working -> CircularProgressIndicator(
                        color = scheme.onError,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(GLYPH),
                    )
                    else -> Text(
                        text = if (pending > MOST) "$MOST+" else "$pending",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onError,
                    )
                }
            }
        }
    }
}

private val DISC = 20.dp
private val GLYPH = 12.dp

/** Past this the number stops being a number and becomes "a lot". */
private const val MOST = 99
private const val TICK_MILLIS = 1_400L
