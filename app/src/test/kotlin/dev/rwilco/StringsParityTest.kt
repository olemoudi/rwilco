package dev.rwilco

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Enforces the repo convention that every user-facing string exists in BOTH locales: a key
 * added to values/strings.xml (EN) must be added to values-es/strings.xml too, and vice
 * versa. A missing translation would otherwise only surface as a mixed-language screen on
 * whichever device happens to run in the other locale.
 */
class StringsParityTest {

    private val keyPattern = Regex("<(string|plurals) name=\"([^\"]+)\"")

    /** Deliberately untranslated keys (brand names fall back to the default locale). */
    private val untranslated = setOf("app_name")

    private fun keysOf(relativePath: String): Set<String> {
        // Gradle runs module tests with the module directory as CWD, but be tolerant of a
        // root-directory runner too.
        val file = sequenceOf(File(relativePath), File("app/$relativePath")).first { it.exists() }
        return keyPattern.findAll(file.readText()).map { it.groupValues[2] }.toSet()
    }

    @Test
    fun `every english string has a spanish translation and vice versa`() {
        val english = keysOf("src/main/res/values/strings.xml")
        val spanish = keysOf("src/main/res/values-es/strings.xml")

        val missingInSpanish = english - spanish - untranslated
        val missingInEnglish = spanish - english
        assertTrue(
            missingInSpanish.isEmpty() && missingInEnglish.isEmpty(),
            "Missing in values-es: $missingInSpanish\nMissing in values: $missingInEnglish",
        )
    }

    @Test
    fun `the parser matches the strings that exist`() {
        // Guards against the regex silently matching nothing and the parity test passing on
        // two empty sets.
        assertTrue(keysOf("src/main/res/values/strings.xml").size >= 4)
    }
}
