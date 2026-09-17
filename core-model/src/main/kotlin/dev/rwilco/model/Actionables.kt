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
        val raw = match.value.trimEnd(*TRAILING)
        val url = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').removePrefix("www.")
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

private val NOT_PHONES = listOf(
    // 27.08.2026 · 26/8 · 3-10-26
    Regex("(?<!\\d)\\d{1,2}[./-]\\d{1,2}(?:[./-]\\d{2,4})?(?!\\d)"),
    // 17:30 · 10.30 · 12,50
    Regex("(?<!\\d)\\d{1,2}[:.,]\\d{2}(?!\\d)"),
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
