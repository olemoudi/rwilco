package dev.rwilco.model

/**
 * The passphrase rule, in one place: long, and made of more than one kind of character.
 *
 * Twelve is the floor because the thing it protects cannot be recovered by anybody — there is
 * no "forgot my passphrase" behind this, and a short one is the only part of the design an
 * attacker with the file can attack.
 */
const val MIN_PASSPHRASE_LENGTH = 12

/** The most a bar can say about a passphrase; [level] is how many of its segments are lit. */
data class PassphraseStrength(
    val length: Int,
    val hasLetter: Boolean,
    val hasDigit: Boolean,
    /** Anything that is neither a letter nor a digit: a space counts, which is the point. */
    val hasOther: Boolean,
    /** 0 nothing, 1 not enough, 2 enough, 3 good, 4 long and varied. */
    val level: Int,
) {
    /** Long enough and alphanumeric: what the app refuses to go ahead without. */
    val meetsMinimum: Boolean get() = length >= MIN_PASSPHRASE_LENGTH && hasLetter && hasDigit

    companion object {
        /** How many segments the bar has. */
        const val LEVELS = 4
    }
}

fun passphraseStrength(passphrase: String): PassphraseStrength {
    val hasLetter = passphrase.any { it.isLetter() }
    val hasDigit = passphrase.any { it.isDigit() }
    val hasOther = passphrase.any { !it.isLetterOrDigit() }
    val enough = passphrase.length >= MIN_PASSPHRASE_LENGTH && hasLetter && hasDigit
    val level = when {
        passphrase.isEmpty() -> 0
        !enough -> 1
        passphrase.length >= 20 && hasOther -> 4
        passphrase.length >= 16 || hasOther -> 3
        else -> 2
    }
    return PassphraseStrength(passphrase.length, hasLetter, hasDigit, hasOther, level)
}

fun passphraseIsStrongEnough(passphrase: String): Boolean = passphraseStrength(passphrase).meetsMinimum
