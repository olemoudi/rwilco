package dev.rwilco.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performSemanticsAction

/**
 * Home's way into the form since 0.71.0: the pencil on the card whose words are [words]. The tap
 * on a card folds it and unfolds it now, so a test that wants the editor has to ask for it the
 * way a thumb does.
 *
 * Found by the reminder's own words, which the pencil carries in its description because a list
 * of thirty cards has thirty pencils and neither a screen reader nor a test can tell them apart
 * otherwise. Only the words and never the verb around them: the verb comes from a context, and
 * this suite changes the app's language between classes.
 *
 * Pressed through its click action rather than by a touch at its centre, because a card scrolled
 * only just into view sits under the floating buttons — `performScrollToNode` stops the moment a
 * node is inside the viewport, and the viewport goes on under them. A touch there lands on "fold
 * the list away", which folds every card, persists, and takes the pencils with it for the rest
 * of the run. What these tests are about is what the editor does with the scroll, not whether a
 * thumb can reach the corner of a half-scrolled card.
 */
fun ComposeTestRule.editCard(words: String) {
    onNode(hasContentDescription(words, substring = true) and hasClickAction())
        .performSemanticsAction(SemanticsActions.OnClick)
}
