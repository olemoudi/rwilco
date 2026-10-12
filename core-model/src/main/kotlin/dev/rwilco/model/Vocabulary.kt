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
 *   before is put right the same way again, in the same place ([Vocabulary.learned]): "a visto"
 *   was "a Vito" once, so it is again — and "he visto" is still seen.
 */

/** The person's own words, most used first, and the fixes they have made by hand. */
class Vocabulary(
    /** As the person writes them, most used first. */
    val words: List<String>,
    /**
     * What the person put right by hand (0.190.0): the word before and the word as a recognizer
     * heard it, folded and joined by a space ("a visto"), → the word as they put it ("Vito").
     */
    val learned: Map<String, String> = emptyMap(),
) {
    /** The names among them — what the person writes capitalised — which are all that is ever written in. */
    private val names: List<String> = (learned.values + words).filter { it.first().isUpperCase() }.distinct()
    private val byKey: Map<String, String> = names.reversed().associateBy { fold(it) }

    /** By sound, never a sound too short to be anybody's in particular ("Will" is "bi", as "vi" is). */
    private val bySound: Map<String, String> = names.reversed().associateBy(::soundOf).filterKeys { it.length >= MIN_SOUND }

    /** What to ask a recognizer to lean towards: the most used, as written. */
    val bias: List<String> get() = (learned.values.distinct() + words).distinct().take(MAX_BIAS)

    /**
     * Of a recognizer's [alternatives], best first, the one that names most of the person's own
     * names — each once, only written as a name ("rosa" and "más" are ordinary words whatever the
     * address book holds), and only the unusual ones, which are what a recognizer would not have
     * ranked first ("Pilar" it would have) — and the first, when none names more than it.
     */
    fun choose(alternatives: List<String>): String? {
        val scored = alternatives.filter { it.isNotBlank() }.map { it to namesIn(it) }
        val best = scored.maxOfOrNull { it.second } ?: return null
        return scored.first { it.second == best }.first
    }

    private fun namesIn(text: String): Int =
        WORD.findAll(text).filter { it.value.first().isUpperCase() }.map { fold(it.value) }.filter { it in byKey && it !in COMMON_NAMES }.toSet().size

    /**
     * The person's names that sound like [word] — or nearly, a letter's sound either way — for a
     * word tapped as wrong (0.192.0): "Bito" offers Vito. Never the word itself; the nearest and
     * most used first.
     */
    fun soundingLike(word: String, max: Int = 3): List<String> {
        val sound = soundOf(word)
        if (sound.length < MIN_SOUND) return emptyList()
        return names.filter { fold(it) != fold(word) }
            .map { it to distance(soundOf(it), sound) }
            .filter { (_, apart) -> apart <= maxOf(1, sound.length / 4) }
            .sortedBy { it.second }
            .map { it.first }
            .take(max)
    }

    /** [text] with the person's own words written as they write them; the same text when nothing changes. */
    fun corrected(text: String): String {
        if (names.isEmpty() && learned.isEmpty()) return text
        var previous = ""
        var previousEnd = -1
        return WORD.replace(text) { match ->
            val word = match.value
            val key = fold(word)
            // Capitalised for being the start of what was heard, or of a sentence in it: not a guess at a name.
            val opens = previousEnd < 0 || text.substring(previousEnd + 1, match.range.first).any { it in SENTENCE_BREAKS }
            val fixed = learned["$previous $key".trim()]
            previous = key
            previousEnd = match.range.last
            if (fixed != null) return@replace fixed
            if (!word.first().isUpperCase()) return@replace word
            byKey[key]
                // A name the recognizer guessed at (capitalised, not just opening what it heard) that
                // sounds like one of theirs — unless it is a name spelled as names are: "Sara" is
                // somebody, whatever is bought at Zara; "Bito" is nobody.
                ?: (if (opens || key in COMMON_NAMES) null else bySound[soundOf(word)])
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
    fun count(raw: String) {
        val word = raw.removeSuffix("'s").removeSuffix("’s")
        if (word.length < MIN_LENGTH || word.none(Char::isLetter) || word.any(Char::isDigit) || fold(word) in SMALL) return
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
            // A capital after a full stop, a colon, a new line or a dash is the start of something, not a name.
            val midSentence = i > 0 && reminder.text.substring(tokens[i - 1].range.last + 1, token.range.first).none { it in SENTENCE_BREAKS }
            if (named || (midSentence && word.first().isUpperCase())) count(word)
        }
        reminder.tags.forEach { tag -> WORD.findAll(tag).forEach { count(it.value) } }
    }
    places.forEach { place -> WORD.findAll(place.label).forEach { count(it.value) } }
    val words = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { spelling.getValue(it.key) }
    return Vocabulary(words, learned)
}

/**
 * What the person put right in [heard] to make it [fixed] (0.190.0), to be put right the same way
 * the next time ([Vocabulary.learned]): each word swapped one for one where the rest lines up,
 * keyed by the word before it. Only what was **misheard**: a name (capitalised in mid-sentence)
 * that sounds close to what was heard — "visto" was "Vito", "garci" was "García" — or the same
 * word written as a name now ("belen", Belén). A word changed for another ("comprar" for
 * "Recoger", "la fontanera" for "Juan Pérez") is a change of mind about that sentence, and a time
 * or a day put right is about that sentence too: nobody would want either everywhere.
 */
fun fixesBetween(heard: String, fixed: String): Map<String, String> {
    val a = WORD.findAll(heard).map { it.value }.toList()
    val bTokens = WORD.findAll(fixed).toList()
    val b = bTokens.map { it.value }
    if (a.size.toLong() * b.size > MAX_TABLE) return emptyMap()
    fun opens(j: Int) = j == 0 || fixed.substring(bTokens[j - 1].range.last + 1, bTokens[j].range.first).any { it in SENTENCE_BREAKS }
    // A capital opening a sentence says nothing by itself; a word that also sounds like the one
    // heard was a name misheard wherever it stands ("bito llamará" → "Vito llamará").
    fun learnable(was: String, now: String, j: Int, swapped: Boolean) =
        now.first().isUpperCase() && (swapped || !opens(j)) && now.length >= MIN_LENGTH && now.none(Char::isDigit) &&
            fold(was) !in SMALL && !mightSayWhen(was) && !mightSayWhen(now)
    // The longest run of words both say, word for word, as a table: what lines up.
    val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
        lcs[i][j] = if (fold(a[i]) == fold(b[j])) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
    }
    val fixes = LinkedHashMap<String, String>()
    fun learn(i: Int, j: Int) {
        fixes["${if (i > 0) fold(a[i - 1]) else ""} ${fold(a[i])}".trim()] = b[j]
    }
    var i = 0
    var j = 0
    while (i < a.size || j < b.size) {
        if (i < a.size && j < b.size && fold(a[i]) == fold(b[j])) {
            // The same word, written as a name now: "belen" was Belén.
            if (!a[i].first().isUpperCase() && learnable(a[i], b[j], j, swapped = false)) learn(i, j)
            i++
            j++
            continue
        }
        // A stretch where the two differ: from here to where they line up again.
        val (fromA, fromB) = i to j
        while (i < a.size && j < b.size && fold(a[i]) != fold(b[j])) if (lcs[i + 1][j] >= lcs[i][j + 1]) i++ else j++
        while (i < a.size && (j >= b.size || fold(a[i]) != fold(b[j])) && lcs[i + 1][j] == lcs[i][j]) i++
        while (j < b.size && (i >= a.size || fold(a[i]) != fold(b[j])) && lcs[i][j + 1] == lcs[i][j]) j++
        val gone = i - fromA
        val come = j - fromB
        if (gone != come || gone > MAX_SWAP) continue
        for (k in 0 until gone) {
            val was = a[fromA + k]
            val now = b[fromB + k]
            if (learnable(was, now, fromB + k, swapped = true) && soundsClose(was, now)) learn(fromA + k, fromB + k)
        }
    }
    return fixes
}

/** Whether a word heard and the word it was put right to sound near enough to be the same word misheard. */
private fun soundsClose(was: String, now: String): Boolean {
    val heard = soundOf(was)
    val meant = soundOf(now)
    return distance(heard, meant) <= maxOf(1, meant.length / 3)
}

/** How many letters must change to turn one into the other. */
private fun distance(a: String, b: String): Int {
    var row = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val next = IntArray(b.length + 1)
        next[0] = i
        for (j in 1..b.length) next[j] = minOf(row[j] + 1, next[j - 1] + 1, row[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        row = next
    }
    return row[b.length]
}

/** How many words in a row a fix may swap and still be one word for each. */
private const val MAX_SWAP = 3

/** Past this many words on each side, a fix is a text pasted in, not a word put right. */
private const val MAX_TABLE = 40_000L

/**
 * How a word sounds said in Spanish, roughly: the letters that are said the same written the same
 * — "b" and "v", "c", "z" and "s" before "e" and "i", "qu" and "k", a silent "h", "ll" and "y" —
 * so "Vito" and "Bito", "Cecilia" and "Sesilia", "Quique" and "Kike" come to the same thing.
 */
internal fun soundOf(word: String): String {
    var w = fold(word).filter(Char::isLetter)
    w = w.replace("ch", "0").replace("ll", "y").replace("qu", "k")
    w = GUE.replace(w, "g")
    w = GE.replace(w, "j")
    w = CE.replace(w, "s")
    w = w.replace("z", "s").replace("c", "k").replace("h", "").replace("v", "b").replace("w", "b").replace("x", "ks")
    w = END_Y.replace(w, "i")
    return DOUBLED.replace(w, "$1")
}

private val GUE = Regex("gu(?=[ei])")
private val GE = Regex("g(?=[ei])")
private val CE = Regex("c(?=[ei])")
private val END_Y = Regex("y$")
private val DOUBLED = Regex("(.)\\1+")

/** A word: letters and digits, apostrophes inside it ("O'Neill"). */
private val WORD = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)*")

/** What ends one thing said and starts another: a capital after one of these is not a name. */
private const val SENTENCE_BREAKS = ".!?:;…\n·/•—-"

/** Words too small, or too common, to be anybody's own, even in a place's name ("Casa de los abuelos"). Folded. */
private val SMALL = setOf(
    "los", "las", "del", "con", "por", "para", "una", "uno", "unos", "unas", "que", "mis", "tus", "sus", "the", "and", "for", "with", "from",
)

/**
 * Given names a recognizer spells right when it hears them, so a person's own word that sounds
 * the same never takes their place. Folded. Not every name there is: the common ones, which are
 * the ones that collide.
 */
private val COMMON_NAMES = setOf(
    "ana", "sara", "sergio", "sonia", "silvia", "susana", "cecilia", "celia", "sofia", "lucia", "lucas", "luis", "luisa", "laura", "lara",
    "marta", "maria", "mario", "marina", "mar", "carmen", "carlos", "carla", "carolina", "cristina", "cristian", "daniel", "daniela",
    "david", "diego", "elena", "helena", "eva", "isabel", "irene", "ines", "javier", "jorge", "jose", "juan", "julia", "julio", "pablo",
    "paula", "pedro", "pilar", "rosa", "raquel", "ramon", "rafael", "ruben", "victor", "victoria", "vicente", "vera", "beatriz", "belen",
    "alba", "alberto", "alejandro", "alicia", "alvaro", "andres", "angel", "antonio", "beto", "bea", "berta", "blanca", "clara",
    "eduardo", "emilio", "enrique", "esther", "fernando", "francisco", "gabriel", "gloria", "hugo", "ivan", "jaime", "jesus", "lola",
    "lorena", "manuel", "marcos", "miguel", "monica", "natalia", "nuria", "olga", "oscar", "patricia", "rocio", "rodrigo", "sandra",
    "teresa", "tomas", "vanesa", "veronica", "zoe", "will", "bill", "william", "john", "james", "mary", "anna", "sarah", "mike",
    "michael", "david", "paul", "peter", "kate", "jack", "tom", "bob", "rob", "robert", "ben", "dan", "sam", "joe", "lucy", "emma",
)

/** Words shorter than this are too easily somebody else's ("Al", "Mo"). */
private const val MIN_LENGTH = 3

/** A sound shorter than this is too many people's. */
private const val MIN_SOUND = 3

/** How many words a recognizer is asked to lean towards. */
private const val MAX_BIAS = 50
