package dev.rwilco.voice

import dev.rwilco.model.AppSettings
import dev.rwilco.model.Condition
import dev.rwilco.model.Heard
import dev.rwilco.model.Presence
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.SavedPlace
import dev.rwilco.model.Spoken
import dev.rwilco.model.Trigger
import dev.rwilco.model.hear
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * **What the app says, it understands** (0.177.0). The editor says every reminder back in one
 * sentence over "Guardar", and the notification says why it rang in the same words; somebody who
 * has read "al llegar a Casa, sólo si estoy en Oficina" a hundred times will say it that way. So
 * the sentence templates are read out of both `strings.xml`, filled in, said to [hear], and must
 * come back as the shape that would have printed them.
 */
class SpokenRoundTripTest {

    private val zone = ZoneId.of("Europe/Madrid")
    private val now: Instant = LocalDateTime.of(2026, 8, 27, 15, 0).atZone(zone).toInstant()
    private val casa = SavedPlace("Casa", 40.40, -3.70, 120, id = "p-casa")
    private val oficina = SavedPlace("Oficina", 40.41, -3.69, 150, id = "p-oficina")
    private val settings = AppSettings(savedPlaces = listOf(casa, oficina))

    private fun strings(relativePath: String): Map<String, String> {
        val file = sequenceOf(File(relativePath), File("app/$relativePath")).first { it.exists() }
        return STRING.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
    }

    private val languages = listOf(strings("src/main/res/values-es/strings.xml"), strings("src/main/res/values/strings.xml"))

    private fun Map<String, String>.say(name: String, vararg args: String): String {
        var template = getValue(name)
        args.forEachIndexed { index, arg -> template = template.replace("%${index + 1}\$s", arg) }
        return template
    }

    private fun read(text: String): Spoken =
        (hear(text, now, zone, settings) as? Heard.Read)?.spoken ?: throw AssertionError("'$text' was not read whole: ${hear(text, now, zone, settings)}")

    private fun place(spoken: Spoken, index: Int = 0) = spoken.rules[index].trigger as Trigger.Location

    @Test
    fun `the four readings of a place read back as themselves`() {
        for (words in languages) {
            fun check(name: String, presence: Presence, crossing: Boolean) {
                val said = "Regar " + words.say(name, "Casa")
                val rule = place(read(said))
                assertEquals(presence to crossing, rule.presence to rule.onCrossing, said)
                assertEquals("p-casa", rule.placeId, said)
            }
            check("trigger_arrive_at", Presence.INSIDE, crossing = true)
            check("trigger_leave_from", Presence.OUTSIDE, crossing = true)
            check("editor_sentence_place_inside", Presence.INSIDE, crossing = false)
            check("editor_sentence_place_outside", Presence.OUTSIDE, crossing = false)
        }
    }

    @Test
    fun `a doorway with its rate reads back with the rate`() {
        for (words in languages) {
            val said = "Regar " + words.say("trigger_arrive_at_after", "Casa", "10 min")
            assertEquals(10, place(read(said)).dwellMinutes, said)
        }
    }

    @Test
    fun `the joins the sentence uses read back as the sets they print`() {
        for (words in languages) {
            val arrive = words.say("trigger_arrive_at", "Casa")
            val leave = words.say("trigger_leave_from", "Oficina")
            val either = "Regar $arrive ${words.getValue("editor_sentence_or")} $leave"
            assertEquals(RuleMatch.ANY, read(either).match, either)
            assertEquals(2, read(either).rules.size, either)

            val inside = words.say("editor_sentence_place_inside", "Casa")
            val away = words.say("editor_sentence_place_outside", "Oficina")
            val atOnce = "Regar $inside ${words.getValue("editor_sentence_at_once")} $away"
            assertEquals(RuleMatch.TOGETHER, read(atOnce).match, atOnce)
        }
    }

    @Test
    fun `only and its fence read back as a fenced rule`() {
        for (words in languages) {
            val said = "Regar " + words.say("trigger_arrive_at", "Casa") + ", " + words.say("editor_sentence_only", words.say("editor_sentence_if_at", "Oficina"))
            val rule = read(said).rules.single()
            assertTrue(rule.conditions.single() is Condition.AtPlace, said)
            assertEquals("Oficina", (rule.conditions.single() as Condition.AtPlace).label, said)
        }
    }

    @Test
    fun `a deadline by an hour reads back`() {
        for (words in languages) {
            val said = "Regar " + words.say("trigger_arrive_at", "Casa") + " " + words.say("editor_sentence_deadline_window", "22:00")
            val rule = read(said).rules.single()
            assertTrue(rule.conditions.single() is Condition.TimeWindow, said)
        }
    }

    private companion object {
        val STRING = Regex("<string name=\"([a-z0-9_]+)\">(.*?)</string>")
    }
}
