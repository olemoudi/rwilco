package dev.rwilco.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rwilco.R
import dev.rwilco.model.TriggerFamily
import dev.rwilco.ui.theme.LocalDarkTheme
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.familyColor
import dev.rwilco.update.UpdateCenter
import dev.rwilco.update.UpdateInfo
import dev.rwilco.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The update already downloaded and waiting to be installed, or null.
 *
 * Asked of the file itself, which is the only record there is (see [Updater]): again whenever the
 * update status moves, and whenever the screen comes back — an install accepted or declined in
 * the system's own dialog is only seen on the way back. Off the main thread, because it parses a
 * big archive; with no file there, which is nearly always, it answers at once.
 */
@Composable
fun rememberStagedUpdate(): UpdateInfo? {
    val context = LocalContext.current
    val updateState by UpdateCenter.state.collectAsStateWithLifecycle()
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumeTick++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var staged by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(updateState, resumeTick) {
        staged = withContext(Dispatchers.IO) { Updater(context).let { it.stagedUpdate(it.channel()) } }
    }
    return staged
}

/**
 * A small green arrow on the corner of an icon: a new version is downloaded and waiting.
 *
 * Laid in a [Box] over the icon it marks, aligned to its top-end corner, it sits a third of itself
 * past that corner the way a badge does, cut out of the icon by a ring of [ground] — the colour of
 * whatever is behind it — so the arrow does not run into the glyph underneath. It is on the cog on
 * Home and on the Updates row in Settings, folded or not, so a waiting update is not left unseen
 * until somebody happens to open Settings.
 *
 * Green, the green of the backup's tick beside it on Home: the one colour Home spends on "the app
 * has something ready for you". Never amber, which is what fires next.
 */
@Composable
fun UpdateReadyBadge(visible: Boolean, ground: Color, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(Tokens.motion.fast)) + scaleIn(tween(Tokens.motion.medium), initialScale = 0.6f),
        exit = fadeOut(tween(Tokens.motion.fast)) + scaleOut(tween(Tokens.motion.fast), targetScale = 0.6f),
        modifier = modifier.offset(x = DISC / 3, y = -DISC / 3),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(DISC)
                // Drawn over the edge of the green, which is what turns the ring into a cut-out.
                .border(RING, ground, CircleShape)
                .background(familyColor(TriggerFamily.PLACE, LocalDarkTheme.current), CircleShape),
        ) {
            Icon(
                imageVector = Icons.Outlined.ArrowDownward,
                contentDescription = stringResource(R.string.update_ready_badge),
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(GLYPH),
            )
        }
    }
}

private val DISC = 18.dp
private val GLYPH = 12.dp
private val RING = 2.dp
