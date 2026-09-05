package dev.rwilco.ui.settings

import dev.rwilco.R
import dev.rwilco.update.UpdateInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * What the update-channel card says about where this phone stands.
 *
 * Written after it said nothing at all (0.89.0). The card painted one row of this table — being
 * ahead of the beta channel — so following a channel with nothing published on it, which is what
 * alpha was on the day the channels shipped, moved the segmented control and changed nothing
 * else on the screen. A dead control and a working one look identical from the outside, and the
 * only way to see an omission is to write the whole table down.
 */
class ChannelStandingTest {

    private val installed = 139

    private fun standing(offer: UpdateInfo?, reading: Boolean = false) =
        channelStanding(reading, offer, installed)

    @Test
    fun `nothing is said while the manifest is still being read`() {
        // A line that flickers "could not read this channel" on every visit is worse than a beat.
        assertNull(standing(offer = null, reading = true))
        assertNull(standing(offer = UpdateInfo(140, "0.89.0-alpha"), reading = true))
    }

    @Test
    fun `a channel that could not be read and one with nothing on it say different things`() {
        assertEquals(R.string.settings_channel_unreachable, standing(offer = null)?.textRes)
        assertEquals(R.string.settings_channel_empty, standing(UpdateInfo(0, ""))?.textRes)
    }

    @Test
    fun `a newer build on the channel names itself`() {
        val coming = standing(UpdateInfo(140, "0.89.0-alpha"))
        assertEquals(R.string.settings_channel_coming, coming?.textRes)
        assertEquals("0.89.0-alpha", coming?.argument)
        assertEquals(false, coming?.alarming)
    }

    @Test
    fun `being level with the channel is said quietly, being ahead of it is not`() {
        val current = standing(UpdateInfo(installed, "0.88.0-beta"))
        assertEquals(R.string.settings_channel_current, current?.textRes)
        assertEquals(false, current?.alarming, "being up to date is not a warning")

        val stranded = standing(UpdateInfo(installed - 1, "0.87.0-beta"))
        assertEquals(R.string.settings_channel_waiting, stranded?.textRes)
        assertEquals("0.87.0-beta", stranded?.argument)
        assertEquals(true, stranded?.alarming, "the one thing the choice cannot make true on its own")
    }

    @Test
    fun `every reading of the manifest produces a line`() {
        // The bug was an omission, so this is the assertion that would have caught it: once the
        // read is done, there is no shape of manifest that leaves the card silent.
        for (offer in listOf(null, UpdateInfo(0, ""), UpdateInfo(1, "0.1.0-beta"), UpdateInfo(installed, "x"), UpdateInfo(999, "y"))) {
            assertEquals(true, standing(offer) != null, "silent for $offer")
        }
    }
}
