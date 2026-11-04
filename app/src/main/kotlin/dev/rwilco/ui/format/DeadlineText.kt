package dev.rwilco.ui.format

import dev.rwilco.R
import dev.rwilco.model.Deadline

/**
 * The deadline on a set, in words. Three surfaces say it and each has its own shape: the
 * editor's button says what is set ("Plazo: de 18:00 a 22:00"), the card's row says it as a
 * line of its own, and the sentence over "Guardar" says it as a clause after the rules
 * ("…, entre las 18:00 y las 22:00"). One file, so the three cannot drift.
 */
fun deadlineButtonLabel(words: Words, deadline: Deadline): String = when (deadline) {
    is Deadline.Window -> words.get(R.string.editor_deadline_window, TimeText.time(deadline.from, words.is24h, words.locale), TimeText.time(deadline.to, words.is24h, words.locale))
    is Deadline.Timer -> words.get(R.string.editor_deadline_timer, durationText(words, deadline.minutes))
}

fun deadlineCardLabel(words: Words, deadline: Deadline): String = when (deadline) {
    is Deadline.Window -> words.get(R.string.card_deadline_window, TimeText.time(deadline.from, words.is24h, words.locale), TimeText.time(deadline.to, words.is24h, words.locale))
    is Deadline.Timer -> words.get(R.string.card_deadline_timer, durationText(words, deadline.minutes))
}

/**
 * The clause after the rules, with no comma of its own: the sentence puts that in.
 *
 * A window says both ends (2026-11-04): "antes de las 22:00" read as "any time until ten", and
 * a set whose last rule happened at five was not going to ring at five — the round only opens
 * at eighteen hundred.
 */
fun deadlinePhrase(words: Words, deadline: Deadline): String = when (deadline) {
    is Deadline.Window -> words.get(R.string.editor_sentence_deadline_window, TimeText.time(deadline.from, words.is24h, words.locale), TimeText.time(deadline.to, words.is24h, words.locale))
    is Deadline.Timer -> words.get(R.string.editor_sentence_deadline_timer, durationText(words, deadline.minutes))
}
