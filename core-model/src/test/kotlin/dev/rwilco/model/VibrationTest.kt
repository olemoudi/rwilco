package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The waveform a reminder buzzes. What is actually being pinned here is the motor: every
 * pattern this app can build has to end on its own, inside a minute, whatever happens to the
 * app that started it.
 */
class VibrationTest {

    private val everyPattern = VibrationStrength.entries.flatMap { strength ->
        VibrationRhythm.entries.map { rhythm -> VibrationPattern(strength, rhythm) }
    }

    @Test
    fun `nothing this app can build outlasts a minute`() {
        for (pattern in everyPattern) {
            val waveform = waveformFor(pattern)
            assertTrue(
                waveform.totalMillis <= VibrationLimits.LONGEST.toMillis(),
                "$pattern runs for ${waveform.totalMillis} ms",
            )
            // And it uses the minute it is given: a cap that stops it after five seconds would
            // pass the test above and be a different bug.
            assertTrue(
                waveform.totalMillis > VibrationLimits.LONGEST.toMillis() - 1_500,
                "$pattern gives up after ${waveform.totalMillis} ms",
            )
        }
    }

    @Test
    fun `a waveform is a whole pattern, not one to be repeated and stopped by hand`() {
        for (pattern in everyPattern) {
            val waveform = waveformFor(pattern)
            assertEquals(waveform.timings.size, waveform.amplitudes.size, "$pattern is malformed")
            assertEquals(0L, waveform.timings.first(), "$pattern does not start with the silence Android expects")
            assertEquals(0, waveform.amplitudes.first())
            // Off, on, off, on: every even slot silent and every odd one a buzz.
            waveform.amplitudes.forEachIndexed { index, amplitude ->
                if (index % 2 == 0) assertEquals(0, amplitude, "$pattern buzzes in slot $index")
                else assertTrue(amplitude > 0, "$pattern is silent in slot $index")
            }
        }
    }

    @Test
    fun `continuous is one buzz and pulsed is many`() {
        val continuous = waveformFor(VibrationPattern(rhythm = VibrationRhythm.CONTINUOUS))
        assertEquals(listOf(0L, 60_000L), continuous.timings)

        val pulsed = waveformFor(VibrationPattern(rhythm = VibrationRhythm.PULSED))
        assertTrue(pulsed.timings.size > 20, "a minute of buzz and pause is ${pulsed.timings.size} slots")
        assertEquals(VibrationLimits.PULSE_ON_MS, pulsed.timings[1])
        assertEquals(VibrationLimits.PULSE_OFF_MS, pulsed.timings[2])
    }

    @Test
    fun `strong drives the motor harder than gentle, and only that changes`() {
        val strong = waveformFor(VibrationPattern(VibrationStrength.STRONG, VibrationRhythm.PULSED))
        val gentle = waveformFor(VibrationPattern(VibrationStrength.GENTLE, VibrationRhythm.PULSED))
        assertEquals(strong.timings, gentle.timings, "strength is not a rhythm")
        assertTrue(strong.amplitudes.max() > gentle.amplitudes.max())
        assertTrue(gentle.amplitudes.max() > 0, "gentle is still a vibration")
        assertTrue(strong.amplitudes.max() <= 255, "amplitude is 1..255")
    }

    @Test
    fun `a shorter limit is honoured, and an absurd one cannot make a broken pattern`() {
        val preview = waveformFor(VibrationPattern(), Duration.ofSeconds(3))
        assertTrue(preview.totalMillis <= 3_000, "a three-second preview ran ${preview.totalMillis} ms")
        assertTrue(preview.totalMillis > 1_000, "a three-second preview is not worth feeling at ${preview.totalMillis} ms")
        for (silly in listOf(Duration.ZERO, Duration.ofMillis(-5), Duration.ofMillis(10))) {
            val waveform = waveformFor(VibrationPattern(rhythm = VibrationRhythm.PULSED), silly)
            assertEquals(waveform.timings.size, waveform.amplitudes.size)
            assertTrue(waveform.totalMillis >= 0)
        }
    }

    @Test
    fun `a notification buzzes for an instant, because it is not an alarm`() {
        for (pattern in everyPattern) {
            val timings = notificationPattern(pattern)
            assertEquals(0L, timings.first())
            assertTrue(timings.sum() <= 1_500, "$pattern makes a notification buzz for ${timings.sum()} ms")
        }
        // Only the rhythm reaches a channel: Android's pattern is durations and nothing else.
        assertEquals(
            notificationPattern(VibrationPattern(VibrationStrength.STRONG, VibrationRhythm.PULSED)),
            notificationPattern(VibrationPattern(VibrationStrength.GENTLE, VibrationRhythm.PULSED)),
        )
    }
}
