package dev.rwilco.model

import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The safety net says its word out loud now — the ordinary tone at half an alarm — and the
 * price of that licence is that it keeps to the hours somebody is up.
 *
 * The net was mute, and being mute is what let it speak at any hour without anybody having to
 * think about it. A noise puts the question back, and this is the answer: heard while you are
 * there to hear it, and as mute as it ever was while you are not.
 */
class NetSoundTest {

    private val shape = DayShape.DEFAULT

    @Test
    fun `it speaks while somebody is up, and not at three in the morning`() {
        // Tuesday: up at eight, in bed at half eleven.
        assertTrue(netSpeaksAloud(local(2026, 8, 25, 10, 0), zone, shape))
        assertTrue(netSpeaksAloud(local(2026, 8, 25, 23, 0), zone, shape))
        assertFalse(netSpeaksAloud(local(2026, 8, 25, 23, 45), zone, shape), "past bedtime")
        assertFalse(netSpeaksAloud(local(2026, 8, 26, 3, 0), zone, shape), "the middle of the night")
        assertFalse(netSpeaksAloud(local(2026, 8, 26, 7, 30), zone, shape), "half an hour before the alarm")
        // The two ends themselves: getting up counts, bedtime does not.
        assertTrue(netSpeaksAloud(local(2026, 8, 25, 8, 0), zone, shape))
        assertFalse(netSpeaksAloud(local(2026, 8, 25, 23, 30), zone, shape))
    }

    @Test
    fun `a bedtime past midnight belongs to the night before`() {
        // A Friday is a working day that ends at the weekend's bedtime: up at eight, to bed at
        // half one. So one in the morning on Saturday is still Friday, and somebody is up.
        // Asked of Saturday's own calendar day alone this would be "asleep", which is the whole
        // reason two days are looked at.
        assertTrue(netSpeaksAloud(local(2026, 8, 29, 1, 0), zone, shape), "Saturday at one is Friday night")
        assertFalse(netSpeaksAloud(local(2026, 8, 29, 2, 0), zone, shape), "half past one is bedtime")
        // And Saturday's own lie-in: not up at nine, up at ten.
        assertFalse(netSpeaksAloud(local(2026, 8, 29, 9, 0), zone, shape))
        assertTrue(netSpeaksAloud(local(2026, 8, 29, 10, 0), zone, shape))
    }

    @Test
    fun `somebody who is up at odd hours is asked about their own hours`() {
        // The shape is the person's, not a clock the app owns: a night worker in bed at nine in
        // the morning gets a word at three and none at noon.
        val nights = DayShape(hours = AwakeHours(wake = java.time.LocalTime.of(21, 0), sleep = java.time.LocalTime.of(9, 0)))
        assertTrue(netSpeaksAloud(local(2026, 8, 26, 3, 0), zone, nights))
        assertFalse(netSpeaksAloud(local(2026, 8, 26, 12, 0), zone, nights))
    }

    @Test
    fun `half is half of the alarm, in the unit a volume control is`() {
        assertEquals(0.5f, NET_GAIN)
    }

    @Test
    fun `the tone it borrows is the ordinary one, never the insistent`() {
        // "Hasta que reciba caso" is a promise about an alarm somebody has to answer, and the
        // net is the opposite of that: it is a word about something already let go.
        val settings = AppSettings(
            alertSound = AlertSound.Bundled(Chime.SOFT),
            insistentSound = AlertSound.Bundled(Chime.ALERT),
        )
        assertEquals(AlertSound.Bundled(Chime.SOFT), settings.soundFor(insistent = false))
    }
}
