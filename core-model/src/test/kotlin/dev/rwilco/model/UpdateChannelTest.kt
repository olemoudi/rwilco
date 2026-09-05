package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The channel rules, with none of the network.
 *
 * Every one of these is otherwise an implicit assumption spread between the updater, the
 * settings screen and a CI script, which is how a distribution model acquires two answers to
 * the same question.
 */
class UpdateChannelTest {

    @Test
    fun `beta is the default a phone lands on without choosing`() {
        assertEquals(UpdateChannel.BETA, AppSettings().updateChannel, "an install that predates the channels")
    }

    @Test
    fun `a build belongs to the channel whose suffix it carries, and to no other`() {
        assertTrue(belongsToChannel("0.88.0-beta", UpdateChannel.BETA))
        assertTrue(belongsToChannel("0.88.0-alpha", UpdateChannel.ALPHA))
        assertFalse(belongsToChannel("0.88.0-alpha", UpdateChannel.BETA))
        assertFalse(belongsToChannel("0.88.0-beta", UpdateChannel.ALPHA))
    }

    @Test
    fun `a version name from before the channels is refused by both rather than accepted by both`() {
        // The alternative reading — "no suffix, so it fits anywhere" — is the one that lets a
        // hand-edited manifest move a phone silently, which is what channels exist to prevent.
        assertFalse(belongsToChannel("0.86.0", UpdateChannel.BETA))
        assertFalse(belongsToChannel("0.86.0", UpdateChannel.ALPHA))
        assertFalse(belongsToChannel("", UpdateChannel.BETA))
    }

    @Test
    fun `a suffix must be the end of the name and not merely in it`() {
        assertFalse(belongsToChannel("0.88.0-beta.1", UpdateChannel.BETA))
        assertFalse(belongsToChannel("-alpha-0.88.0", UpdateChannel.ALPHA))
    }

    @Test
    fun `switching says nothing until a manifest has been read`() {
        assertEquals(ChannelSwitch.Unknown, channelSwitch(139, 0, ""))
        assertEquals(ChannelSwitch.Unknown, channelSwitch(139, -1, "0.88.0-beta"))
    }

    @Test
    fun `ahead of the channel is a wait, level with it is not`() {
        assertEquals(ChannelSwitch.Immediate("0.89.0-beta"), channelSwitch(139, 140, "0.89.0-beta"))
        assertEquals(ChannelSwitch.AlreadyOnIt, channelSwitch(139, 139, "0.88.0-beta"))
        // Being current is not a wait: lumping the two together tells every up-to-date phone
        // on beta — which is nearly all of them — that it is stranded ahead of its own channel.
        assertEquals(ChannelSwitch.WaitsForNextRelease("0.88.0-beta"), channelSwitch(140, 139, "0.88.0-beta"))
    }
}
