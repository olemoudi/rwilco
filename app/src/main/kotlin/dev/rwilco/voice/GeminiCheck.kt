package dev.rwilco.voice

import dev.rwilco.model.AppSettings
import dev.rwilco.model.geminiInstruction
import dev.rwilco.model.geminiPlaces
import dev.rwilco.model.geminiSchema
import java.time.Instant
import java.time.ZoneId

/** What testing a key came to. */
enum class GeminiCheck {
    /** Gemini read the sample sentence: the key, the model and the answer's shape all work. */
    WORKS,

    /** The key was refused. */
    KEY_REFUSED,

    /** No answer, or none this build could read: offline, too many asks, a fault at Gemini's end. */
    NO_ANSWER,
}

/** A test of [key]: its result, or null while Gemini is still being asked. A key edited since has not been tested. */
data class GeminiCheckState(val key: String, val result: GeminiCheck?)

/**
 * Settings › Voice's "Probar la clave" (0.180.0): a sample sentence asked exactly as a dictation
 * would ask it — the same cleaned key, instruction, schema (the person's places in it) and models —
 * so a "works" means dictation will work, not merely that the key exists.
 */
suspend fun tryGeminiKey(raw: String, now: Instant, zone: ZoneId, settings: AppSettings, ask: AskGemini): GeminiCheck =
    when (ask(geminiKeyOf(raw), CHECK_SENTENCE, geminiInstruction(now, zone, settings), geminiSchema(settings.geminiPlaces))) {
        is GeminiAnswer.Reading -> GeminiCheck.WORKS
        GeminiAnswer.KeyRefused -> GeminiCheck.KEY_REFUSED
        GeminiAnswer.Failed -> GeminiCheck.NO_ANSWER
    }

/** What a test asks Gemini to read: a reminder anybody might say. */
internal const val CHECK_SENTENCE = "comprar pan mañana a las 9"
