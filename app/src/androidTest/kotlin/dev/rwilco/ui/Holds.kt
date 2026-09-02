package dev.rwilco.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import dev.rwilco.ui.components.GUARD_TICK_TAG

/**
 * The alert's answers are holds, not taps (0.66.0, [dev.rwilco.ui.components.PressGuard]):
 * wait for the screen to arm, keep a finger on the button until the tick is up, and only then
 * lift it — the answer is given on release, and [whileConfirmed] is the moment before that.
 *
 * The button is the node with the words *and* a click action: while a hold is on, the
 * indicator up top says the same words, without one. Until the screen has armed the button
 * has no click action either, so waiting for one is waiting for the countdown.
 */
fun ComposeTestRule.holdToAnswer(label: String, whileConfirmed: () -> Unit = {}) {
    waitUntilArmed(label)
    val button = hasText(label) and hasClickAction()
    onNode(button).performTouchInput { down(center) }
    waitUntil(timeoutMillis = 10_000) {
        onAllNodesWithTag(GUARD_TICK_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    whileConfirmed()
    onNode(button).performTouchInput { up() }
}

/** Until the countdown has run and [label] answers: the button takes a click action only then. */
fun ComposeTestRule.waitUntilArmed(label: String) {
    waitUntil(timeoutMillis = 10_000) {
        onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
    }
}
