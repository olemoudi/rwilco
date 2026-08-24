package dev.rwilco.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
    private val rules = ListSerializer(TriggerRule.serializer())
    private val conditions = ListSerializer(Condition.serializer())

    fun encodeTags(tags: List<String>): String = json.encodeToString(strings, tags)

    fun decodeTags(raw: String): List<String> =
        runCatching { json.decodeFromString(strings, raw) }.getOrDefault(emptyList())

    fun encodeRules(value: List<TriggerRule>): String = json.encodeToString(rules, value)

    /**
     * Element by element, so a rule of a kind this build does not know (or a corrupt one) is
     * skipped and the reminder survives with the rules it can still honour.
     *
     * Two shapes live in this column: a rule, and — from the builds before conditions existed —
     * a bare trigger. The `trigger` key is what tells them apart.
     */
    fun decodeRules(raw: String): List<TriggerRule> {
        val array = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrElse { return emptyList() }
        return array.mapNotNull { element ->
            runCatching {
                val obj = element as? JsonObject
                if (obj != null && obj.containsKey("trigger")) decodeRule(obj)
                else TriggerRule(json.decodeFromJsonElement(Trigger.serializer(), element))
            }.getOrNull()
        }
    }

    /**
     * A condition this build does not understand is dropped rather than taking the rule with it.
     * That errs towards ringing too often, which is the right way round for a reminder: the
     * failure somebody notices is the one that never arrives.
     */
    private fun decodeRule(obj: JsonObject): TriggerRule {
        val trigger = json.decodeFromJsonElement(Trigger.serializer(), obj.getValue("trigger"))
        val kept = (obj["conditions"] as? JsonArray).orEmpty().mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(Condition.serializer(), element) }.getOrNull()
        }
        return TriggerRule(trigger, kept)
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
