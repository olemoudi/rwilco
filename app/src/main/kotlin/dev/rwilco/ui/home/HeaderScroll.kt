package dev.rwilco.ui.home

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import dev.rwilco.ui.theme.Tokens

/**
 * Where the top row sits after the list has scrolled by [consumed]: 0 is fully shown, minus
 * [height] fully hidden, and anything between is a drag in progress.
 *
 * A scroll *down* the list arrives as a negative delta, which is what takes the row up and out.
 */
fun headerOffsetAfter(offset: Float, consumed: Float, height: Float): Float =
    (offset + consumed).coerceIn(-height, 0f)

/** A row let go of half way goes to whichever edge is nearer: half a wordmark is not a header. */
fun headerSettleTarget(offset: Float, height: Float): Float =
    if (offset <= -height / 2f) -height else 0f

/**
 * The top row's own scroll: out of the way going down, back at the first sign of going up.
 *
 * That row carries the way into Settings, and it used to be the list's first item — so reading
 * three screens down and wanting Settings meant scrolling all the way back to the top to reach
 * a button that had not gone anywhere. It is the screen's own row now: it leaves as the list
 * goes down, comes back as the list goes up, and stays wherever in the list that happens.
 *
 * **It moves by what the list actually scrolled, and consumes nothing.** Two things fall out of
 * that, both of them the point. A list too short to scroll — two reminders on a fresh phone —
 * scrolls nothing, so the row cannot be dragged off a screen it is not covering; and while the
 * row is leaving it travels exactly as far as the cards under it, so its bottom edge stays on
 * the first card the whole way down and no gap can open between them. A Material top bar does
 * this by eating the scroll and shrinking its own height, which re-measures the screen on every
 * frame of a drag; the list's top padding here never changes at all.
 */
@Stable
class HeaderScroll internal constructor(private val settleSpec: AnimationSpec<Float>) {

    /** How tall the row is; nothing can move until it has been measured. */
    var heightPx: Float by mutableFloatStateOf(0f)
        private set

    /** How far up the row is drawn: 0 shown, -[heightPx] hidden. */
    var offsetPx: Float by mutableFloatStateOf(0f)
        private set

    /** While true the row does not move at all: searching, where the field is what a thumb is aiming at. */
    internal var pinned: Boolean = false

    internal fun measured(height: Float) {
        if (height == heightPx) return
        heightPx = height
        offsetPx = offsetPx.coerceIn(-height, 0f)
    }

    /** Back where it belongs, whatever the list is doing. */
    suspend fun show() = animateTo(0f)

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // What the list ate, and only that: see the class doc. Nothing is claimed back —
            // the row moving is free.
            if (!pinned) offsetPx = headerOffsetAfter(offsetPx, consumed.y, heightPx)
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            // The gesture is over (this runs at the end of a fling, and of a drag that flung
            // nothing): a row stopped half way finishes the journey it was on.
            if (!pinned) animateTo(headerSettleTarget(offsetPx, heightPx))
            return Velocity.Zero
        }
    }

    private suspend fun animateTo(target: Float) {
        if (target == offsetPx) return
        animate(initialValue = offsetPx, targetValue = target, animationSpec = settleSpec) { value, _ -> offsetPx = value }
    }
}

/** [HeaderScroll] for this screen, kept across recompositions. [pinned] holds the row still. */
@Composable
fun rememberHeaderScroll(pinned: Boolean = false): HeaderScroll {
    val motion = Tokens.motion
    val scroll = remember(motion) { HeaderScroll(tween(motion.fast, easing = motion.emphasized)) }
    SideEffect { scroll.pinned = pinned }
    // Pinning is also a promise that it is there to be pinned: the search field opening while
    // the row was half way out would otherwise be a field somebody has to scroll to type in.
    LaunchedEffect(pinned) { if (pinned) scroll.show() }
    return scroll
}
