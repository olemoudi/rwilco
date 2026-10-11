package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Sentences with more than one "when" in them (0.177.0): how the clauses come together into
 * rules, by the owner's readings of 2026-10-11. The clock is [Fixtures.now], Thursday 27 August
 * 2026 at 15:00 in Madrid.
 */
class SpokenCompositionTest {

    private val casa = SavedPlace("Casa", 40.40, -3.70, 120, id = "p-casa")
    private val oficina = SavedPlace("Oficina", 40.41, -3.69, 150, id = "p-oficina")
    private val gimnasio = SavedPlace("Gimnasio", 40.42, -3.68, 80, id = "p-gimnasio")
    private val home = SavedPlace("Home", 51.50, -0.12, 100, id = "p-home")
    private val comida = SavedWindow("a la hora de comer", LocalTime.of(13, 30), LocalTime.of(15, 30))
    private val settings = AppSettings(savedPlaces = listOf(casa, oficina, gimnasio, home), savedWindows = listOf(comida))
    private val fix = Fix(40.45, -3.65, 20.0, now)

    private fun heard(text: String, here: Fix? = null) = hear(text, now, zone, settings, here, hereLabel = "aquí")

    private fun read(text: String, here: Fix? = null): Spoken =
        (heard(text, here) as? Heard.Read)?.spoken ?: throw AssertionError("'$text' was not read whole: ${heard(text, here)}")

    private fun unsure(text: String): Heard.Why =
        (heard(text) as? Heard.Unsure)?.why ?: throw AssertionError("'$text' was not left for a person: ${heard(text)}")

    private fun t(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)
    private fun at(day: Int, hour: Int, minute: Int = 0) = Trigger.AtDateTime(LocalDateTime.of(2026, 8, day, hour, minute))
    private fun arrive(place: SavedPlace) = Trigger.Location(place.lat, place.lng, place.radiusM, Presence.INSIDE, place.label, onCrossing = true, placeId = place.id)
    private fun leave(place: SavedPlace) = arrive(place).copy(presence = Presence.OUTSIDE)
    private fun inside(place: SavedPlace) = arrive(place).copy(onCrossing = false)
    private fun atPlace(place: SavedPlace) = Condition.AtPlace(place.lat, place.lng, place.radiusM, place.label, inside = true, placeId = place.id)

    // --- cualquiera ---

    @Test
    fun `either of them is or`() {
        val spoken = read("llamar a Ana a las 9 o al llegar a casa")
        assertEquals("Llamar a Ana", spoken.text)
        assertEquals(listOf(TriggerRule(at(27, 21)), TriggerRule(arrive(casa))), spoken.rules)
        assertEquals(RuleMatch.ANY, spoken.match)
    }

    @Test
    fun `what is said beside one side of an or fences that side`() {
        val spoken = read("regar a las 9 o al llegar a casa por la tarde")
        assertEquals(listOf(TriggerRule(at(27, 21)), TriggerRule(arrive(casa), listOf(Condition.TimeWindow(t(17), t(20))))), spoken.rules)
        assertEquals(RuleMatch.ANY, spoken.match)
    }

    @Test
    fun `two moments joined by and ring twice, which is either`() {
        // The 9 alone would be tonight's — which the 21 already is — so it is the other nine.
        val spoken = read("la pastilla a las 9 y a las 21")
        assertEquals("La pastilla", spoken.text)
        assertEquals(listOf(TriggerRule(at(28, 9)), TriggerRule(at(27, 21))), spoken.rules)
        assertEquals(RuleMatch.ANY, spoken.match)
    }

    @Test
    fun `whichever comes first says either in so many words`() {
        assertEquals(RuleMatch.ANY, read("llamar a Ana a las 9 o al llegar a casa, lo que pase antes").match)
    }

    // --- one rule, fenced ---

    @Test
    fun `a doorway with a part of the day beside it is the doorway, fenced by the hours`() {
        val spoken = read("comprar pan al llegar a casa por la tarde")
        assertEquals("Comprar pan", spoken.text)
        assertEquals(listOf(TriggerRule(arrive(casa), listOf(Condition.TimeWindow(t(17), t(20))))), spoken.rules)
    }

    @Test
    fun `only if fences the rule before it`() {
        assertEquals(
            listOf(TriggerRule(arrive(casa), listOf(Condition.TimeWindow(t(18), t(22))))),
            read("comprar pan al llegar a casa y sólo si es entre las 18 y las 22").rules,
        )
        assertEquals(listOf(TriggerRule(at(27, 20), listOf(atPlace(casa)))), read("llamar a mamá a las 8 sólo si estoy en casa").rules)
        assertEquals(listOf(TriggerRule(arrive(casa), listOf(Condition.OnDays(setOf(DayOfWeek.FRIDAY))))), read("llamar a mamá al llegar a casa sólo si es viernes").rules)
    }

    @Test
    fun `a saved stretch of the day is read by its name`() {
        assertEquals(listOf(TriggerRule(Trigger.Interval(t(13, 30), t(15, 30)))), read("comer fruta a la hora de comer").rules)
        assertEquals(
            listOf(TriggerRule(arrive(casa), listOf(Condition.TimeWindow(t(13, 30), t(15, 30))))),
            read("sacar la carne al llegar a casa a la hora de comer").rules,
        )
    }

    @Test
    fun `a rate and a speed belong to the doorway said before them`() {
        assertEquals(listOf(TriggerRule(arrive(casa).copy(dwellMinutes = 10))), read("comprar pan al llegar a casa y cuando lleve 10 minutos allí").rules)
        assertEquals(
            listOf(TriggerRule(leave(oficina), listOf(movingOf(MovingKind.DRIVING)))),
            read("llamar a Juan al salir de la oficina si voy en coche").rules,
        )
        assertEquals(Heard.Why.PARTIAL, unsure("llamar a Juan mañana si voy en coche"))
    }

    @Test
    fun `days of the month beside a place fence it`() {
        assertEquals(listOf(TriggerRule(arrive(casa), listOf(Condition.OnMonthDays(setOf(1, 15))))), read("pagar el alquiler al llegar a casa los días 1 y 15").rules)
    }

    // --- a la vez ---

    @Test
    fun `two states joined by and are true at once`() {
        val spoken = read("regar cuando esté en casa y sea fin de semana")
        assertEquals("Regar", spoken.text)
        assertEquals(listOf(TriggerRule(inside(casa)), TriggerRule(Trigger.Weekday(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)))), spoken.rules)
        assertEquals(RuleMatch.TOGETHER, spoken.match)
    }

    @Test
    fun `at the same time says it in so many words`() {
        val spoken = read("regar cuando esté en casa a la vez que es por la tarde")
        assertEquals("Regar", spoken.text)
        assertEquals(RuleMatch.TOGETHER, spoken.match)
        assertEquals(2, spoken.rules.size)
        // Two moments at once never coincide: read, and refused before it is written.
        val never = read("regar al llegar a casa y a las 9, a la vez")
        assertEquals(RuleMatch.TOGETHER, never.match)
        assertNull(never.toReminder("r", now, zone, settings))
    }

    // --- todos ---

    @Test
    fun `both of them says all`() {
        val spoken = read("llamar al banco a las 9 y al llegar a casa, las dos cosas")
        assertEquals("Llamar al banco", spoken.text)
        assertEquals(RuleMatch.ALL, spoken.match)
        assertEquals(listOf(TriggerRule(at(27, 21)), TriggerRule(arrive(casa))), spoken.rules)
    }

    // --- plazos ---

    @Test
    fun `a deadline bounds a set, and beside one rule it is a fence`() {
        val set = read("regar cuando esté en casa y sea fin de semana antes de las 22")
        assertEquals(Deadline.Window(LocalTime.MIDNIGHT, t(22)), set.deadline)
        assertEquals(RuleMatch.TOGETHER, set.match)
        val one = read("regar al llegar a casa antes de las 22")
        assertNull(one.deadline)
        assertEquals(listOf(TriggerRule(arrive(casa), listOf(Condition.TimeWindow(LocalTime.MIDNIGHT, t(22))))), one.rules)
        assertEquals(Heard.Why.PARTIAL, unsure("regar antes de las 22"))
    }

    @Test
    fun `a set's deadline is written with its close`() {
        val written = read("regar cuando esté en casa y sea fin de semana antes de las 22").toReminder("r", now, zone, settings)
        assertEquals(Deadline.Window(LocalTime.MIDNIGHT, t(22)), written?.deadline)
    }

    // --- the other kinds ---

    @Test
    fun `chance, stretches of the day, and a repeat fenced`() {
        val water = read("beber agua tres veces al día entre las 10 y las 20")
        assertEquals("Beber agua", water.text)
        assertEquals(listOf(TriggerRule(Trigger.Random(3, Period.DAY, t(10), t(20)))), water.rules)
        assertEquals(Recurrence.ByTrigger, water.recurrence)
        assertEquals(listOf(TriggerRule(Trigger.Interval(t(17), t(19)))), read("estirar entre las 17 y las 19").rules)
        assertEquals(listOf(TriggerRule(Trigger.Interval(t(17), t(19)))), read("estirar de 5 a 7").rules)
        val bins = read("sacar la basura todos los lunes a las 21 sólo si estoy en casa")
        assertEquals("Sacar la basura", bins.text)
        assertEquals(emptyList<TriggerRule>(), bins.rules)
        assertEquals(
            Recurrence.Calendar(Trigger.Repeat(startsOn = LocalDate.of(2026, 8, 31), unit = RepeatUnit.WEEK, time = t(21), days = setOf(DayOfWeek.MONDAY)), listOf(atPlace(casa))),
            bins.recurrence,
        )
    }

    @Test
    fun `aqui joins like any other doorway`() {
        val spoken = read("sacar la basura al salir de aquí o a las 21", here = fix)
        assertEquals(listOf(TriggerRule(hereCircle(fix, "aquí")), TriggerRule(at(27, 21))), spoken.rules)
        assertEquals(Heard.NeedsHere("sacar la basura al salir de aquí o a las 21"), heard("sacar la basura al salir de aquí o a las 21"))
    }

    // --- how to be told ---

    @Test
    fun `how to be told is said with the rest and changes the person's defaults`() {
        val loud = read("despertar a los niños mañana a las 7 con pantalla completa")
        assertEquals("Despertar a los niños", loud.text)
        assertEquals(DEFAULT_ACTIONS + Action.FULL_SCREEN, loud.toReminder("r", now, zone, settings)?.actions)
        val insist = read("tomar la pastilla a las 21 que suene hasta que conteste").toReminder("r", now, zone, settings)?.actions
        assertEquals(setOf(Action.NOTIFICATION, Action.SOUND_UNTIL_ANSWERED, Action.VIBRATE), insist)
        assertEquals(setOf(Action.NOTIFICATION, Action.VIBRATE), read("regar mañana sin sonido").toReminder("r", now, zone, settings)?.actions)
        assertEquals(setOf(Action.NOTIFICATION), read("regar mañana, solo una notificación").toReminder("r", now, zone, settings)?.actions)
    }

    // --- what saying it out loud adds ---

    @Test
    fun `a place said with only its preposition is said the way the one before was`() {
        val spoken = read("comprar pilas cuando esté en el gimnasio o en la oficina")
        assertEquals("Comprar pilas", spoken.text)
        assertEquals(listOf(TriggerRule(inside(gimnasio)), TriggerRule(inside(oficina))), spoken.rules)
        assertEquals(listOf(TriggerRule(arrive(casa)), TriggerRule(arrive(oficina))), read("llamar a Juan cuando llegue a casa o a la oficina").rules)
    }

    @Test
    fun `until a day is the days from today to it`() {
        assertEquals(listOf(TriggerRule(Trigger.DateRange(LocalDate.of(2026, 8, 27), LocalDate.of(2026, 8, 28)))), read("comprar el regalo hasta el viernes").rules)
        assertEquals(listOf(TriggerRule(Trigger.DateRange(LocalDate.of(2026, 8, 27), LocalDate.of(2026, 9, 14)))), read("renovar el abono antes del 15").rules)
    }

    @Test
    fun `a stretch of days with a part of the day is both at once`() {
        val spoken = read("llamar al fontanero la semana que viene por la mañana")
        assertEquals("Llamar al fontanero", spoken.text)
        assertEquals(listOf(TriggerRule(Trigger.DateRange(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 6))), TriggerRule(Trigger.Interval(t(9), t(12)))), spoken.rules)
        assertEquals(RuleMatch.TOGETHER, spoken.match)
        val study = read("estudiar entre las 18 y las 20 entre semana")
        assertEquals("Estudiar", study.text)
        assertEquals(RuleMatch.TOGETHER, study.match)
    }

    @Test
    fun `a calendar and the date said with it is where the calendar starts`() {
        val birthday = read("recordar el cumpleaños de Ana el 3 de marzo cada año")
        assertEquals("Recordar el cumpleaños de Ana", birthday.text)
        assertEquals(Recurrence.Calendar(Trigger.Repeat(startsOn = LocalDate.of(2027, 3, 3), unit = RepeatUnit.YEAR)), birthday.recurrence)
        // Two hours a day are no calendar's: not written by halves.
        assertEquals(Heard.Why.PARTIAL, unsure("tomar la pastilla a las 9 y a las 21 todos los días"))
    }

    @Test
    fun `a rate needs no cuando of its own, and a kind of day beside a doorway fences it`() {
        assertEquals(listOf(TriggerRule(arrive(oficina).copy(dwellMinutes = 15))), read("revisar el correo cuando llegue a la oficina y lleve 15 minutos").rules)
        assertEquals(
            listOf(TriggerRule(arrive(casa), listOf(Condition.OnDays(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))))),
            read("poner la lavadora al llegar a casa si es fin de semana").rules,
        )
    }

    // --- what the second review round found (0.177.0) ---

    @Test
    fun `until a day keeps its month, and before today's day is next month's`() {
        assertEquals("Renovar el DNI" to listOf(TriggerRule(Trigger.DateRange(LocalDate.of(2026, 8, 27), LocalDate.of(2027, 3, 15)))),
            read("renovar el DNI hasta el 15 de marzo").let { it.text to it.rules })
        assertEquals(listOf(TriggerRule(Trigger.DateRange(LocalDate.of(2026, 8, 27), LocalDate.of(2026, 9, 26)))), read("pagar antes del 27").rules)
    }

    @Test
    fun `a day of the month said once is a date, not a fence`() {
        assertEquals("Comprar el regalo" to listOf(TriggerRule(Trigger.DayRandom(LocalDate.of(2027, 3, 5)))), read("comprar el regalo el día 5 de marzo").let { it.text to it.rules })
        assertEquals(listOf(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 9, 15, 9, 0)))), read("pagar el seguro el día 15 a las 9").rules)
    }

    @Test
    fun `a span whose end has no month ends in the month that follows`() {
        assertEquals(listOf(TriggerRule(Trigger.DateRange(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 11, 5)))), read("viajar del 20 de octubre al 5").rules)
        assertEquals(listOf(TriggerRule(Trigger.DateRange(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 11, 5)))), read("viajar del 20 al 5 de noviembre").rules)
    }

    @Test
    fun `words that are the thing itself are not how to be told`() {
        val phone = read("poner el móvil en silencio a las 23")
        assertEquals("Poner el móvil en silencio", phone.text)
        assertEquals(DEFAULT_ACTIONS, phone.toReminder("r", now, zone, settings)?.actions)
        assertEquals("Ser insistente con el banco", read("ser insistente con el banco mañana").text)
    }

    @Test
    fun `a small word beside the when alone stays`() {
        assertEquals("Fix it", read("fix it tomorrow").text)
        assertEquals("Send it", read("send it at 5pm").text)
    }

    @Test
    fun `an hour with its pm and no space is still an hour of a deadline or a window`() {
        assertEquals(Heard.Why.PARTIAL, unsure("water the plants before 10pm"))
        assertEquals(listOf(TriggerRule(arrive(home), listOf(Condition.TimeWindow(LocalTime.MIDNIGHT, t(22))))), read("water the plants when I get home before 10pm").rules)
        assertEquals(listOf(TriggerRule(Trigger.Interval(t(18), t(20)))), read("stretch between 6pm and 8pm").rules)
    }

    @Test
    fun `joins that are part of the thing itself stay in the words`() {
        assertEquals("Tomar las dos pastillas al mismo tiempo", read("tomar las dos pastillas al mismo tiempo a las 9").text)
        assertEquals("Llamar antes del partido", read("llamar antes del partido a las 9").text)
    }

    // --- what the final review round found (0.179.0) ---

    @Test
    fun `pm written with dots is still the afternoon`() {
        assertEquals("Call mom" to listOf(TriggerRule(Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.DAYS), t(17)))), read("remind me to call mom tomorrow at 5 p.m.").let { it.text to it.rules })
        assertEquals("Llamar a mamá" to listOf(TriggerRule(Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.DAYS), t(17)))), read("llamar a mamá mañana a las 5 p. m.").let { it.text to it.rules })
    }

    @Test
    fun `somebody else's house is not the place called Casa`() {
        assertTrue(heard("comprar vino cuando llegue a casa de Juan") is Heard.Unsure)
        assertTrue(heard("llevar el regalo cuando llegue a casa de mi madre") is Heard.Unsure)
        assertEquals(listOf(TriggerRule(arrive(casa))), read("poner la lavadora cuando llegue a casa de noche").rules.map { it.copy(conditions = emptyList()) }.take(1))
    }

    @Test
    fun `an English part of the day qualifies the hours of a stretch`() {
        assertEquals(listOf(TriggerRule(Trigger.Interval(t(17), t(19)))), read("call mom between 5 and 7 in the evening").rules)
        assertEquals(listOf(TriggerRule(Trigger.Random(3, Period.DAY, t(9), t(21)))), read("drink water three times a day between 9 in the morning and 9 in the evening").rules)
    }

    @Test
    fun `a stretch with no qualifier is read as a whole, never overnight by accident`() {
        // Seven to eight in the morning is before the default getting-up (8:00): the waking one is the evening's.
        assertEquals(listOf(TriggerRule(Trigger.Interval(t(19), t(20)))), read("llamar a mamá entre las 7 y las 8").rules)
        assertEquals(listOf(TriggerRule(Trigger.Interval(t(10), t(14)))), read("estudiar de 10 a 2").rules)
        assertEquals(listOf(TriggerRule(Trigger.Interval(t(17), t(19)))), read("estirar de 5 a 7").rules)
    }

    @Test
    fun `numbers that count something are not hours or days`() {
        assertEquals("Hacer de 10 a 15 flexiones", read("hacer de 10 a 15 flexiones mañana").text)
        assertEquals("Comprar entre 2 y 3 kilos de naranjas", read("comprar entre 2 y 3 kilos de naranjas mañana").text)
        assertEquals("Estudiar los temas del 1 al 5", read("estudiar los temas del 1 al 5 mañana").text)
    }

    @Test
    fun `a day left out, or the day after said alone, is not written by halves`() {
        assertEquals(Heard.Why.PARTIAL, unsure("tomar la pastilla todos los días menos el domingo"))
        assertEquals(Heard.Why.PARTIAL, unsure("take the pills every day except sunday at 9"))
        assertEquals(Heard.Why.PARTIAL, unsure("comprar pan mañana o pasado"))
    }

    @Test
    fun `two moments said side by side with no join are two answers`() {
        assertEquals(Heard.Why.PARTIAL, unsure("comprar pan cuando llegue a casa a las 7"))
        // With a join they ring twice, as before.
        assertEquals(RuleMatch.ANY, read("comprar pan al llegar a casa y a las 7").match)
    }

    @Test
    fun `days on the calendar joined by and are either of them`() {
        val spoken = read("regar las plantas el lunes y el jueves")
        assertEquals("Regar las plantas", spoken.text)
        assertEquals(
            listOf(TriggerRule(Trigger.RelativeDate(RelativeDay.NextWeekday(DayOfWeek.MONDAY))), TriggerRule(Trigger.RelativeDate(RelativeDay.NextWeekday(DayOfWeek.THURSDAY)))),
            spoken.rules,
        )
        assertEquals(RuleMatch.ANY, spoken.match)
    }

    // --- the waking-hours rule, reviewed (0.179.0) ---

    @Test
    fun `an overnight stretch said in full stays overnight`() {
        assertEquals(listOf(TriggerRule(Trigger.Interval(t(22), t(2)))), read("estudiar de 22 a 2").rules)
    }

    @Test
    fun `one and twelve are never open`() {
        val fridayLunch = Fixtures.local(2026, 8, 28, 11, 0)
        val heardThen = hear("llamar a la una", fridayLunch, zone, settings)
        assertEquals(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 13, 0)), (heardThen as Heard.Read).spoken.rules.single().trigger)
        val noon = hear("llamar a las 12", fridayLunch, zone, settings)
        assertEquals(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 12, 0)), (noon as Heard.Read).spoken.rules.single().trigger)
    }

    @Test
    fun `the same rule twice with no other reading to turn to is left for a person`() {
        assertEquals(Heard.Why.PARTIAL, unsure("la pastilla hoy a las 9 y a las 21"))
    }

    @Test
    fun `a weekly calendar's open hour is judged on the day it rings`() {
        // Saturdays get up at ten by default: a waking nine that day is the evening's, said once or every week.
        assertEquals(listOf(TriggerRule(Trigger.RelativeDate(RelativeDay.NextWeekday(DayOfWeek.SATURDAY), t(21)))), read("regar el sábado a las 9").rules)
        assertEquals(t(21), (read("regar cada sábado a las 9").recurrence as Recurrence.Calendar).repeat.time)
    }

    @Test
    fun `a clock with its leading zero is never moved`() {
        assertEquals(listOf(TriggerRule(Trigger.Interval(t(7), t(8)))), read("llamar de 07:00 a 08:00").rules)
        val deadline = read("regar al llegar a casa antes de las 09:00").rules.single().conditions.single()
        assertEquals(Condition.TimeWindow(LocalTime.MIDNIGHT, t(9)), deadline)
    }

    @Test
    fun `an open hour that truly agrees with its calendar folds into it`() {
        val early = Fixtures.local(2026, 8, 27, 7, 0)
        val spoken = (hear("la pastilla a las 9 y todos los días a las 9", early, zone, settings) as Heard.Read).spoken
        assertEquals(t(9), (spoken.recurrence as Recurrence.Calendar).repeat.time)
        assertEquals(emptyList<TriggerRule>(), spoken.rules)
    }

    // --- more than one reading ---

    @Test
    fun `or and and in one sentence is more than one reading`() {
        assertEquals(Heard.Why.PARTIAL, unsure("regar a las 9 o al llegar a casa y a las 21"))
    }

    @Test
    fun `a join with no when on one side joins words, not clauses`() {
        val spoken = read("comprar pan y leche cuando llegue a casa")
        assertEquals("Comprar pan y leche", spoken.text)
        assertEquals(listOf(TriggerRule(arrive(casa))), spoken.rules)
        assertEquals("Llamar a Juan", read("mañana llamar a Juan a las 9").text)
    }

    @Test
    fun `a fence nobody understood is not dropped`() {
        assertEquals(Heard.Why.PARTIAL, unsure("comprar pan al llegar a casa y sólo si llueve"))
    }
}
