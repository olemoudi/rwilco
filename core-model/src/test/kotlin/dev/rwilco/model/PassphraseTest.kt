package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PassphraseTest {

    @Test
    fun `twelve alphanumeric characters is the floor`() {
        assertFalse(passphraseIsStrongEnough(""))
        assertFalse(passphraseIsStrongEnough("corta1"))
        assertFalse(passphraseIsStrongEnough("solamenteletras"), "no digit")
        assertFalse(passphraseIsStrongEnough("123456789012"), "no letter")
        assertFalse(passphraseIsStrongEnough("once chars1"), "eleven characters")
        assertTrue(passphraseIsStrongEnough("docecaracter1"))
        assertTrue(passphraseIsStrongEnough("mi frase 2026"), "a space is a character like any other")
    }

    @Test
    fun `the bar fills as the passphrase gets longer and more varied`() {
        assertEquals(0, passphraseStrength("").level)
        assertEquals(1, passphraseStrength("corta1").level)
        assertEquals(2, passphraseStrength("docecaract12").level)
        assertEquals(3, passphraseStrength("dieciseiscarac12").level, "long enough to earn a third")
        assertEquals(3, passphraseStrength("doce caract1").level, "or varied enough")
        assertEquals(4, passphraseStrength("veinte caracteres y1").level)
    }

    @Test
    fun `what the bar is made of is said plainly`() {
        val strength = passphraseStrength("Frase larga 2026!")
        assertTrue(strength.hasLetter)
        assertTrue(strength.hasDigit)
        assertTrue(strength.hasOther)
        assertEquals(17, strength.length)
        assertTrue(strength.meetsMinimum)
    }
}
