package dev.rwilco.ui.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The colour a tag works itself out to. What matters is not which colour — it is that the same
 * word always lands on the same one, that no word lands on a colour that already means
 * something, and that a handful of ordinary tags do not all come out looking alike.
 */
class TagColorsTest {

    /** The four this app has already spent. */
    private val reserved = TagColors.RESERVED

    private fun apart(a: Float, b: Float): Float {
        val gap = abs(a - b) % 360f
        return minOf(gap, 360f - gap)
    }

    @Test
    fun `the same word is always the same colour, whatever its case`() {
        assertEquals(TagColors.hue("compra"), TagColors.hue("compra"))
        assertEquals(TagColors.hue("Compra"), TagColors.hue("compra"), "one tag, one colour")
        assertEquals(TagColors.hue("CASA"), TagColors.hue("casa"))
    }

    @Test
    fun `no tag lands on a hue that already means something`() {
        val words = listOf(
            "casa", "compra", "salud", "trabajo", "gym", "recetas", "coche", "plantas", "banco",
            "médico", "regalos", "viaje", "papeles", "bici", "cumples", "cocina", "obra", "pádel",
        )
        for (word in words) {
            val hue = TagColors.hue(word)
            assertTrue(hue in 0f..360f, "$word landed off the circle at $hue")
            for (taken in reserved) {
                assertTrue(
                    apart(hue, taken) >= 24f,
                    "\"$word\" at $hue is too near the ${taken}° a family colour or the amber already means",
                )
            }
        }
    }

    @Test
    fun `the palette itself is spread out, and nowhere near the hues that are spoken for`() {
        val hues = TagColors.hues()
        for (hue in hues) {
            for (taken in reserved) {
                assertTrue(apart(hue, taken) >= 24f, "the palette itself has a hue at $hue, too near ${taken}°")
            }
        }
        // Every pair far enough apart to be told apart side by side. A palette exists so that
        // two tags are either the same colour or plainly a different one — never almost.
        for (i in hues.indices) {
            for (j in i + 1 until hues.size) {
                assertTrue(apart(hues[i], hues[j]) >= 26f, "${hues[i]} and ${hues[j]} are the same colour twice")
            }
        }
    }

    @Test
    fun `every tag lands on the palette and a row of them spreads across it`() {
        val words = listOf("casa", "compra", "salud", "trabajo", "gym", "coche", "papeleo", "viaje")
        val palette = TagColors.hues().toSet()
        for (word in words) assertTrue(TagColors.hue(word) in palette, "\"$word\" invented a hue")
        // Sixteen swatches and a hash: two tags CAN come out the same, and that is the deal —
        // the same colour is a reading somebody can live with, "almost the same" is not, which
        // is why the palette is picked rather than generated. What is worth pinning is that a
        // handful of ordinary words spread out rather than piling up.
        val colours = words.map { TagColors.color(it, dark = true) }
        assertTrue(colours.distinct().size >= words.size - 2, "eight ordinary tags: only ${colours.distinct().size} colours")
    }

    @Test
    fun `the two shades of a hue are the same colour at different weights`() {
        val palette = TagColors.hues()
        // Whatever the word, its hue is one of the eight; the shade is the other half of the
        // sixteen, and it is a weight rather than a different colour.
        val words = (1..200).map { "tag$it" }
        val perHue = words.groupBy { TagColors.hue(it) }
        assertEquals(palette.size, perHue.size, "two hundred words did not reach the whole palette")
        for ((hue, sharing) in perHue) {
            val weights = sharing.map { TagColors.color(it, dark = true) }.distinct()
            assertEquals(2, weights.size, "hue $hue came out in ${weights.size} weights, not two")
        }
    }

    @Test
    fun `the light and dark readings are the same hue and different weights`() {
        val dark = TagColors.color("compra", dark = true)
        val light = TagColors.color("compra", dark = false)
        assertTrue(dark.red + dark.green + dark.blue > light.red + light.green + light.blue,
            "the dark scheme wants the lighter ink and the light scheme the darker one")
    }
}
