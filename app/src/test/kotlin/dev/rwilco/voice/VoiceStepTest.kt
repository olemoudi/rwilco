package dev.rwilco.voice

import dev.rwilco.model.HeardSpan
import dev.rwilco.model.Reminder
import dev.rwilco.model.TriggerFamily
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** The dictation sheet's states, step by step, with the recognizer's late and odd answers in them. */
class VoiceStepTest {

    private val reminder = Reminder(id = "r", text = "Comprar pan", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)

    private fun heard(event: VoiceEvent) = VoiceInputEvent.Heard(event)

    @Test
    fun `the permission's answer starts the listening or says why not`() {
        assertEquals(VoiceState.Listening(), voiceStep(VoiceState.Asking, VoiceInputEvent.Permission(true)))
        assertEquals(VoiceState.Failed(VoiceFailure.NO_PERMISSION), voiceStep(VoiceState.Asking, VoiceInputEvent.Permission(false)))
        // An answer nobody is waiting for changes nothing.
        assertEquals(VoiceState.Listening("hola"), voiceStep(VoiceState.Listening("hola"), VoiceInputEvent.Permission(false)))
    }

    @Test
    fun `the words come in as they are heard, and the level with them`() {
        val partial = voiceStep(VoiceState.Listening(), heard(VoiceEvent.Partial("comprar pan")))
        assertEquals(VoiceState.Listening("comprar pan"), partial)
        assertEquals(VoiceState.Listening("comprar pan", level = 0.5f), voiceStep(partial, heard(VoiceEvent.Level(4f))))
        assertEquals(VoiceState.Listening(said = listOf("comprar pan")), voiceStep(partial, heard(VoiceEvent.Ready)))
    }

    @Test
    fun `a pause closes a stretch and the listening goes on`() {
        val first = voiceStep(VoiceState.Listening("comprar pan"), heard(VoiceEvent.Segment("comprar pan")))
        assertEquals(VoiceState.Listening(said = listOf("comprar pan")), first)
        val second = voiceStep(voiceStep(first, heard(VoiceEvent.Partial("al llegar a"))), heard(VoiceEvent.Segment("")))
        // An empty end after a good partial keeps the partial, as an empty final does.
        assertEquals(VoiceState.Listening(said = listOf("comprar pan", "al llegar a")), second)
        assertEquals("comprar pan al llegar a", (second as VoiceState.Listening).transcript)
        // A silence with nothing in it adds nothing.
        assertEquals(second, voiceStep(second, heard(VoiceEvent.Segment(""))))
    }

    @Test
    fun `only Terminado ends it, with every stretch heard`() {
        val listening = VoiceState.Listening("y llevar 10 minutos", said = listOf("comprar pan", "al llegar a casa"))
        val finishing = voiceStep(listening, VoiceInputEvent.Finish)
        assertEquals(listening.copy(finishing = true), finishing)
        assertEquals(
            VoiceState.Thinking(listOf("comprar pan", "al llegar a casa", "y llevar 10 minutos allí")),
            voiceStep(finishing, heard(VoiceEvent.Final("y llevar 10 minutos allí"))),
        )
        // An empty last stretch keeps what was showing.
        assertEquals(
            VoiceState.Thinking(listOf("comprar pan", "al llegar a casa", "y llevar 10 minutos")),
            voiceStep(finishing, heard(VoiceEvent.Final(""))),
        )
        // Whatever the ear says on its way out, the words are the sentence.
        assertEquals(VoiceState.Thinking(listOf("comprar pan", "al llegar a casa", "y llevar 10 minutos")), voiceStep(finishing, heard(VoiceEvent.Failed(5))))
        // Asked again, or an ear that never answered: what was heard is the sentence.
        assertEquals(VoiceState.Thinking(listOf("comprar pan", "al llegar a casa", "y llevar 10 minutos")), voiceStep(finishing, VoiceInputEvent.Finish))
        assertEquals(VoiceState.Failed(VoiceFailure.NOT_HEARD), voiceStep(VoiceState.Listening(finishing = true), VoiceInputEvent.Finish))
    }

    @Test
    fun `a pause of the microphone keeps the words, and Seguir or Terminado take it on`() {
        val listening = VoiceState.Listening("al llegar", level = 0.7f, said = listOf("comprar pan"))
        val paused = voiceStep(listening, VoiceInputEvent.Pause)
        assertEquals(VoiceState.Listening(said = listOf("comprar pan", "al llegar"), paused = true), paused)
        // A closed ear's late words change nothing.
        assertEquals(paused, voiceStep(paused, heard(VoiceEvent.Partial("otra cosa"))))
        assertEquals(paused, voiceStep(paused, heard(VoiceEvent.Final("otra cosa"))))
        assertEquals(paused, voiceStep(paused, VoiceInputEvent.Pause))
        assertEquals(VoiceState.Listening(said = listOf("comprar pan", "al llegar")), voiceStep(paused, VoiceInputEvent.Resume))
        assertEquals(VoiceState.Thinking(listOf("comprar pan", "al llegar")), voiceStep(paused, VoiceInputEvent.Finish))
        // Nothing said at all: nothing to keep.
        assertEquals(VoiceState.Failed(VoiceFailure.NOT_HEARD), voiceStep(VoiceState.Listening(), VoiceInputEvent.Pause))
        // Paused on the way out: it ends, with what there is.
        assertEquals(VoiceState.Thinking(listOf("comprar pan")), voiceStep(VoiceState.Listening(said = listOf("comprar pan"), finishing = true), VoiceInputEvent.Pause))
        // Nothing to resume or finish anywhere else.
        val thinking = VoiceState.Thinking(listOf("x"))
        assertEquals(thinking, voiceStep(thinking, VoiceInputEvent.Resume))
        assertEquals(thinking, voiceStep(thinking, VoiceInputEvent.Finish))
        assertEquals(thinking, voiceStep(thinking, VoiceInputEvent.Pause))
    }

    @Test
    fun `a fresh ear keeps the stretch the old one was in the middle of`() {
        assertEquals(VoiceState.Listening(said = listOf("comprar pan", "al llegar")), voiceStep(VoiceState.Listening("al llegar", said = listOf("comprar pan")), heard(VoiceEvent.Ready)))
        assertEquals(VoiceState.Listening(said = listOf("x")), voiceStep(VoiceState.Listening(said = listOf("x")), heard(VoiceEvent.Ready)))
    }

    @Test
    fun `the final sentence is what gets thought about, or the last partial when the final is empty`() {
        assertEquals(VoiceState.Thinking(listOf("comprar pan")), voiceStep(VoiceState.Listening("compra"), heard(VoiceEvent.Final(" comprar pan "))))
        assertEquals(VoiceState.Thinking(listOf("comprar pan")), voiceStep(VoiceState.Listening("comprar pan"), heard(VoiceEvent.Final(""))))
        assertEquals(VoiceState.Failed(VoiceFailure.NOT_HEARD), voiceStep(VoiceState.Listening(), heard(VoiceEvent.Final(""))))
    }

    @Test
    fun `silence is not having heard, unless something was already heard`() {
        assertEquals(VoiceState.Failed(VoiceFailure.NOT_HEARD), voiceStep(VoiceState.Listening(), heard(VoiceEvent.Failed(7))))
        assertEquals(VoiceState.Failed(VoiceFailure.NOT_HEARD), voiceStep(VoiceState.Listening(), heard(VoiceEvent.Failed(6))))
        assertEquals(VoiceState.Thinking(listOf("comprar pan")), voiceStep(VoiceState.Listening("comprar pan"), heard(VoiceEvent.Failed(7))))
        assertEquals(VoiceState.Failed(VoiceFailure.NO_PERMISSION, "x"), voiceStep(VoiceState.Listening("x"), heard(VoiceEvent.Failed(9))))
        assertEquals(VoiceState.Failed(VoiceFailure.BROKEN), voiceStep(VoiceState.Listening(), heard(VoiceEvent.Failed(5))))
        // An ear that breaks after something was said pauses with every stretch kept, for "Seguir".
        assertEquals(
            VoiceState.Listening(said = listOf("comprar pan", "al llegar"), paused = true),
            voiceStep(VoiceState.Listening("al llegar", said = listOf("comprar pan")), heard(VoiceEvent.Failed(5))),
        )
    }

    @Test
    fun `what the recognizer says after the sentence is in changes nothing`() {
        val thinking = VoiceState.Thinking(listOf("comprar pan"))
        assertEquals(thinking, voiceStep(thinking, heard(VoiceEvent.Level(8f))))
        assertEquals(thinking, voiceStep(thinking, heard(VoiceEvent.Final("otra cosa"))))
        assertEquals(VoiceState.Created(reminder), voiceStep(thinking, VoiceInputEvent.Written(reminder)))
        // A reminder written after the sheet moved on is not shown.
        assertEquals(VoiceState.Listening(), voiceStep(VoiceState.Listening(), VoiceInputEvent.Written(reminder)))
    }

    @Test
    fun `a sentence that could not be written goes to the editor, once`() {
        val thinking = VoiceState.Thinking(listOf("llamar a mi madre"))
        assertEquals(
            VoiceState.ToEditor("Llamar a mi madre", VoiceOutcome.Why.NOTHING),
            voiceStep(thinking, VoiceInputEvent.Unwritten("Llamar a mi madre", VoiceOutcome.Why.NOTHING)),
        )
        assertEquals(VoiceState.Listening(), voiceStep(VoiceState.Listening(), VoiceInputEvent.Unwritten("x", VoiceOutcome.Why.NOTHING)))
    }

    @Test
    fun `a guess is shown, and written only once confirmed`() {
        val thinking = VoiceState.Thinking(listOf("comprar pilas en el mercado"), asking = true)
        val spoken = dev.rwilco.model.Spoken("Comprar pilas")
        val shown = voiceStep(thinking, VoiceInputEvent.Proposed(reminder, "{form}", spoken, null))
        assertEquals(VoiceState.Confirm(reminder, "{form}", spoken, null), shown)
        assertEquals(VoiceState.Created(reminder), voiceStep(shown, VoiceInputEvent.Written(reminder)))
        assertEquals(VoiceState.ToEditor("x", VoiceOutcome.Why.REFUSED, "{form}"), voiceStep(shown, VoiceInputEvent.Unwritten("x", VoiceOutcome.Why.REFUSED, "{form}")))
        // A guess that arrives after the sheet moved on is not shown.
        assertEquals(VoiceState.Listening(), voiceStep(VoiceState.Listening(), VoiceInputEvent.Proposed(reminder, "{form}", spoken, null)))
    }

    @Test
    fun `a written reminder carries what it was read from`() {
        val check = VoiceCheck(heard = "comprar pan mañana", parts = listOf(HeardSpan(12..17, TriggerFamily.TIME)))
        assertEquals(VoiceState.Created(reminder, check), voiceStep(VoiceState.Thinking(listOf("comprar pan mañana")), VoiceInputEvent.Written(reminder, check)))
    }

    // --- the check of a written reminder (0.182.0) ---

    private val later = reminder.copy(text = "Comprar pan", updatedAt = Instant.ofEpochSecond(60))
    private val nine = Instant.ofEpochSecond(9 * 3600)
    private val twentyOne = Instant.ofEpochSecond(21 * 3600)

    @Test
    fun `a check with nothing to look at twice lets the sheet go by itself`() {
        assertTrue(VoiceCheck(heard = "comprar pan mañana").clear)
        assertFalse(VoiceCheck(heard = "x", doubts = listOf(0..0)).clear)
        assertFalse(VoiceCheck(heard = "x", alternatives = listOf(VoiceAlternative(later, null))).clear)
        // Words taken out as a correction are looked at before the sheet goes (0.187.0).
        assertFalse(VoiceCheck(heard = "x", dropped = listOf(0..0)).clear)
    }

    @Test
    fun `what was taken out is struck first and named`() {
        val heard = "llamar a Juan mañana a las cinco, no, a las seis"
        val check = VoiceCheck(heard = heard, parts = listOf(HeardSpan(14..19, TriggerFamily.TIME), HeardSpan(38..47, TriggerFamily.TIME)), dropped = listOf(21..36))
        assertEquals(HeardMark(21..36, HeardMark.Kind.DROPPED), check.marks().first())
        assertEquals(listOf("a las cinco, no"), check.droppedWords())
        assertEquals(emptyList<String>(), VoiceCheck(heard = "x", dropped = listOf(4..9)).droppedWords())
    }

    @Test
    fun `choosing an alternative puts the shown one in its place, so a second tap takes it back`() {
        val tonight = VoiceAlternative(later, twentyOne)
        val check = VoiceCheck(heard = "llamar a las 9", firesAt = nine, alternatives = listOf(tonight))
        val swapped = check.swapped(reminder, tonight)
        assertEquals(twentyOne, swapped.firesAt)
        assertEquals(listOf(VoiceAlternative(reminder, nine)), swapped.alternatives)
        assertEquals(check, swapped.swapped(later, swapped.alternatives.single()))
    }

    @Test
    fun `the sentence's marks are laid how-it-was-said first, then the whens, then the doubts`() {
        val heard = "recuérdame llamar al banco mañana antes de comer"
        val check = VoiceCheck(
            heard = heard,
            parts = listOf(HeardSpan(27..32, TriggerFamily.TIME), HeardSpan(0..9, null)),
            doubts = listOf(34..41),
        )
        assertEquals(
            listOf(HeardMark(0..9, HeardMark.Kind.MANNER), HeardMark(27..32, HeardMark.Kind.WHEN, TriggerFamily.TIME), HeardMark(34..41, HeardMark.Kind.DOUBT)),
            check.marks(),
        )
        assertEquals(listOf("antes de"), check.doubtWords())
    }

    @Test
    fun `marks past the end of the sentence are cut, never thrown`() {
        val check = VoiceCheck(heard = "regar", parts = listOf(HeardSpan(3..20, TriggerFamily.PLACE), HeardSpan(30..40, null)), doubts = listOf(-2..1))
        assertEquals(listOf(HeardMark(3..4, HeardMark.Kind.WHEN, TriggerFamily.PLACE), HeardMark(0..1, HeardMark.Kind.DOUBT)), check.marks())
        assertEquals(emptyList<HeardMark>(), VoiceCheck(heard = "", parts = listOf(HeardSpan(0..3, null))).marks())
    }

    // --- Gemini's second look at a doubtful reading (0.183.0) ---

    private val doubtful = VoiceCheck(heard = "llamar al banco mañana antes de comer", doubts = listOf(23..30), refining = Refining.ASKING)
    private val shown = reminder.copy(text = "Llamar al banco antes de comer")

    @Test
    fun `a reading Gemini is still looking at waits for it`() {
        assertFalse(VoiceCheck(heard = "x", refining = Refining.ASKING).clear)
        assertTrue(VoiceCheck(heard = "x", refining = Refining.FAILED).clear)
    }

    @Test
    fun `Gemini reading it the same way clears the doubt`() {
        val same = VoiceAlternative(shown.copy(text = "llamar al banco antes de comer."), null, VoiceAlternative.Kind.GEMINI)
        val agreed = doubtful.refined(shown, same)
        assertEquals(Refining.AGREED, agreed.refining)
        assertEquals(emptyList<IntRange>(), agreed.doubts)
        assertTrue(agreed.clear)
    }

    @Test
    fun `Gemini reading it another way is offered first, and nothing is applied`() {
        val other = VoiceAlternative(shown.copy(text = "Llamar al banco"), nine, VoiceAlternative.Kind.GEMINI)
        val half = VoiceAlternative(later, twentyOne)
        val offered = doubtful.copy(alternatives = listOf(half)).refined(shown, other)
        assertEquals(Refining.OFFERED, offered.refining)
        assertEquals(listOf(other, half), offered.alternatives)
        assertEquals(doubtful.doubts, offered.doubts)
    }

    @Test
    fun `Gemini reading one already offered promotes it instead of offering it twice`() {
        val half = VoiceAlternative(later, twentyOne)
        val sameAsHalf = VoiceAlternative(later, twentyOne, VoiceAlternative.Kind.GEMINI)
        val offered = doubtful.copy(alternatives = listOf(half)).refined(shown, sameAsHalf)
        assertEquals(listOf(half.copy(kind = VoiceAlternative.Kind.GEMINI)), offered.alternatives)
    }

    @Test
    fun `no reading from Gemini, or none in time, leaves everything as it was`() {
        val failed = doubtful.refined(shown, null)
        assertEquals(Refining.FAILED, failed.refining)
        assertEquals(doubtful.doubts, failed.doubts)
        assertEquals(doubtful.alternatives, failed.alternatives)
    }

    @Test
    fun `choosing Gemini's reading leaves the original on offer, and choosing it back offers Gemini's again`() {
        val gemini = VoiceAlternative(later, twentyOne, VoiceAlternative.Kind.GEMINI)
        val chosen = doubtful.copy(refining = Refining.OFFERED, alternatives = listOf(gemini)).swapped(shown, gemini)
        assertEquals(listOf(VoiceAlternative(shown, null, VoiceAlternative.Kind.ORIGINAL)), chosen.alternatives)
        assertEquals(listOf(gemini), chosen.swapped(later, chosen.alternatives.single()).alternatives)
    }

    // --- the steps applied atomically, whatever the sheet is now (the review round) ---

    @Test
    fun `Gemini's answer lands only on the sheet still showing that reminder`() {
        val offer = VoiceAlternative(later.copy(text = "Llamar al banco"), nine, VoiceAlternative.Kind.GEMINI)
        val asking = VoiceState.Created(shown, doubtful)
        assertEquals(Refining.OFFERED, (asking.refinedWith(shown.id, offer) as VoiceState.Created).check.refining)
        // A sheet closed meanwhile stays closed; another reminder's sheet is left alone.
        assertEquals(null, (null as VoiceState?).refinedWith(shown.id, offer))
        assertEquals(asking, asking.refinedWith("another", offer))
        assertEquals(VoiceState.Listening(), VoiceState.Listening().refinedWith(shown.id, offer))
    }

    @Test
    fun `a choice made while Gemini was asked survives its answer`() {
        val half = VoiceAlternative(later, twentyOne)
        val chosen = VoiceState.Created(shown, doubtful.copy(alternatives = listOf(half))).choosing(half) as VoiceState.Created
        assertEquals(later, chosen.reminder)
        val answered = chosen.refinedWith(shown.id, null) as VoiceState.Created
        assertEquals(later, answered.reminder)
        assertEquals(Refining.FAILED, answered.check.refining)
        // A reading no longer on offer is not chosen.
        assertEquals(chosen, chosen.choosing(half))
    }

    @Test
    fun `a sheet let go of while Gemini was asked stops saying it is asking`() {
        assertEquals(Refining.FAILED, (VoiceState.Created(shown, doubtful).refiningStopped() as VoiceState.Created).check.refining)
        val settled = VoiceState.Created(shown, doubtful.copy(refining = Refining.AGREED))
        assertEquals(settled, settled.refiningStopped())
        assertEquals(null, (null as VoiceState?).refiningStopped())
    }

    @Test
    fun `the position of aqui travels with the reading it was drawn for`() {
        val spot = dev.rwilco.model.Fix(40.4, -3.7, 10.0, Instant.EPOCH)
        val gemini = VoiceAlternative(later, twentyOne, VoiceAlternative.Kind.GEMINI, here = null)
        val swapped = doubtful.copy(here = spot, alternatives = listOf(gemini)).swapped(shown, gemini)
        assertEquals(null, swapped.here)
        assertEquals(spot, swapped.alternatives.single().here)
        assertFalse(swapped.readsAsSaid)
        assertTrue(swapped.swapped(later, swapped.alternatives.single()).readsAsSaid)
    }

    @Test
    fun `the same reading is the same words and the same when, however it is written`() {
        assertTrue(sameReading(shown, shown.copy(text = "LLAMAR al banco, antes de comer.")))
        assertFalse(sameReading(shown, shown.copy(text = "Llamar a la banca antes de comer")))
        assertFalse(sameReading(shown, shown.copy(ruleMatch = dev.rwilco.model.RuleMatch.ALL)))
    }

    @Test
    fun `again starts a fresh round`() {
        assertEquals(VoiceState.Listening(), voiceStep(VoiceState.Failed(VoiceFailure.NOT_HEARD), VoiceInputEvent.Retry))
    }

    @Test
    fun `the level runs from silence to shouting and no further`() {
        assertEquals(0f, levelOf(-10f))
        assertEquals(0f, levelOf(-2f))
        assertEquals(1f, levelOf(10f))
        assertEquals(1f, levelOf(40f))
    }
}
