package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A reminder said out loud: what is written, what is left for a person, and why.
 *
 * The owner's own five sentences come first (2026-10-11). The clock is [Fixtures.now]: Thursday
 * 27 August 2026, 15:00 in Madrid.
 */
class SpokenTest {

    private val casa = SavedPlace("Casa", 40.40, -3.70, 120, id = "p-casa")
    private val home = SavedPlace("Home", 51.50, -0.12, 100, id = "p-home")
    private val settings = AppSettings(savedPlaces = listOf(casa, home))
    private val fix = Fix(40.45, -3.65, 20.0, now)
    private val today = LocalDate.of(2026, 8, 27)

    private fun heard(text: String, here: Fix? = null) = hear(text, now, zone, settings, here, hereLabel = "aquí")

    private fun read(text: String, here: Fix? = null): Spoken =
        (heard(text, here) as? Heard.Read)?.spoken ?: throw AssertionError("'$text' was not read whole: ${heard(text, here)}")

    private fun unsure(text: String): Heard.Why =
        (heard(text) as? Heard.Unsure)?.why ?: throw AssertionError("'$text' was not left for a person: ${heard(text)}")

    private fun once(text: String): Pair<String, Trigger> = read(text).let { it.text to it.rules.single().trigger }

    private fun arrive(place: SavedPlace) = Trigger.Location(place.lat, place.lng, place.radiusM, Presence.INSIDE, place.label, onCrossing = true, placeId = place.id)

    // --- the owner's five ---

    @Test
    fun `when I get home is the doorway into the place called Casa`() {
        assertEquals("Comprar pan" to arrive(casa), once("comprar pan cuando llegue a casa"))
    }

    @Test
    fun `while I am at home is the state`() {
        assertEquals("Regar las plantas" to arrive(casa).copy(onCrossing = false), once("regar las plantas mientras estoy en casa"))
    }

    @Test
    fun `sometime this week is what is left of it`() {
        assertEquals("Llamar al banco" to Trigger.DateRange(today, LocalDate.of(2026, 8, 30)), once("llamar al banco en algún momento de esta semana"))
    }

    @Test
    fun `tomorrow afternoon is tomorrow at the afternoon's hour`() {
        assertEquals("Llamar a Marta" to Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.DAYS), LocalTime.of(17, 0)), once("llamar a Marta mañana por la tarde"))
    }

    @Test
    fun `when I leave here needs a position, and is a circle round it once there is one`() {
        val asked = heard("sacar la basura cuando me vaya de aquí")
        assertEquals(Heard.NeedsHere("sacar la basura cuando me vaya de aquí"), asked)
        val spoken = read("sacar la basura cuando me vaya de aquí", here = fix)
        assertEquals("Sacar la basura", spoken.text)
        assertEquals(hereCircle(fix, "aquí"), spoken.rules.single().trigger)
        assertEquals("Sacar la basura", read("al salir de aquí, sacar la basura", here = fix).text)
    }

    // --- the words that are left ---

    @Test
    fun `how a sentence to a phone starts is not part of what to remember`() {
        assertEquals("Compre pan", read("Recuérdame que compre pan cuando llegue a casa").text)
        assertEquals("Llamar a Ana", read("recuérdame de llamar a Ana mañana").text)
        assertEquals("Llamar a Ana", read("avísame mañana a las 9 de llamar a Ana").text)
        assertEquals("Call mum", read("remind me to call mum when I get home").text)
        assertEquals("Llamar al fontanero", read("pon un recordatorio para llamar al fontanero el lunes").text)
    }

    @Test
    fun `the when may come first, last or around the words`() {
        assertEquals("Llamar al médico" to Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.DAYS), LocalTime.of(9, 0)), once("Mañana a las 9, llamar al médico"))
        assertEquals("Llamar a Juan" to Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.DAYS), LocalTime.of(9, 0)), once("mañana llamar a Juan a las 9"))
        assertEquals("Regar" to arrive(casa), once("Al llegar a casa, regar"))
    }

    @Test
    fun `the small words that tied the when to the sentence go with it`() {
        assertEquals("Comprar flores" to Trigger.RelativeDate(RelativeDay.NextWeekday(DayOfWeek.SATURDAY)), once("comprar flores para el sábado"))
        assertEquals("Ir a la reunión", read("ir a la reunión de mañana").text)
        assertEquals("Hablar con el jefe", read("hablar con el jefe el próximo lunes").text)
        assertEquals("Comprar pan", read("Comprar pan, mañana.").text)
        // And the ones that belong to the thing stay.
        assertEquals("Llamar a José", read("Llamar a José el miércoles").text)
        assertEquals("Comprar pan de molde", read("comprar pan de molde mañana").text)
    }

    @Test
    fun `a repeat and a length are written as such`() {
        val pills = read("pastillas cada día a las 9")
        assertEquals("Pastillas", pills.text)
        assertEquals(emptyList<TriggerRule>(), pills.rules)
        assertEquals(Recurrence.Calendar(Trigger.Repeat(startsOn = today.plusDays(1), unit = RepeatUnit.DAY, time = LocalTime.of(9, 0))), pills.recurrence)
        assertEquals("Sacar el pan del horno" to Trigger.Countdown(20), once("sacar el pan del horno en 20 minutos"))
    }

    // --- what the review round found (0.176.0) ---

    @Test
    fun `the words after a weekday that are part of saying it go with it`() {
        assertEquals("Comprar pan" to Trigger.RelativeDate(RelativeDay.NextWeekday(DayOfWeek.MONDAY)), once("comprar pan el lunes que viene"))
        assertEquals("Comprar pan" to Trigger.RelativeDate(RelativeDay.NextWeekday(DayOfWeek.MONDAY)), once("comprar pan el lunes próximo"))
    }

    @Test
    fun `the morning said beside an hour is part of the hour`() {
        val nine = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 9, 0))
        assertEquals("Llamar a Ana" to nine, once("llamar a Ana a las 9 por la mañana"))
        assertEquals("Call mom" to nine, once("call mom at 9 in the morning"))
        assertEquals("Pastillas", read("pastillas todos los días a las 8 por la mañana").text)
    }

    @Test
    fun `how a sentence starts does not take the start of the thing with it`() {
        assertEquals("A mi madre llamar", read("recuérdame a mi madre llamar mañana").text)
        assertEquals("Call mum", read("remind me at 9 to call mum").text)
        assertEquals(Heard.Why.PARTIAL, unsure("recuérdame a las 9 llamar cuando llegue al curro"))
        assertEquals(Heard.Unsure("A las 9 llamar cuando llegue al curro", Heard.Why.PARTIAL), heard("recuérdame a las 9 llamar cuando llegue al curro"))
    }

    @Test
    fun `a reading carries the sentence without its start, for when it cannot be written after all`() {
        assertEquals("Comprar pan mañana", (heard("recuérdame comprar pan mañana") as Heard.Read).said)
    }

    // --- a routine said out loud ---

    private fun routine(text: String): Pair<String, Recurrence> = read(text).let {
        assertEquals(emptyList<TriggerRule>(), it.rules, "a routine has no rules of its own here")
        it.text to it.recurrence
    }

    @Test
    fun `a routine counts its span from the last time it was done`() {
        assertEquals("Mover el coche" to Recurrence.Since(3, RecurrenceUnit.WEEKS), routine("rutina: mover el coche cada 3 semanas"))
        assertEquals("Regar las plantas" to Recurrence.Since(5, RecurrenceUnit.DAYS), routine("regar las plantas cada 5 días desde la última vez"))
        assertEquals("Cambiar el filtro" to Recurrence.Since(2, RecurrenceUnit.MONTHS), routine("cambiar el filtro cada 2 meses desde que lo haga"))
        assertEquals("Tomar la pastilla" to Recurrence.Since(8, RecurrenceUnit.HOURS), routine("Nueva rutina: tomar la pastilla cada 8 horas"))
        assertEquals("Water the plants" to Recurrence.Since(4, RecurrenceUnit.DAYS), routine("new routine water the plants every 4 days"))
        assertEquals("Mover el coche" to Recurrence.Since(3, RecurrenceUnit.WEEKS), routine("apunta una rutina: mover el coche cada 3 semanas"))
    }

    @Test
    fun `a routine with no span is the week the editor opens one with`() {
        assertEquals("Cambiar las sábanas" to Recurrence.Since(1, RecurrenceUnit.WEEKS), routine("rutina cambiar las sábanas"))
    }

    @Test
    fun `what a routine cannot hold is left for a person`() {
        assertEquals(Heard.Why.PARTIAL, unsure("rutina: regar los lunes"))
        assertEquals(Heard.Why.PARTIAL, unsure("rutina: regar mañana"))
        assertEquals(Heard.Why.PARTIAL, unsure("rutina: regar cada día a las 9"))
        assertEquals(Heard.Why.NO_WORDS, unsure("rutina cada 3 días"))
        // "Rutina" in the middle of a sentence is a word like any other.
        assertEquals("Mi rutina de estiramientos" to Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.DAYS)), once("mi rutina de estiramientos mañana"))
    }

    @Test
    fun `a routine is told about the way routines are`() {
        val mine = settings.copy(routineActions = setOf(Action.NOTIFICATION))
        val written = read("rutina: mover el coche cada 3 semanas").toReminder("r", now, zone, mine)
        assertEquals(setOf(Action.NOTIFICATION), written?.actions)
        assertEquals(true, written?.isRoutine)
    }

    // --- what is left for a person ---

    @Test
    fun `no when at all is nothing to write`() {
        assertEquals(Heard.Why.NOTHING, unsure("llamar a mi madre"))
        // A place nobody saved is no place.
        assertEquals(Heard.Why.NOTHING, unsure("comprar pan cuando llegue al curro"))
        assertEquals(Heard.Unsure("Llamar a mi madre", Heard.Why.NOTHING), heard("recuérdame llamar a mi madre"))
    }

    @Test
    fun `more when than was understood is not written by halves`() {
        assertEquals(Heard.Why.PARTIAL, unsure("mañana cuando llegue a casa comprar pan"))
        assertEquals(Heard.Why.PARTIAL, unsure("beber agua cada 8 horas a las 9"))
        assertEquals(Heard.Why.PARTIAL, unsure("sacar la basura cuando me vaya de aquí mañana"))
        assertEquals(Heard.Why.PARTIAL, unsure("regar esta semana cuando llegue al curro"))
    }

    @Test
    fun `a when and nothing else leaves no reminder`() {
        assertEquals(Heard.Why.NO_WORDS, unsure("mañana a las 9"))
        assertEquals(Heard.Why.NO_WORDS, unsure("recuérdame cuando llegue a casa"))
    }

    // --- written ---

    @Test
    fun `a reading is settled the way a one-tap preset is`() {
        val countdown = read("sacar el pan en 20 minutos").toReminder("r", now, zone, settings)
        assertEquals(Trigger.Countdown(20, startedAt = now), countdown?.rules?.single()?.trigger)
        val tomorrow = read("llamar a Marta mañana por la tarde").toReminder("r", now, zone, settings)
        assertEquals(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 17, 0)), tomorrow?.rules?.single()?.trigger)
        assertEquals("Llamar a Marta", tomorrow?.text)
        assertEquals(settings.defaultActions, tomorrow?.actions)
        assertEquals(now, tomorrow?.createdAt)
        val today = read("regar en algún momento de hoy").toReminder("r", now, zone, settings)?.rules?.single()?.trigger as Trigger.DayRandom
        assertEquals(LocalTime.of(15, 1), today.window?.from, "a day left to the day is narrowed to what is left of it")
    }

    @Test
    fun `the person's own actions are the ones written`() {
        val mine = settings.copy(defaultActions = setOf(Action.FULL_SCREEN, Action.SOUND))
        assertEquals(setOf(Action.FULL_SCREEN, Action.SOUND), read("regar mañana").toReminder("r", now, zone, mine)?.actions)
    }

    @Test
    fun `anything the editor would warn about is not written unseen`() {
        assertNull(Spoken("Regar", listOf(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 26, 9, 0))))).toReminder("r", now, zone, settings))
        assertNull(Spoken("   ", listOf(TriggerRule(Trigger.Countdown(20)))).toReminder("r", now, zone, settings))
        assertNotNull(Spoken("Regar", listOf(TriggerRule(Trigger.Countdown(20)))).toReminder("r", now, zone, settings))
    }

    @Test
    fun `stripping works on the words as given, accents, commas and double spaces included`() {
        assertEquals("Café con Ana", withoutSpans("Café con Ana  mañana", listOf(14..19)))
        assertEquals("Llamar", withoutSpans("llamar, mañana", listOf(8..13)))
    }
}
