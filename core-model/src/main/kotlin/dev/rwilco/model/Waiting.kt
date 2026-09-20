package dev.rwilco.model

import java.time.Instant

/*
 * What is waiting for an answer, which is what Home says first.
 *
 * Every one of these states was already somewhere on Home — a ring left unanswered sat in
 * "vencidos", a routine's in its overdue row, a contact's in its own — and all three were rows
 * about a reminder rather than rows about a question: they said when it should have rung, and
 * the tap opened the form. The one door that can actually answer them is the alert screen, and
 * nothing on Home led there. So they are lifted out of those rows and put in one card at the
 * top, and the tap is the alert.
 *
 * **Two witnesses, because neither is enough on its own.** The row knows a ring was left
 * unanswered ([Reminder.awaitingAnswer]) and knows it whether or not the card is still in the
 * shade — a notification swiped away in the half-asleep clearing of the shade is exactly the
 * one worth showing. But the row cannot know about the cards that answer nowhere: "todavía no"
 * on a routine's question takes it down and writes nothing, and the net's notes are about
 * reminders that are *not* owed an answer. Those are read from the shade itself, which is the
 * only thing that knows. So: either witness puts it on the card, and the caller supplies the
 * second ([cardOpen]) because only Android can answer it.
 */

/**
 * Whether this reminder is waiting for an answer right now. [cardOpen] is whether one of our
 * cards about it is still in the shade — see the note above; a caller with no way to ask says
 * false and gets the row's own answer.
 *
 * Paused and done are answers themselves, so neither is ever waiting — a card left in the shade
 * by a pause is stale, not owed. And a firing asked for *nothing* never had a card to answer:
 * its moment passes without a word on purpose ([firingPlan]), and it is simply overdue after.
 *
 * **A contact is never here**, although its telling is a card in the shade with two answers on
 * it and nothing else about it differs. Its row on Home is the one thing in this app built on
 * purpose *not* to look like a debt — the budget above it exists so that being behind on
 * somebody never feels like one (`Contacts.kt`, `RoutinesLine.kt`) — and this card is a
 * complaint by design. The owner was asked and kept the row (2026-09-20).
 */
fun Reminder.answerOwed(now: Instant, cardOpen: Boolean = false): Boolean =
    status == Status.ACTIVE && !isContact && (cardOpen || (awaitingAnswer(now) && actions.isNotEmpty()))

/**
 * Since when it has been waiting — the ring, or failing that the question, or the net's word,
 * and failing all three the last time the row was written. What the card counts up from.
 */
fun Reminder.owedSince(): Instant = lastFiredAt ?: askedAt ?: nudgedAt ?: updatedAt

/**
 * Everything waiting for an answer, the one that has waited longest first — reminders,
 * routines and contacts alike, because the question they are asking is the same one.
 */
fun answersOwed(
    reminders: List<Reminder>,
    now: Instant,
    cardOpen: (String) -> Boolean = { false },
): List<Reminder> = reminders
    .filter { it.answerOwed(now, cardOpen(it.id)) }
    .sortedWith(compareBy({ it.owedSince() }, { it.createdAt }))
