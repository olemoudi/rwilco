package dev.rwilco.voice

import dev.rwilco.model.Reminder
import org.junit.jupiter.api.Assertions.assertEquals
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
        assertEquals(partial, voiceStep(partial, heard(VoiceEvent.Ready)))
    }

    @Test
    fun `the final sentence is what gets thought about, or the last partial when the final is empty`() {
        assertEquals(VoiceState.Thinking("comprar pan"), voiceStep(VoiceState.Listening("compra"), heard(VoiceEvent.Final(" comprar pan "))))
        assertEquals(VoiceState.Thinking("comprar pan"), voiceStep(VoiceState.Listening("comprar pan"), heard(VoiceEvent.Final(""))))
        assertEquals(VoiceState.Failed(VoiceFailure.NOT_HEARD), voiceStep(VoiceState.Listening(), heard(VoiceEvent.Final(""))))
    }

    @Test
    fun `silence is not having heard, unless something was already heard`() {
        assertEquals(VoiceState.Failed(VoiceFailure.NOT_HEARD), voiceStep(VoiceState.Listening(), heard(VoiceEvent.Failed(7))))
        assertEquals(VoiceState.Failed(VoiceFailure.NOT_HEARD), voiceStep(VoiceState.Listening(), heard(VoiceEvent.Failed(6))))
        assertEquals(VoiceState.Thinking("comprar pan"), voiceStep(VoiceState.Listening("comprar pan"), heard(VoiceEvent.Failed(7))))
        assertEquals(VoiceState.Failed(VoiceFailure.NO_PERMISSION, "x"), voiceStep(VoiceState.Listening("x"), heard(VoiceEvent.Failed(9))))
        assertEquals(VoiceState.Failed(VoiceFailure.BROKEN, "x"), voiceStep(VoiceState.Listening("x"), heard(VoiceEvent.Failed(5))))
    }

    @Test
    fun `what the recognizer says after the sentence is in changes nothing`() {
        val thinking = VoiceState.Thinking("comprar pan")
        assertEquals(thinking, voiceStep(thinking, heard(VoiceEvent.Level(8f))))
        assertEquals(thinking, voiceStep(thinking, heard(VoiceEvent.Final("otra cosa"))))
        assertEquals(VoiceState.Created(reminder), voiceStep(thinking, VoiceInputEvent.Written(reminder)))
        // A reminder written after the sheet moved on is not shown.
        assertEquals(VoiceState.Listening(), voiceStep(VoiceState.Listening(), VoiceInputEvent.Written(reminder)))
    }

    @Test
    fun `a sentence that could not be written goes to the editor, once`() {
        val thinking = VoiceState.Thinking("llamar a mi madre")
        assertEquals(
            VoiceState.ToEditor("Llamar a mi madre", VoiceOutcome.Why.NOTHING),
            voiceStep(thinking, VoiceInputEvent.Unwritten("Llamar a mi madre", VoiceOutcome.Why.NOTHING)),
        )
        assertEquals(VoiceState.Listening(), voiceStep(VoiceState.Listening(), VoiceInputEvent.Unwritten("x", VoiceOutcome.Why.NOTHING)))
    }

    @Test
    fun `a guess is shown, and written only once confirmed`() {
        val thinking = VoiceState.Thinking("comprar pilas en el mercado", asking = true)
        val spoken = dev.rwilco.model.Spoken("Comprar pilas")
        val shown = voiceStep(thinking, VoiceInputEvent.Proposed(reminder, "{form}", spoken, null))
        assertEquals(VoiceState.Confirm(reminder, "{form}", spoken, null), shown)
        assertEquals(VoiceState.Created(reminder), voiceStep(shown, VoiceInputEvent.Written(reminder)))
        assertEquals(VoiceState.ToEditor("x", VoiceOutcome.Why.REFUSED, "{form}"), voiceStep(shown, VoiceInputEvent.Unwritten("x", VoiceOutcome.Why.REFUSED, "{form}")))
        // A guess that arrives after the sheet moved on is not shown.
        assertEquals(VoiceState.Listening(), voiceStep(VoiceState.Listening(), VoiceInputEvent.Proposed(reminder, "{form}", spoken, null)))
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
