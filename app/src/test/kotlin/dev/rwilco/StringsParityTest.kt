package dev.rwilco

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Enforces the repo convention that every user-facing string exists in BOTH locales: a key
 * added to values/strings.xml (EN) must be added to values-es/strings.xml too, and vice
 * versa. A missing translation would otherwise only surface as a mixed-language screen on
 * whichever device happens to run in the other locale.
 */
class StringsParityTest {

    private val keyPattern = Regex("<(string|plurals|string-array) name=\"([^\"]+)\"")
    private val arrayPattern = Regex("<string-array name=\"([^\"]+)\">(.*?)</string-array>", RegexOption.DOT_MATCHES_ALL)

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

    /** The release notes are arrays: a bullet added to one locale alone shipped a mixed changelog. */
    @Test
    fun `every string-array has the same number of items in both locales`() {
        fun itemsOf(relativePath: String): Map<String, Int> {
            val file = sequenceOf(File(relativePath), File("app/$relativePath")).first { it.exists() }
            return arrayPattern.findAll(file.readText()).associate { it.groupValues[1] to Regex("<item>").findAll(it.groupValues[2]).count() }
        }
        val english = itemsOf("src/main/res/values/strings.xml")
        val spanish = itemsOf("src/main/res/values-es/strings.xml")
        val uneven = english.filter { (name, count) -> spanish[name] != count }
        assertEquals(emptyMap<String, Int>(), uneven, "arrays with a different number of items in Spanish")
    }
}
