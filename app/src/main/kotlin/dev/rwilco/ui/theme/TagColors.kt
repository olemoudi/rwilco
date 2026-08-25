package dev.rwilco.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.util.Locale

/**
 * A colour for a tag, worked out from its name and nothing else.
 *
 * Nothing is stored: the same word is the same colour on every screen, after a reinstall, and
 * on a phone that has never seen it. Which is the only way this can be free — a colour somebody
 * has to choose is a colour somebody has to choose for every tag they ever make.
 *
 * Four hues in this app already mean something and are not available: the amber that says
 * "this is what fires next", and the three the trigger families are recognised by ([RESERVED]).
 * Every tag lands well clear of all four, which is what keeps a green tag from reading as a
 * place and an amber one from reading as the next thing due.
 */
object TagColors {

    /**
     * The hues a tag can be, chosen rather than generated.
     *
     * Generating them was the first attempt and it does not work: this app has already spent
     * the amber, and green, blue and violet belong to the trigger families, so what is left of
     * the circle is three uneven arcs — and spreading points evenly through them puts a third
     * of every palette in the one wide green-ish arc. Three tags in a row came out three
     * shades of the same green, which is the reading a colour per tag exists to prevent.
     *
     * So these eight are picked: each at least 24° from every meaning already spoken for, and
     * at least 26° from its neighbours here. Eight is what the circle actually has room for
     * once four things own a piece of it, and pretending otherwise only produces colours that
     * are *almost* the same — which helps nobody and looks like a bug.
     */
    private val HUES = listOf(8f, 78f, 105f, 178f, 232f, 288f, 315f, 342f)

    /** Hues that already carry a meaning: the amber of "next", and the three trigger families. */
    val RESERVED = listOf(40f, 145f, 207f, 256f)

    fun hue(tag: String): Float = HUES[swatch(tag) % HUES.size]

    /** Which of the [HUES] × 2 swatches a name lands on. */
    private fun swatch(tag: String): Int {
        // The lowercase spelling, because "Compra" and "compra" are one tag everywhere else.
        // String.hashCode is specified by the language, so this is the same colour on every
        // phone and after every reinstall — which is the whole reason nothing is stored.
        val mixed = mix(tag.lowercase(Locale.ROOT).hashCode())
        return ((mixed.toLong() and 0xFFFFFFFFL) % (HUES.size * 2)).toInt()
    }

    /** The palette as it stands, for anything that wants to check it over. */
    fun hues(): List<Float> = HUES

    /**
     * A 32-bit avalanche, so that words as alike as "casa" and "cosa" do not land next to each
     * other. Java's String.hashCode spreads badly over short strings, and short strings are
     * what tags are.
     */
    private fun mix(seed: Int): Int {
        var h = seed
        h = h xor (h ushr 16)
        h *= -2048144789
        h = h xor (h ushr 13)
        h *= -1028477387
        return h xor (h ushr 16)
    }

    /**
     * Light enough to read on the dark scheme and dark enough to read on the light one, at the
     * weight the family colours already sit at — a tag should be recognisable, not loud.
     */
    private const val DARK_SATURATION = 0.60f
    private const val DARK_LIGHTNESS = 0.70f
    private const val LIGHT_SATURATION = 0.68f
    private const val LIGHT_LIGHTNESS = 0.36f

    /**
     * How far the second half of the palette sits from the first, which is what stops eight
     * tags being the ceiling. Wide, and it has to be: two tags sharing a hue meet each other as
     * two thin outlines a finger apart, and a shade's worth of difference between them is no
     * difference at all — the first try at this put a teal next to a slightly paler teal and
     * neither of them said which tag it was.
     *
     * Deeper in both schemes, never paler. The second try went the other way and the pale end
     * of the palette came out near enough to white to read as the neutral chip, which is the
     * one thing a tag colour must not do. Down from here is a richer version of the same hue
     * on the dark scheme and a darker one on the light, and both still read.
     */
    private const val DEEPER = 0.18f

    fun color(tag: String, dark: Boolean): Color {
        val deeper = swatch(tag) >= HUES.size
        return Color.hsl(
            hue = hue(tag),
            saturation = if (dark) DARK_SATURATION else LIGHT_SATURATION,
            lightness = (if (dark) DARK_LIGHTNESS else LIGHT_LIGHTNESS) - if (deeper) DEEPER else 0f,
        )
    }
}

@Composable
fun tagColor(tag: String): Color = TagColors.color(tag, LocalDarkTheme.current)

/** The same colour as a wash behind a tag's own words. Stronger on dark, as everywhere else. */
@Composable
fun tagWash(tag: String): Color = tagColor(tag).copy(alpha = if (LocalDarkTheme.current) 0.20f else 0.13f)
