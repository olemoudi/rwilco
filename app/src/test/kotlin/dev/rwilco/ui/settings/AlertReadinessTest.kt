package dev.rwilco.ui.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The count behind the closed row. It is the only thing a folded Alerts group says about
 * itself, so a miscount is a phone that quietly cannot ring and a screen that says it can.
 */
class AlertReadinessTest {

    @Test
    fun `a phone with everything granted has nothing to fix`() {
        val readiness = AlertReadiness()
        assertEquals(0, readiness.problems)
        assertTrue(readiness.allGood)
    }

    @Test
    fun `every one of the ten counts once`() {
        val nothing = AlertReadiness(
            notifications = false,
            channels = false,
            fullScreen = false,
            exactAlarms = false,
            alarmVolume = false,
            throughDnd = false,
            unrestricted = false,
            battery = false,
            overlay = false,
            usageAccess = false,
        )
        assertEquals(10, nothing.problems)
        assertFalse(nothing.allGood)
    }

    @Test
    fun `one thing in the way is one thing to fix`() {
        // The volume at zero is the quietest of the ten failures and the easiest to miss:
        // everything is granted, and no reminder is ever heard.
        val quiet = AlertReadiness(alarmVolume = false)
        assertEquals(1, quiet.problems)
        assertFalse(quiet.allGood)
    }

    @Test
    fun `the count is of what is missing, not of what is granted`() {
        assertEquals(2, AlertReadiness(notifications = false, battery = false).problems)
    }

    @Test
    fun `the strip on Home shows for a problem nobody has waved off, and again for a new one`() {
        val muted = AlertReadiness(channels = false, read = true)
        assertEquals(setOf("channels"), muted.problemNames())
        assertTrue(stripShows(muted, dismissed = emptySet()))
        assertFalse(stripShows(muted, dismissed = setOf("channels")))
        val worse = AlertReadiness(channels = false, exactAlarms = false, read = true)
        assertTrue(stripShows(worse, dismissed = setOf("channels")))
        assertFalse(stripShows(AlertReadiness(read = true), dismissed = emptySet()))
    }

    @Test
    fun `nothing is said, and nothing forgotten, before anything has been looked at`() {
        // The default is a guess (everything granted) so a fresh screen never flashes red. Acting
        // on it threw away the "ahora no" that had just been given, every time Home recomposed.
        val guess = AlertReadiness()
        assertFalse(stripShows(guess, dismissed = emptySet()))
        assertEquals(setOf("channels"), liveDismissals(guess, dismissed = setOf("channels")))
    }

    @Test
    fun `a problem that has been fixed stops being remembered, so it is news if it comes back`() {
        val both = AlertReadiness(channels = false, battery = false, read = true)
        assertEquals(setOf("channels", "battery"), liveDismissals(both, setOf("channels", "battery")))
        // The channel is un-muted; the battery is still in the way, so "all good" never arrives.
        val fixed = AlertReadiness(battery = false, read = true)
        assertEquals(setOf("battery"), liveDismissals(fixed, setOf("channels", "battery")))
        // And when it is muted again, the strip says so instead of staying quiet for ever.
        assertTrue(stripShows(both, dismissed = liveDismissals(fixed, setOf("channels", "battery"))))
    }

    @Test
    fun `the do-not-disturb grant is an offer, not one of the ten`() {
        val noPolicy = AlertReadiness(policyAccess = false, read = true)
        assertEquals(0, noPolicy.problems)
        assertTrue(noPolicy.allGood)
    }
}
