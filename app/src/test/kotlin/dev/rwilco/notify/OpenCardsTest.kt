package dev.rwilco.notify

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which cards in the shade mean "an answer is owed"; see `Waiting.kt` and Home's top card. */
class OpenCardsTest {

    private val id = "r1"

    @Test
    fun `the ring, the net's word and a routine's question all count`() {
        assertTrue(AlertNotifications.cardOpen(setOf(AlertNotifications.notificationId(id)), id))
        assertTrue(AlertNotifications.cardOpen(setOf(AlertNotifications.nudgeNotificationId(id)), id))
        assertTrue(AlertNotifications.cardOpen(setOf(AlertNotifications.askNotificationId(id)), id))
    }

    @Test
    fun `the done and reset notices do not`() {
        // They are about something already done and go by themselves after their minute. A row
        // on Home saying an answer is owed for a thing just ticked off is the opposite of what
        // happened — and "deshacer" is on the card itself, where it belongs.
        assertFalse(AlertNotifications.cardOpen(setOf(AlertNotifications.resetNotificationId(id)), id))
    }

    @Test
    fun `another reminder's cards are not this one's`() {
        assertFalse(AlertNotifications.cardOpen(setOf(AlertNotifications.notificationId("other")), id))
        assertFalse(AlertNotifications.cardOpen(emptySet(), id))
    }
}
