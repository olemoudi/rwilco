package dev.rwilco.model

/**
 * What in a reminder's words can be acted on (0.135.0): a number to ring, a link to open.
 *
 * "Llamar al dentista 912 345 678" rang, was read, and then the number had to be carried by eye
 * into the dialer; a page shared into the app became a reminder holding a link nothing could
 * open. The words were text and nothing else, everywhere.
 *
 * **Offered, never acted on** — a row in a card's menu, a held pill on the alert — and found
 * with the same bias the words-reading chip has (`WhenInText`): a missed number costs somebody
 * the typing they were going to do anyway, a date offered as a telephone costs the trust in
 * every row after it. So what *looks like* something else is taken out before the telephones
 * are looked for, and the rules for one are strict.
 */
sealed interface Actionable {
    /** As it was written, which is what a row shows. */
    val raw: String

    /** [dial] is what a dialer takes: the digits, and the leading plus when there was one. */
    data class Phone(override val raw: String, val dial: String) : Actionable

    /** [url] always carries its scheme; [host] is what a row can say without the whole address. */
    data class Link(override val raw: String, val url: String, val host: String) : Actionable
}

/** The first [limit] of them, in the order they were written, each once. */
fun actionablesIn(text: String, limit: Int = 3): List<Actionable> {
    if (text.isBlank()) return emptyList()
    val found = ArrayList<Pair<Int, Actionable>>()
    var rest = text
    // Links first, and blanked out afterwards: an order number inside one is not a telephone.
    // **Only the web.** The words may have come from another app's share, and a row that opened
    // whatever scheme it was handed would be that app's way into every other one on the phone.
    for (match in LINK.findAll(text)) {
        val raw = match.value.withoutSentenceTail()
        val url = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
        val bare = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
        val host = if (bare.startsWith("www.", ignoreCase = true)) bare.drop(4) else bare
        if (host.isNotEmpty()) found += match.range.first to Actionable.Link(raw, url, host)
        rest = rest.blank(match.range)
    }
    // What looks like a date, an hour or a price is one: "27.08.2026 10.30" is twelve digits
    // with separators between them, which is exactly what a telephone is.
    for (pattern in NOT_PHONES) for (match in pattern.findAll(rest)) rest = rest.blank(match.range)
    for (match in PHONE.findAll(rest)) {
        val raw = match.value.trim()
        val digits = raw.count { it.isDigit() }
        if (digits !in PHONE_DIGITS) continue
        val dial = (if (raw.startsWith("+")) "+" else "") + raw.filter { it.isDigit() }
        found += match.range.first to Actionable.Phone(raw, dial)
    }
    return found.sortedBy { it.first }.map { it.second }.distinctBy { if (it is Actionable.Phone) it.dial else (it as Actionable.Link).url }.take(limit)
}

/** The same length, so every position found afterwards is still a position in the words. */
private fun String.blank(range: IntRange): String = replaceRange(range, " ".repeat(range.last - range.first + 1))

private val LINK = Regex("(?i)(?<![\\w@])(?:https?://|www\\.)[^\\s<>\"']+")

/** What ends a sentence is not part of the address it ended on. */
private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '»', '”', '\'')

/**
 * The address without the punctuation the sentence put after it — **except a bracket the address
 * opened itself** (0.139.0). Stripped flat, "es.wikipedia.org/wiki/Torrijas_(postre)" lost its
 * last character and the row opened an address that is not a page; Spanish Wikipedia writes half
 * its disambiguations that way. A closing bracket with its opening inside the address belongs to
 * the address; one without belongs to the hand that wrote "(ver https://…)".
 */
private fun String.withoutSentenceTail(): String {
    var end = length
    while (end > 0) {
        val last = this[end - 1]
        if (last !in TRAILING) break
        val opening = when (last) {
            ')' -> '('
            ']' -> '['
            else -> null
        }
        // Counted over what is left, not over the whole: "…/a_(b))" gives one bracket back and
        // keeps the other.
        val sofar = take(end)
        if (opening != null && sofar.count { it == opening } >= sofar.count { it == last }) break
        end--
    }
    return substring(0, end)
}

// **A date is a whole one, not the tail of something longer** (0.139.0): "91.234.56.78" ends in
// what reads as a date, and blanking it left a stub under the nine-digit floor, so a landline
// written the way half of Spain writes one was not offered at all. Bounded by a separator on
// either side as well as a digit, so a run of groups is never mistaken for a date inside it.
private val NOT_PHONES = listOf(
    // 27.08.2026 · 26/8 · 3-10-26
    Regex("(?<![\\d./-])\\d{1,2}[./-]\\d{1,2}(?:[./-]\\d{2,4})?(?![\\d./-])"),
    // 17:30 · 10.30 · 12,50
    // **The colon is never the last thing in a class.** Android's regex is ICU's, and ICU reads
    // ":]" as the end of a POSIX property — `[\d.,:]` threw at class-init on the phone and took
    // the whole file with it, while the JVM tests were green. `PatternsOnDeviceTest` is the guard.
    Regex("(?<![\\d:.,-])\\d{1,2}[:.,]\\d{2}(?![\\d:.,])"),
)

/**
 * A run of digits in groups, one separator at most between them, a plus in front at most — and
 * **the whole run**: not starting in the middle of a longer one (the tail of an IBAN is twelve
 * digits), not glued to a letter (a reference number), and not stopping short of where the
 * digits stop (the first fifteen of a card).
 */
private val PHONE = Regex("(?<![\\w+])(?<!\\d[ .\\-])\\+?\\d(?:[ .\\-]?\\d)*(?![ .\\-]?\\d)(?!\\w)")

/** Nine is a national number here; fifteen is as long as one gets (E.164). */
private val PHONE_DIGITS = 9..15
