package dev.rwilco.model

/*
 * The person's own words (0.189.0).
 *
 * A speech recognizer is trained on everybody's Spanish, and "Vito" — the owner's wife's name —
 * is nobody's word but his: it comes back as "Bito", or as whatever ordinary word sounds nearest.
 * The words are already on the phone, though, in what he has written before: the names in his
 * reminders and routines, the people he keeps in touch with, his tags and his places. So they
 * are gathered here and put to work three ways, each more careful than the last:
 *
 * - **Asked for.** The recognizer is told to lean towards them ([Vocabulary.bias]), where it can
 *   be (Android 13's biasing strings); a recognizer that cannot simply does not.
 * - **Chosen.** Of the readings the recognizer offers for one stretch, the one that says most of
 *   them wins over the one it ranked first ([Vocabulary.choose]).
 * - **Put right.** A word the recognizer wrote as a name — capitalised in mid-sentence — that is
 *   none of theirs but *sounds* like one of theirs is written as theirs ([Vocabulary.corrected]):
 *   "Llamar a Bito" is "Llamar a Vito". Never an ordinary word in lower case: "leche de vaca" is
 *   a cow, whatever the address book says about Baca. And whatever the person put right by hand
 *   before is put right the same way again ([Vocabulary.learned]).
 */

/** The person's own words, most used first, and the fixes they have made by hand. */
class Vocabulary(
    /** As the person writes them, most used first. */
    val words: List<String>,
    /** A word as a recognizer heard it (folded) → as the person put it right (0.190.0). */
    val learned: Map<String, String> = emptyMap(),
) {
    private val byKey: Map<String, String> = words.reversed().associateBy { fold(it) }
    private val bySound: Map<String, String> = words.reversed().associateBy(::soundOf)

    /** What to ask a recognizer to lean towards: the most used, as written. */
    val bias: List<String> get() = (learned.values.distinct() + words).distinct().take(MAX_BIAS)

    /**
     * Of a recognizer's [alternatives], best first, the one that says most of the person's own
     * words — the first, when none says more than it.
     */
    fun choose(alternatives: List<String>): String? {
        val scored = alternatives.filter { it.isNotBlank() }.map { it to ownWordsIn(it) }
        val best = scored.maxOfOrNull { it.second } ?: return null
        return scored.first { it.second == best }.first
    }

    private fun ownWordsIn(text: String): Int = WORD.findAll(text).count { fold(it.value).let { key -> key in byKey || key in learned } }

    /** [text] with the person's own words written as they write them; the same text when nothing changes. */
    fun corrected(text: String): String {
        if (words.isEmpty() && learned.isEmpty()) return text
        val first = WORD.find(text)?.range?.first
        return WORD.replace(text) { match ->
            val word = match.value
            val key = fold(word)
            learned[key]
                ?: byKey[key]?.takeIf { word.first().isUpperCase() }
                // A name the recognizer guessed at (capitalised, and not just the start of what it heard).
                ?: bySound[soundOf(word)]?.takeIf { word.first().isUpperCase() && match.range.first != first }
                ?: word
        }
    }
}

/**
 * The person's own words, from everything they have written: the names in their reminders and
 * routines (a word capitalised in mid-sentence), every word of the people they keep in touch with
 * and of their places, and their tags — most used first.
 */
fun vocabularyOf(reminders: List<Reminder>, places: List<SavedPlace>, learned: Map<String, String> = emptyMap()): Vocabulary {
    val counts = LinkedHashMap<String, Int>()
    val spelling = HashMap<String, String>()
    fun count(word: String) {
        if (word.length < MIN_LENGTH || word.none(Char::isLetter) || word.any(Char::isDigit)) return
        val key = fold(word)
        counts[key] = (counts[key] ?: 0) + 1
        // As the person writes it most: capitalised, accents and all, once they have written it so.
        if (spelling[key]?.first()?.isUpperCase() != true) spelling[key] = word
    }
    for (reminder in reminders) {
        val tokens = WORD.findAll(reminder.text).toList()
        tokens.forEachIndexed { i, token ->
            val word = token.value
            val named = reminder.contactKind != null
            val midSentence = i > 0 && !reminder.text.substring(tokens[i - 1].range.last + 1, token.range.first).any { it in ".!?" }
            if (named || (midSentence && word.first().isUpperCase())) count(word)
        }
        reminder.tags.forEach { tag -> WORD.findAll(tag).forEach { count(it.value) } }
    }
    places.forEach { place -> WORD.findAll(place.label).forEach { count(it.value) } }
    val words = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { spelling.getValue(it.key) }
    return Vocabulary(words, learned)
}

/**
 * How a word sounds said in Spanish, roughly: the letters that are said the same written the same
 * — "b" and "v", "c", "z" and "s" before "e" and "i", "qu" and "k", a silent "h", "ll" and "y" —
 * so "Vito" and "Bito", "Cecilia" and "Sesilia", "Quique" and "Kike" come to the same thing.
 */
internal fun soundOf(word: String): String {
    var w = fold(word).filter(Char::isLetter)
    w = w.replace("ch", "0").replace("ll", "y").replace("qu", "k")
        .replace(Regex("gu(?=[ei])"), "g").replace(Regex("g(?=[ei])"), "j").replace(Regex("c(?=[ei])"), "s")
        .replace("z", "s").replace("c", "k").replace("h", "").replace("v", "b").replace("w", "b").replace("x", "ks")
        .replace(Regex("y$"), "i")
    return w.replace(Regex("(.)\\1+"), "$1")
}

/** A word: letters and digits, apostrophes inside it ("O'Neill"). */
private val WORD = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)*")

/** Words shorter than this are too easily somebody else's ("Al", "Mo"). */
private const val MIN_LENGTH = 3

/** How many words a recognizer is asked to lean towards. */
private const val MAX_BIAS = 50
