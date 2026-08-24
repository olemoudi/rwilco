package dev.rwilco.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

/**
 * The on-disk JSON for the parts of a reminder that Room stores as text columns, and for the
 * settings blob. Reading is lenient by construction: a build that meets JSON from a newer build
 * keeps everything it understands and drops the rest, never the whole row.
 */
object ReminderCodec {

    val json: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val strings = ListSerializer(String.serializer())
    private val triggers = ListSerializer(Trigger.serializer())

    fun encodeTags(tags: List<String>): String = json.encodeToString(strings, tags)

    fun decodeTags(raw: String): List<String> =
        runCatching { json.decodeFromString(strings, raw) }.getOrDefault(emptyList())

    fun encodeTriggers(value: List<Trigger>): String = json.encodeToString(triggers, value)

    /**
     * Element by element: a trigger of a kind this build does not know (or a corrupt one) is
     * skipped, and the reminder survives with the triggers it can still honour.
     */
    fun decodeTriggers(raw: String): List<Trigger> {
        val array = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrElse { return emptyList() }
        return array.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(Trigger.serializer(), element) }.getOrNull()
        }
    }

    fun encodeActions(actions: Set<Action>): String = json.encodeToString(strings, actions.map { it.name })

    /** Unknown action names are dropped, in case a newer build added one. */
    fun decodeActions(raw: String): Set<Action> {
        val names = runCatching { json.decodeFromString(strings, raw) }.getOrDefault(emptyList())
        return names.mapNotNullTo(LinkedHashSet()) { name -> Action.entries.firstOrNull { it.name == name } }
    }

    fun encodeSettings(settings: AppSettings): String = json.encodeToString(AppSettings.serializer(), settings)

    /** A blob that does not parse yields the defaults rather than a crash on launch. */
    fun decodeSettings(raw: String): AppSettings =
        runCatching { json.decodeFromString(AppSettings.serializer(), raw) }.getOrDefault(AppSettings())
}
