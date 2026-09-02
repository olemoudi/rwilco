package dev.rwilco.notify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which channels go when the tone or rhythm changes: every alert channel that is not one of the
 * four just made, and nothing else — the missed and net channels are not alert channels and
 * stay, whatever the prefix rule says about the rest.
 */
class AlertChannelsTest {

    private val live = setOf("alert_v2_s0_v0", "alert_v2_s0_v1_s", "alert_v2_s1_v0_system", "alert_v2_s1_v1_system_s")

    @Test
    fun `an alert channel of a tone nobody rings any more is stale`() {
        val existing = listOf("alert_v2_s1_v0_chime", "alert_v2_s0_v0", "missed_v2", "net_v2", "alert_v2_s1_v1_system_s_dnd")
        assertEquals(listOf("alert_v2_s1_v0_chime", "alert_v2_s1_v1_system_s_dnd"), staleAlertChannels(existing, live))
    }

    @Test
    fun `the four live ones and the two quiet channels are never stale`() {
        assertEquals(emptyList<String>(), staleAlertChannels(live.toList() + listOf("missed_v2", "net_v2"), live))
    }
}
