package dev.rwilco.cheer

import dev.rwilco.model.CheerKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * The encouragement's phrasings, read straight out of both strings files: a line formatted with
 * the wrong arguments crashes the worker or Home in the one language nobody tested, and a line
 * missing its number is exactly the canned line this feature exists not to be.
 */
class CheerStringsTest {

    private val arrayPattern = Regex("<string-array name=\"(cheer_[a-z_]+)\">(.*?)</string-array>", RegexOption.DOT_MATCHES_ALL)
    private val itemPattern = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
    private val placeholder = Regex("%(\\d)\\$([sd])")

    /** The array's resource name for a kind, as [CheerText.arrayOf] maps it. */
    private val names = mapOf(
        CheerKind.STREAK to "cheer_streak",
        CheerKind.BEST_STREAK to "cheer_best_streak",
        CheerKind.NEVER_FAILS to "cheer_never_fails",
        CheerKind.FIRST_TIME to "cheer_first_time",
        CheerKind.WEEK_UP to "cheer_week_up",
        CheerKind.WEEK_COUNT to "cheer_week_count",
        CheerKind.TODAY_COUNT to "cheer_today",
        CheerKind.AHEAD to "cheer_ahead",
        CheerKind.ACHIEVEMENT to "cheer_achievement",
    )

    private fun arrays(relativePath: String): Map<String, List<String>> {
        val file = sequenceOf(File(relativePath), File("app/$relativePath")).first { it.exists() }
        return arrayPattern.findAll(file.readText()).associate { match ->
            match.groupValues[1] to itemPattern.findAll(match.groupValues[2]).map { it.groupValues[1].replace("\\'", "'") }.toList()
        }
    }

    private val locales = mapOf(
        "src/main/res/values/strings.xml" to Locale.ENGLISH,
        "src/main/res/values-es/strings.xml" to Locale.forLanguageTag("es"),
    )

    @Test
    fun `every kind has phrasings, and the same number of them in both languages`() {
        val english = arrays("src/main/res/values/strings.xml")
        val spanish = arrays("src/main/res/values-es/strings.xml")
        for ((kind, name) in names) {
            val count = english[name]?.size ?: 0
            assertTrue(count >= 4, "$kind needs a few phrasings to rotate through, has $count")
            assertEquals(count, spanish[name]?.size, "$kind in Spanish")
        }
    }

    @Test
    fun `every cheer line carries the placeholders its kind is formatted with`() {
        for ((path, locale) in locales) {
            val all = arrays(path)
            for ((kind, name) in names) {
                val expected = CheerText.ARGUMENTS.getValue(kind)
                for (line in all.getValue(name)) {
                    val used = placeholder.findAll(line).map { it.groupValues[1].toInt() to it.groupValues[2] }.toSet()
                    val wanted = expected.mapIndexed { index, type -> index + 1 to type.toString() }.toSet()
                    assertEquals(wanted, used, "$path $name: «$line»")
                    // And it formats: the arguments a real line is handed, in their real types.
                    val args: Array<Any> = expected.map { if (it == 's') "Regar las plantas" else 12 }.toTypedArray()
                    val text = String.format(locale, line, *args)
                    assertTrue("%" !in text, "$path $name left a placeholder: «$text»")
                }
            }
        }
    }

    @Test
    fun `no cheer line carries an emoji`() {
        for (path in locales.keys) {
            for ((name, lines) in arrays(path)) {
                for (line in lines) {
                    assertTrue(line.codePoints().noneMatch { it >= 0x1F000 || it in 0x2600..0x27BF }, "$path $name: «$line»")
                }
            }
        }
    }
}
