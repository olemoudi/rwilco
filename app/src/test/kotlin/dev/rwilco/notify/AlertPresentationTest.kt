package dev.rwilco.notify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The rule the owner asked for: an app open in front of you gets a banner, everything else gets
 * the screen. The interesting cases are the two ways of not knowing.
 */
class AlertPresentationTest {

    private fun decide(
        fullScreenWanted: Boolean = true,
        inUse: Boolean = true,
        foreground: ForegroundApp = ForegroundApp.NONE,
        canOverlay: Boolean = true,
        canFullScreen: Boolean = true,
        repeat: Boolean = false,
    ) = alertPresentation(fullScreenWanted, inUse, foreground, canOverlay, canFullScreen, repeat)

    @Test
    fun `an app open in front of somebody is not interrupted`() {
        assertEquals(AlertPresentation.BANNER, decide(foreground = ForegroundApp.OTHER))
    }

    @Test
    fun `the home screen, our own app and a phone in a pocket get the whole screen`() {
        assertEquals(AlertPresentation.FULL_SCREEN, decide(foreground = ForegroundApp.NONE))
        assertEquals(AlertPresentation.FULL_SCREEN, decide(foreground = ForegroundApp.OURS))
        // Screen off or locked: the system's full-screen intent, and nothing else is consulted.
        assertEquals(AlertPresentation.FULL_SCREEN, decide(inUse = false, foreground = ForegroundApp.OTHER))
        assertEquals(AlertPresentation.FULL_SCREEN, decide(inUse = false, canOverlay = false, foreground = ForegroundApp.UNKNOWN))
    }

    @Test
    fun `not being allowed to look falls back to the banner`() {
        assertEquals(AlertPresentation.BANNER, decide(foreground = ForegroundApp.UNKNOWN))
    }

    @Test
    fun `not being allowed to show falls back to the banner`() {
        assertEquals(AlertPresentation.BANNER, decide(canOverlay = false))
    }

    @Test
    fun `a screen the system will not give becomes a banner that makes its own noise`() {
        // Locked or dark: only the system's full-screen intent can light the screen, and when
        // Android 14+ refuses it the notification has to carry the sound — deciding FULL_SCREEN
        // here muted it for a screen that never came.
        assertEquals(AlertPresentation.BANNER, decide(inUse = false, canFullScreen = false))
        // With the screen on the app starts the alert itself, and the grant does not enter into it.
        assertEquals(AlertPresentation.FULL_SCREEN, decide(inUse = true, canFullScreen = false))
    }

    @Test
    fun `a reminder that never asked for the screen never takes it`() {
        assertEquals(AlertPresentation.BANNER, decide(fullScreenWanted = false, inUse = false))
    }

    @Test
    fun `a repeat takes a locked phone's screen again, and leaves a phone in use alone`() {
        // Reported from the phone: a timer's repeat came back as a card on the lock screen, the
        // tap asked for the PIN, and the noise went on. Locked or dark, it is the alert screen,
        // whose "Silenciar" needs no unlocking.
        assertEquals(AlertPresentation.FULL_SCREEN, decide(inUse = false, repeat = true))
        // In somebody's hand it is the banner it always was, wherever they are: once was the alarm.
        assertEquals(AlertPresentation.BANNER, decide(inUse = true, foreground = ForegroundApp.NONE, repeat = true))
        assertEquals(AlertPresentation.BANNER, decide(inUse = true, foreground = ForegroundApp.OURS, repeat = true))
        // And the system's refusal still wins: a screen that will not come is a banner that rings.
        assertEquals(AlertPresentation.BANNER, decide(inUse = false, canFullScreen = false, repeat = true))
        // A reminder that never asked for the screen does not get it on a repeat either.
        assertEquals(AlertPresentation.BANNER, decide(fullScreenWanted = false, inUse = false, repeat = true))
    }
}
