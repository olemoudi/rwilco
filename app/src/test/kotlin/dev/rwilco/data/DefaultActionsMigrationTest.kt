package dev.rwilco.data

import dev.rwilco.model.Action
import dev.rwilco.model.DEFAULT_ACTIONS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The one-off that carries the new sound onto a phone that already has settings on disk.
 *
 * Changing [DEFAULT_ACTIONS] alone reaches nobody: the blob is encoded with every field and is
 * written on the first launch of every build, so every phone in use already has the old pair
 * saved. What this must not do is overwrite an answer somebody gave.
 */
class DefaultActionsMigrationTest {

    @Test
    fun `the old default set gains the sound`() {
        assertEquals(
            setOf(Action.NOTIFICATION, Action.SOUND, Action.VIBRATE),
            withSound(setOf(Action.NOTIFICATION, Action.VIBRATE)),
        )
        assertEquals(DEFAULT_ACTIONS, withSound(setOf(Action.NOTIFICATION, Action.VIBRATE)))
    }

    @Test
    fun `every other answer is left exactly as it is`() {
        // Somebody who turned the noise off on purpose: the moment passes in silence, and that
        // is a choice, not an omission.
        assertEquals(emptySet<Action>(), withSound(emptySet()))
        // The insistent sound is the sound, asked for the other way.
        assertEquals(
            setOf(Action.NOTIFICATION, Action.SOUND_UNTIL_ANSWERED),
            withSound(setOf(Action.NOTIFICATION, Action.SOUND_UNTIL_ANSWERED)),
        )
        // A card alone, a buzz alone, a full screen: none of them is the old default set.
        assertEquals(setOf(Action.NOTIFICATION), withSound(setOf(Action.NOTIFICATION)))
        assertEquals(setOf(Action.VIBRATE), withSound(setOf(Action.VIBRATE)))
        assertEquals(
            setOf(Action.FULL_SCREEN, Action.NOTIFICATION, Action.VIBRATE),
            withSound(setOf(Action.FULL_SCREEN, Action.NOTIFICATION, Action.VIBRATE)),
        )
    }

    @Test
    fun `a set that already has the sound is untouched`() {
        assertEquals(DEFAULT_ACTIONS, withSound(DEFAULT_ACTIONS))
    }
}
