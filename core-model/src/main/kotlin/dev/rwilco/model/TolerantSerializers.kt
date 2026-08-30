package dev.rwilco.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.serializer
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder

/**
 * The settings are decoded all at once, and that is the whole problem.
 *
 * A reminder's own rules are read element by element (`ReminderCodec.decodeRules`), so a rule of
 * a kind this build has no word for is skipped and the reminder survives. A preset's rules live
 * inside the settings blob, where one unreadable trigger throws in the middle of the object and
 * takes **everything** with it — the theme, the sound, the saved places, every other preset —
 * because the read is caught wholesale and answered with the defaults.
 *
 * That is a real path, not a hypothetical: a vault restored on an older build, or a phone
 * downgraded. It costs a preset to drop a rule and costs the person their settings not to.
 */
object TolerantRules : KSerializer<List<TriggerRule>> {
    private val delegate = ListSerializer(TriggerRule.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    // Through the Json instance, not straight at the encoder: a hand-written serializer that
    // encodes a polymorphic value itself loses the format's class discriminator and writes the
    // array form (["after", {...}]) instead of {"type":"after", ...}.
    override fun serialize(encoder: Encoder, value: List<TriggerRule>) {
        val json = encoder as? JsonEncoder ?: return delegate.serialize(encoder, value)
        json.encodeJsonElement(json.json.encodeToJsonElement(delegate, value))
    }

    override fun deserialize(decoder: Decoder): List<TriggerRule> {
        val json = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val array = json.decodeJsonElement() as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching { json.json.decodeFromJsonElement(TriggerRule.serializer(), element) }.getOrNull()
        }
    }
}

/**
 * The same for a recurrence a build cannot read: it stops rather than guesses, which is what
 * `ReminderCodec.decodeRecurrence` already does for the reminder's own column.
 */
object TolerantRecurrence : KSerializer<Recurrence> {
    private val delegate = Recurrence.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Recurrence) {
        val json = encoder as? JsonEncoder ?: return delegate.serialize(encoder, value)
        json.encodeJsonElement(json.json.encodeToJsonElement(delegate, value))
    }

    override fun deserialize(decoder: Decoder): Recurrence {
        val json = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val element = json.decodeJsonElement()
        return runCatching { json.json.decodeFromJsonElement(delegate, element) }.getOrDefault(Recurrence.None)
    }
}

/**
 * A set of actions, element by element: one a build has no word for is dropped, not the
 * settings. Kept as names on the way in, because that is what the reminder's own column does
 * (`ReminderCodec.decodeActions`) and what an enum this build lacks has to be read as.
 */
object TolerantActions : KSerializer<Set<Action>> {
    private val delegate: KSerializer<Set<Action>> = serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Set<Action>) = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): Set<Action> {
        val json = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val array = json.decodeJsonElement() as? JsonArray ?: return emptySet()
        return array.mapNotNullTo(LinkedHashSet()) { element ->
            val name = runCatching { element.jsonPrimitive.content }.getOrNull()
            Action.entries.firstOrNull { it.name == name }
        }
    }
}

/**
 * A sound a build cannot read — a chime added later, a shape it never had — is the phone's
 * own alarm tone, which is what the app rings when a chosen file is gone too. Never the
 * whole settings.
 */
object TolerantSound : KSerializer<AlertSound> {
    private val delegate = AlertSound.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: AlertSound) {
        val json = encoder as? JsonEncoder ?: return delegate.serialize(encoder, value)
        json.encodeJsonElement(json.json.encodeToJsonElement(delegate, value))
    }

    override fun deserialize(decoder: Decoder): AlertSound {
        val json = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val element = json.decodeJsonElement()
        return runCatching { json.json.decodeFromJsonElement(delegate, element) }.getOrDefault(AlertSound.System)
    }
}

/** The same for the tone that may be unset: unreadable reads as unset, which is "the same as the other". */
object TolerantSoundOrNull : KSerializer<AlertSound?> {
    private val delegate = AlertSound.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: AlertSound?) {
        val json = encoder as? JsonEncoder ?: return if (value == null) encoder.encodeNull() else delegate.serialize(encoder, value)
        json.encodeJsonElement(if (value == null) JsonNull else json.json.encodeToJsonElement(delegate, value))
    }

    override fun deserialize(decoder: Decoder): AlertSound? {
        val json = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val element = json.decodeJsonElement()
        if (element is JsonNull) return null
        return runCatching { json.json.decodeFromJsonElement(delegate, element) }.getOrNull()
    }
}
