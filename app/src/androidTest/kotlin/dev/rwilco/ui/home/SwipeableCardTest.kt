package dev.rwilco.ui.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rwilco.ui.theme.RwilcoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The guard on the swipes. A card used to act the instant a thumb crossed a line, which is a
 * card that gets dealt with while somebody is scrolling; now it has to be held open. Only a
 * device runs a real gesture against a real animation, and only a hand-driven clock can say
 * "let go after a fifth of a second" and mean it.
 */
@RunWith(AndroidJUnit4::class)
class SwipeableCardTest {

    @get:Rule
    val rule = createComposeRule()

    private var done = 0
    private var deleted = 0

    private fun setUp() {
        rule.setContent {
            RwilcoTheme(haptics = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SwipeableCard(onDone = { done++ }, onDelete = { deleted++ }) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(96.dp),
                        ) { Text(CARD) }
                    }
                }
            }
        }
        rule.mainClock.autoAdvance = false
    }

    private fun card() = rule.onNodeWithText(CARD)

    private fun openRight() = card().performTouchInput {
        down(centerLeft)
        moveTo(centerRight)
    }

    private fun openLeft() = card().performTouchInput {
        down(centerRight)
        moveTo(centerLeft)
    }

    @Test
    fun openedAndLetGoTooSoonNothingHappens() {
        setUp()
        openRight()
        rule.mainClock.advanceTimeBy(300)
        card().performTouchInput { up() }
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals("a card let go at 0.3s was marked done", 0, done)
        assertEquals(0, deleted)
    }

    @Test
    fun heldOpenLongEnoughItActsOnceAndOnlyOnce() {
        setUp()
        openRight()
        rule.mainClock.advanceTimeBy(600)
        assertEquals(1, done)
        // Still held a second later: the same opening does not act twice.
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(1, done)
        card().performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500)
        assertEquals(1, done)
        assertEquals(0, deleted)
    }

    @Test
    fun theOtherWayDeletes() {
        setUp()
        openLeft()
        rule.mainClock.advanceTimeBy(600)
        assertEquals(1, deleted)
        assertEquals(0, done)
        card().performTouchInput { up() }
    }

    @Test
    fun brushesPastDoNotAddUp() {
        setUp()
        repeat(3) {
            openRight()
            rule.mainClock.advanceTimeBy(200)
            card().performTouchInput { up() }
            rule.mainClock.advanceTimeBy(400)
        }
        assertEquals("three brushes dealt with the card", 0, done)
    }

    private companion object {
        const val CARD = "Coger el paraguas"
    }
}
