package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * What Gemini answers, read back through the same doors the grammar's readings go through
 * (0.178.0). The answers are written the way the schema makes Gemini write them.
 */
class GeminiReadingTest {

    private val casa = SavedPlace("Casa", 40.40, -3.70, 120, id = "p-casa")
    private val settings = AppSettings(savedPlaces = listOf(casa))
    private val fix = Fix(40.45, -3.65, 20.0, now)

    private fun heard(json: String, here: Fix? = null): Heard =
        geminiJson.decodeFromString(GeminiReading.serializer(), json).toHeard(now, zone, settings, here, "aquí", said = "lo dicho")

    private fun read(json: String, here: Fix? = null): Spoken = (heard(json, here) as? Heard.Read)?.spoken ?: throw AssertionError("not read: ${heard(json, here)}")

    @Test
    fun `a doorway into a saved place`() {
        val spoken = read("""{"text":"comprar pilas","match":"ANY","rules":[{"event":{"kind":"ARRIVE","place":"Casa"}}]}""")
        assertEquals("Comprar pilas", spoken.text)
        assertEquals(listOf(TriggerRule(Trigger.Location(40.40, -3.70, 120, Presence.INSIDE, "Casa", onCrossing = true, placeId = "p-casa"))), spoken.rules)
    }

    @Test
    fun `a moment, a fence, a set and how to be told`() {
        val spoken = read(
            """{"text":"Llamar a Ana","match":"TOGETHER","rules":[
                {"event":{"kind":"AT","date":"2026-08-28","time":"09:00"},"conditions":[{"kind":"AT_PLACE","place":"casa"}]},
                {"event":{"kind":"INSIDE","place":"Casa"}}],
              "deadline":{"kind":"BEFORE_TIME","time":"22:00"},
              "actions":{"fullScreen":true,"silent":true}}""",
        )
        assertEquals(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 9, 0)), spoken.rules[0].trigger)
        assertTrue(spoken.rules[0].conditions.single() is Condition.AtPlace, "a label is matched however it is cased")
        assertEquals(RuleMatch.TOGETHER, spoken.match)
        assertEquals(Deadline.Window(LocalTime.MIDNIGHT, LocalTime.of(22, 0)), spoken.deadline)
        assertEquals(setOf(Action.NOTIFICATION, Action.VIBRATE, Action.FULL_SCREEN), spoken.actions.applyTo(DEFAULT_ACTIONS))
    }

    @Test
    fun `a routine and a calendar`() {
        assertEquals(Recurrence.Since(3, RecurrenceUnit.WEEKS), read("""{"text":"mover el coche","match":"ANY","rules":[],"recurrence":{"kind":"SINCE_DONE","every":3,"unit":"WEEKS"}}""").recurrence)
        assertEquals(
            Recurrence.Calendar(Trigger.Repeat(startsOn = LocalDate.of(2027, 3, 3), unit = RepeatUnit.YEAR)),
            read("""{"text":"cumple de Ana","match":"ANY","rules":[],"recurrence":{"kind":"CALENDAR","every":1,"unit":"YEARS","startDate":"2027-03-03"}}""").recurrence,
        )
    }

    @Test
    fun `here needs a position, and is a circle round it once there is one`() {
        val json = """{"text":"sacar la basura","match":"ANY","rules":[{"event":{"kind":"LEAVE_HERE"}}]}"""
        assertEquals(Heard.NeedsHere("lo dicho"), heard(json))
        assertEquals(hereCircle(fix, "aquí"), read(json, here = fix).rules.single().trigger)
    }

    @Test
    fun `a place nobody saved, an hour that does not parse, or nothing at all is not a reading`() {
        assertEquals(Heard.Unsure("lo dicho", Heard.Why.PARTIAL), heard("""{"text":"x","match":"ANY","rules":[{"event":{"kind":"ARRIVE","place":"Mercadona"}}]}"""))
        assertEquals(Heard.Unsure("lo dicho", Heard.Why.PARTIAL), heard("""{"text":"x","match":"ANY","rules":[{"event":{"kind":"AT","date":"2026-08-28","time":"nine"}}]}"""))
        assertEquals(Heard.Unsure("lo dicho", Heard.Why.NOTHING), heard("""{"text":"x","match":"ANY","rules":[]}"""))
        assertEquals(Heard.Unsure("lo dicho", Heard.Why.NO_WORDS), heard("""{"text":"  ","match":"ANY","rules":[{"event":{"kind":"IN_MINUTES","minutes":20}}]}"""))
        // And anything it adds that this build does not know is ignored.
        assertEquals("X", read("""{"text":"x","match":"ANY","confidence":0.9,"rules":[{"event":{"kind":"IN_MINUTES","minutes":20,"why":"en 20 min"}}]}""").text)
    }

    @Test
    fun `a guess goes through the same gate as the grammar`() {
        val past = read("""{"text":"regar","match":"ANY","rules":[{"event":{"kind":"AT","date":"2026-08-26","time":"09:00"}}]}""")
        assertNull(past.toReminder("r", now, zone, settings))
    }

    @Test
    fun `the schema offers only the saved places, and the instruction says what time it is`() {
        val schema = geminiSchema(settings.geminiPlaces)
        val rules = schema["properties"]!!.jsonObject["rules"]!!.jsonObject["items"]!!.jsonObject
        val place = rules["properties"]!!.jsonObject["event"]!!.jsonObject["properties"]!!.jsonObject["place"]!!.jsonObject
        assertEquals(listOf("Casa"), (place["enum"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals("OBJECT", schema["type"]!!.jsonPrimitive.content)
        val instruction = geminiInstruction(now, zone, settings)
        assertTrue("2026-08-27 15:00" in instruction)
        assertTrue("\"Casa\"" in instruction)
        assertTrue("40.4" !in instruction, "no coordinates leave the phone")
        assertTrue("Europe/Madrid" !in instruction, "nor the time zone: the local time is enough")
        assertTrue("Friday 20:30" in instruction, "and where the weekend begins, as the local reader judges it")
        assertTrue(schema is JsonObject)
        assertTrue(schema["required"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun `only a sentence that might say when is worth asking about`() {
        assertEquals(false, mightSayWhen("comprar pan"))
        assertEquals(false, mightSayWhen("llamar a mi madre"))
        assertEquals(true, mightSayWhen("comprar pilas cuando acabe el partido"))
        assertEquals(true, mightSayWhen("felicitar a Ana en Navidad"))
        assertEquals(true, mightSayWhen("call mum after lunch"))
        assertEquals(true, mightSayWhen("pagar el 2"))
    }

    @Test
    fun `what the grammar refuses a guess may not write either`() {
        // A calendar beside a moment of its own, and a deadline one rule cannot carry.
        assertEquals(
            Heard.Unsure("lo dicho", Heard.Why.PARTIAL),
            heard("""{"text":"x","match":"ANY","rules":[{"event":{"kind":"AT","date":"2026-08-28","time":"09:00"}}],"recurrence":{"kind":"CALENDAR","every":1,"unit":"DAYS","time":"21:00"}}"""),
        )
        assertEquals(
            Heard.Unsure("lo dicho", Heard.Why.PARTIAL),
            heard("""{"text":"x","match":"ANY","rules":[{"event":{"kind":"IN_MINUTES","minutes":20}}],"deadline":{"kind":"WITHIN_MINUTES","minutes":30}}"""),
        )
    }
}
