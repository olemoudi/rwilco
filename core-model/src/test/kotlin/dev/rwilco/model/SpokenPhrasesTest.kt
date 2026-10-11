package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The ways people actually say a reminder to a phone (0.181.0), from a corpus of dictations run
 * through the grammar: each of these was either not understood, or — worse — understood by
 * halves and written with the rest of its "when" left in the words ("…y media", "Fichar todos").
 *
 * The clock is [Fixtures.now], Thursday 27 August 2026 at 15:00 in Madrid; the person's hours are
 * the defaults (up 8:00–23:30 on weekdays, 10:00–1:30 at weekends; morning 9, afternoon 17,
 * evening 20).
 */
class SpokenPhrasesTest {

    private val casa = SavedPlace("Casa", 40.40, -3.70, 120, id = "p-casa")
    private val trabajo = SavedPlace("Trabajo", 40.41, -3.69, 150, id = "p-trabajo")
    private val gimnasio = SavedPlace("Gimnasio", 40.42, -3.68, 80, id = "p-gym")
    private val mercadona = SavedPlace("Mercadona", 40.43, -3.67, 80, id = "p-merca")
    private val home = SavedPlace("Home", 51.50, -0.12, 100, id = "p-home")
    private val work = SavedPlace("Work", 51.51, -0.13, 100, id = "p-work")
    private val settings = AppSettings(savedPlaces = listOf(casa, trabajo, gimnasio, mercadona, home, work))
    private val today = LocalDate.of(2026, 8, 27)

    private fun heard(text: String) = hear(text, now, zone, settings, here = null, hereLabel = "aquí")

    private fun read(text: String): Heard.Read =
        heard(text) as? Heard.Read ?: throw AssertionError("'$text' was not read whole: ${heard(text)}")

    private fun once(text: String): Pair<String, Trigger> = read(text).spoken.let { spoken ->
        assertEquals(1, spoken.rules.size, "'$text' should be one rule: $spoken")
        assertEquals(emptyList<Condition>(), spoken.rules.single().conditions, "'$text' should have no fences: $spoken")
        assertEquals(Recurrence.None, spoken.recurrence, "'$text' should not come back: $spoken")
        spoken.text to spoken.rules.single().trigger
    }

    private fun comes(text: String): Pair<String, Recurrence> = read(text).spoken.let { spoken ->
        assertEquals(emptyList<TriggerRule>(), spoken.rules, "'$text' should be a calendar alone: $spoken")
        spoken.text to spoken.recurrence
    }

    private fun at(month: Int, day: Int, hour: Int, minute: Int = 0) = Trigger.AtDateTime(LocalDateTime.of(2026, month, day, hour, minute))
    private fun t(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)
    private fun tomorrow(time: LocalTime?) = Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.DAYS), time)
    private fun arrive(place: SavedPlace) = Trigger.Location(place.lat, place.lng, place.radiusM, Presence.INSIDE, place.label, onCrossing = true, placeId = place.id)
    private fun inside(place: SavedPlace) = arrive(place).copy(onCrossing = false)
    private fun leave(place: SavedPlace) = arrive(place).copy(presence = Presence.OUTSIDE)

    private fun calendar(startsOn: LocalDate, unit: RepeatUnit, time: LocalTime? = null, days: Set<DayOfWeek> = emptySet(), monthly: MonthlyOn? = null) =
        Recurrence.Calendar(Trigger.Repeat(startsOn = startsOn, every = 1, unit = unit, time = time, days = days, monthly = monthly))

    private val workdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    // --- a length with a fraction in it ---

    @Test
    fun `an hour and a half is ninety minutes, and the fraction goes with the length`() {
        assertEquals("Sacar la ropa" to Trigger.Countdown(90), once("sacar la ropa dentro de una hora y media"))
        assertEquals("Sacar la ropa" to Trigger.Countdown(150), once("sacar la ropa en 2 horas y media"))
        assertEquals("Mirar el horno" to Trigger.Countdown(75), once("mirar el horno en 1 hora y cuarto"))
        assertEquals("Mirar el horno" to Trigger.Countdown(90), once("mirar el horno en hora y media"))
        assertEquals("Mirar el horno" to Trigger.Countdown(165), once("mirar el horno dentro de 2 horas y tres cuartos"))
        assertEquals("Llamar a Luis" to Trigger.Countdown(45), once("llamar a Luis en tres cuartos de hora"))
        assertEquals("Call mom" to Trigger.Countdown(90), once("call mom in an hour and a half"))
        assertEquals("Call mom" to Trigger.Countdown(150), once("call mom in 2 and a half hours"))
    }

    // --- an hour said the way people say one ---

    @Test
    fun `quarter to the hour is the hour before`() {
        // An open nine: tonight's 20:45 is the next to come round, and both halves are awake.
        assertEquals("Llamar a Juan" to at(8, 27, 20, 45), once("llamar a Juan a las 9 menos cuarto"))
        assertEquals("Llamar a Juan" to at(8, 27, 20, 45), once("llamar a Juan a las nueve menos cuarto"))
        assertEquals("Llamar a Juan" to at(8, 28, 8, 45), once("llamar a Juan a las 9 menos cuarto de la mañana"))
        assertEquals("Llamar a Juan" to at(8, 27, 20, 50), once("llamar a Juan a las 9 menos diez"))
    }

    @Test
    fun `the words around an hour that only say it is an hour go with it`() {
        assertEquals("Llamar a Juan" to at(8, 27, 21), once("llamar a Juan a las 21 horas"))
        assertEquals("Llamar a Juan" to at(8, 27, 20), once("llamar a Juan a las 8 en punto"))
        assertEquals("Llamar a Juan" to at(8, 27, 17), once("llamar a Juan sobre las 5"))
        assertEquals("Llamar a Juan" to at(8, 27, 17), once("llamar a Juan hacia las 5"))
        assertEquals("Llamar a Juan" to at(8, 27, 17), once("llamar a Juan a eso de las 5"))
        assertEquals("Llamar a Juan" to at(8, 27, 17), once("llamar a Juan alrededor de las 5"))
        assertEquals("Call mom" to at(8, 27, 17), once("call mom at around 5"))
    }

    @Test
    fun `midnight is the next one`() {
        assertEquals("Llamar a Luis" to at(8, 28, 0), once("llamar a Luis a medianoche"))
        assertEquals("Call Luis" to at(8, 28, 0), once("call Luis at midnight"))
    }

    @Test
    fun `quarter past, half past and quarter to in English`() {
        // Seven fifteen in the morning is before getting up: the evening's.
        assertEquals("Call mom" to at(8, 27, 19, 15), once("call mom at quarter past 7"))
        assertEquals("Call mom" to at(8, 27, 18, 30), once("call mom at half past 6"))
        assertEquals("Call mom" to at(8, 27, 20, 45), once("call mom at quarter to 9"))
    }

    @Test
    fun `first thing and early are the morning's hour of the day they are said with`() {
        assertEquals("Llamar al banco" to tomorrow(t(9)), once("llamar al banco mañana a primera hora"))
        assertEquals("Llamar al banco" to at(8, 28, 9), once("llamar al banco a primera hora"))
        assertEquals("Llamar a Juan" to tomorrow(t(9)), once("llamar a Juan mañana temprano"))
        assertEquals("Ir al mercado" to Trigger.RelativeDate(RelativeDay.NextWeekday(DayOfWeek.SATURDAY), t(9)), once("ir al mercado el sábado temprano"))
        assertEquals("Call the bank" to tomorrow(t(9)), once("call the bank first thing tomorrow"))
        // "Temprano" on its own is how, not when: "salir temprano" is somebody's plan.
        assertEquals("Salir temprano del trabajo" to tomorrow(null), once("salir temprano del trabajo mañana"))
    }

    // --- days ---

    @Test
    fun `a weekday with its day of the month is one date`() {
        assertEquals("Dentista" to Trigger.DayRandom(LocalDate.of(2026, 9, 14)), once("el lunes 14 dentista"))
        assertEquals("Dentista" to Trigger.DayRandom(LocalDate.of(2026, 9, 14)), once("el lunes día 14 dentista"))
        assertEquals("Dentista" to Trigger.DayRandom(LocalDate.of(2026, 9, 1)), once("el martes 1 de septiembre dentista"))
        assertEquals("Dentista" to at(9, 14, 10), once("el lunes 14 a las 10 dentista"))
        // The 14th that is a Wednesday is October's.
        assertEquals("Dentista" to Trigger.DayRandom(LocalDate.of(2026, 10, 14)), once("el miércoles 14 dentista"))
        assertEquals("Dentist" to Trigger.DayRandom(LocalDate.of(2026, 9, 14)), once("dentist on monday the 14th"))
    }

    @Test
    fun `a weekday and a day of the month that never meet soon are left for a person`() {
        assertEquals(Heard.Unsure("el domingo 14 dentista", Heard.Why.NOTHING), heard("el domingo 14 dentista"))
    }

    @Test
    fun `the end of the month is its last day`() {
        assertEquals("Pagar la tarjeta" to Trigger.DayRandom(LocalDate.of(2026, 8, 31)), once("pagar la tarjeta a final de mes"))
        assertEquals("Pagar la tarjeta" to Trigger.DayRandom(LocalDate.of(2026, 8, 31)), once("pagar la tarjeta a fin de mes"))
        assertEquals("Pagar la tarjeta" to at(8, 31, 10), once("pagar la tarjeta a final de mes a las 10"))
        assertEquals("Pay the card" to Trigger.DayRandom(LocalDate.of(2026, 8, 31)), once("pay the card at the end of the month"))
    }

    @Test
    fun `a month named alone is the whole of it, or what is left of it`() {
        assertEquals("Apuntarme al gimnasio" to Trigger.DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), once("apuntarme al gimnasio en septiembre"))
        assertEquals("Renovar el pasaporte" to Trigger.DateRange(today, LocalDate.of(2026, 8, 31)), once("renovar el pasaporte en agosto"))
        assertEquals("Renovar el pasaporte" to Trigger.DateRange(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 31)), once("renovar el pasaporte en marzo"))
        assertEquals("Pagar el IBI" to Trigger.DateRange(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 30)), once("pagar el IBI a finales de septiembre"))
        assertEquals("Pagar el IBI" to Trigger.DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10)), once("pagar el IBI a principios de octubre"))
        assertEquals("Renew the passport" to Trigger.DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), once("renew the passport in september"))
    }

    @Test
    fun `today said on its own, out loud, is what is left of today`() {
        assertEquals("Llamar a la tienda" to Trigger.DayRandom(today), once("llamar a la tienda hoy"))
        val fenced = read("echar gasolina cuando salga del trabajo hoy").spoken
        assertEquals(leave(trabajo), fenced.rules.single().trigger)
        assertEquals(listOf(Trigger.DayRandom(today).asState(settings.dayShape)), fenced.rules.single().conditions)
    }

    @Test
    fun `the weekend, singular, is the coming one`() {
        assertEquals("Llamar a la abuela" to Trigger.DateRange(LocalDate.of(2026, 8, 29), LocalDate.of(2026, 8, 30)), once("llamar a la abuela el fin de semana"))
    }

    // --- what comes back ---

    @Test
    fun `the first or last weekday of every month`() {
        assertEquals(
            "Reunión de equipo" to calendar(LocalDate.of(2026, 9, 7), RepeatUnit.MONTH, monthly = MonthlyOn.Nth(1, DayOfWeek.MONDAY)),
            comes("reunión de equipo el primer lunes de cada mes"),
        )
        assertEquals(
            "Pagar el alquiler" to calendar(LocalDate.of(2026, 8, 28), RepeatUnit.MONTH, monthly = MonthlyOn.Nth(-1, DayOfWeek.FRIDAY)),
            comes("pagar el alquiler el último viernes de cada mes"),
        )
        assertEquals(
            "Team meeting" to calendar(LocalDate.of(2026, 9, 7), RepeatUnit.MONTH, t(10), monthly = MonthlyOn.Nth(1, DayOfWeek.MONDAY)),
            comes("team meeting the first monday of every month at 10am"),
        )
    }

    @Test
    fun `every year and every month said with todos`() {
        assertEquals("Felicitar a Ana" to calendar(LocalDate.of(2027, 3, 3), RepeatUnit.YEAR), comes("felicitar a Ana todos los años el 3 de marzo"))
        assertEquals("Revisar la caldera", comes("revisar la caldera todos los meses").first)
    }

    @Test
    fun `twice a day is two draws a day`() {
        assertEquals("Take pills" to Trigger.Random(2, Period.DAY, t(10), t(20)), read("take pills twice a day").spoken.let { it.text to it.rules.single().trigger })
    }

    @Test
    fun `a day of every month`() {
        assertEquals("Pagar la comunidad" to calendar(LocalDate.of(2026, 9, 1), RepeatUnit.MONTH, monthly = MonthlyOn.Day(1)), comes("pagar la comunidad el día 1 de cada mes"))
        assertEquals("Pagar la comunidad" to calendar(LocalDate.of(2026, 9, 1), RepeatUnit.MONTH, t(10), monthly = MonthlyOn.Day(1)), comes("pagar la comunidad el 1 de cada mes a las 10"))
        assertEquals("Pay rent" to calendar(LocalDate.of(2026, 9, 1), RepeatUnit.MONTH, monthly = MonthlyOn.Day(1)), comes("pay rent on the 1st of every month"))
    }

    @Test
    fun `working days and weekends with an hour are a weekly series on those days`() {
        val clockIn = calendar(LocalDate.of(2026, 8, 28), RepeatUnit.WEEK, t(8), workdays)
        assertEquals("Fichar" to clockIn, comes("fichar todos los días laborables a las 8"))
        assertEquals("Fichar" to clockIn, comes("fichar entre semana a las 8"))
        assertEquals("Fichar" to clockIn, comes("fichar de lunes a viernes a las 8"))
        assertEquals("Take out the trash" to clockIn, comes("take out the trash every weekday at 8"))
        assertEquals("Fichar" to calendar(today, RepeatUnit.WEEK, days = workdays), comes("fichar de lunes a viernes"))
        assertEquals("Desayunar" to calendar(LocalDate.of(2026, 8, 29), RepeatUnit.WEEK, t(10), weekend), comes("desayunar los fines de semana a las 10"))
        assertEquals("Regar" to calendar(LocalDate.of(2026, 8, 29), RepeatUnit.WEEK, days = weekend), comes("regar todos los fines de semana"))
    }

    @Test
    fun `a part of the day said in the plural is every day at its hour`() {
        assertEquals("Tomar la pastilla" to calendar(today, RepeatUnit.DAY, t(20)), comes("tomar la pastilla todas las noches"))
        assertEquals("Tomar la pastilla" to calendar(today, RepeatUnit.DAY, t(22)), comes("tomar la pastilla todas las noches a las 10"))
        assertEquals("Estirar" to calendar(today.plusDays(1), RepeatUnit.DAY, t(9)), comes("estirar cada mañana"))
        assertEquals("Water the plants" to calendar(today.plusDays(1), RepeatUnit.DAY, t(9)), comes("water the plants every morning"))
    }

    // --- getting up and going to bed, by the person's own hours ---

    @Test
    fun `getting up is the hour the person gets up on that day`() {
        assertEquals("Tomar la pastilla" to at(8, 28, 8), once("tomar la pastilla al levantarme"))
        assertEquals("Tomar la pastilla" to at(8, 28, 8), once("tomar la pastilla cuando me levante"))
        assertEquals("Hacer estiramientos" to at(8, 28, 8), once("mañana cuando me levante hacer estiramientos"))
        // A Saturday's lie-in is the weekend's.
        assertEquals("Regar" to at(8, 29, 10), once("el sábado al levantarme regar"))
        assertEquals("Stretch" to at(8, 28, 8), once("when I wake up, stretch"))
    }

    @Test
    fun `going to bed is half an hour before the person's bedtime`() {
        assertEquals("Tomar la pastilla" to at(8, 27, 23), once("tomar la pastilla antes de acostarme"))
        // Friday night is the weekend's, and its bedtime is past midnight.
        assertEquals("Tomar la pastilla" to at(8, 29, 1), once("tomar la pastilla el viernes antes de acostarme"))
        assertEquals("Take my pills" to at(8, 27, 23), once("take my pills before bed"))
        assertEquals("Tomar la pastilla" to calendar(today, RepeatUnit.DAY, t(23)), comes("tomar la pastilla todas las noches antes de acostarme"))
    }

    // --- places ---

    @Test
    fun `going to a place, or passing by it, is arriving there`() {
        assertEquals("Comprar pan" to arrive(mercadona), once("comprar pan cuando vaya al Mercadona"))
        assertEquals("Comprar pan" to arrive(mercadona), once("comprar pan cuando pase por el Mercadona"))
        assertEquals("Comprar pan" to arrive(casa), once("comprar pan cuando esté cerca de casa"))
        assertEquals("Llevar la toalla" to arrive(gimnasio), once("llevar la toalla cuando vaya al gimnasio"))
        assertEquals("Buy milk" to arrive(work), once("buy milk when I go to work"))
        assertEquals("Buy milk" to arrive(home), once("buy milk when I'm near home"))
    }

    @Test
    fun `next time is part of saying where`() {
        assertEquals("Comprar pan" to arrive(mercadona), once("comprar pan la próxima vez que vaya al Mercadona"))
        assertEquals("Comprar pan" to inside(mercadona), once("comprar pan la próxima vez que esté en el Mercadona"))
        assertEquals("Buy milk" to inside(home), once("buy milk next time I'm at home"))
    }

    // --- how a sentence to a phone starts ---

    @Test
    fun `do not let me forget is how a sentence starts, not what to remember`() {
        assertEquals("Comprar pan", read("que no se me olvide comprar pan mañana").spoken.text)
        assertEquals("Comprar pan", read("no te olvides de comprar pan mañana").spoken.text)
        assertEquals("Comprar pan", read("no me dejes olvidar comprar pan mañana").spoken.text)
        assertEquals("Buy bread", read("don't forget to buy bread tomorrow").spoken.text)
    }

    // --- what the sheet is told about a reading ---

    @Test
    fun `a reading says where in the sentence each when was said`() {
        val sentence = "recuérdame comprar pan cuando llegue a casa"
        val parts = read(sentence).parts
        assertEquals("cuando llegue a casa", parts.single { it.family == TriggerFamily.PLACE }.range.let { sentence.substring(it.first, it.last + 1) })
        assertEquals("recuérdame", parts.single { it.family == null }.range.let { sentence.substring(it.first, it.last + 1) })
        val timed = "mañana a las 9, llamar al médico"
        assertEquals(
            listOf("mañana", "a las 9"),
            read(timed).parts.filter { it.family == TriggerFamily.TIME }.map { timed.substring(it.range.first, it.range.last + 1) }.sortedBy { timed.indexOf(it) },
        )
    }

    @Test
    fun `words left that sound like part of a when are doubts`() {
        val sentence = "llamar al banco mañana antes de comer"
        val reading = read(sentence)
        assertEquals("Llamar al banco antes de comer", reading.spoken.text)
        assertEquals(listOf("antes de"), reading.doubts.map { sentence.substring(it.first, it.last + 1) })
        // The reading is still the reading: tomorrow.
        assertEquals(tomorrow(null), reading.spoken.rules.single().trigger)
        val border = "reunión de equipo el primer martes"
        assertEquals("Reunión de equipo el primer", read(border).spoken.text)
        assertEquals(listOf("primer"), read(border).doubts.map { border.substring(it.first, it.last + 1) })
    }

    @Test
    fun `a clean reading has no doubts`() {
        listOf(
            "comprar pan cuando llegue a casa",
            "llamar a Marta mañana por la tarde",
            "comprar regalo mañana para todos",
            "limpiar el cuarto mañana",
            "pedir hora en el médico el lunes",
            "llamar a Juan mañana y a María",
            "fichar entre semana a las 8",
        ).forEach { assertEquals(emptyList<IntRange>(), read(it).doubts, it) }
    }

    @Test
    fun `an open hour whose other half is also awake is offered the other way`() {
        val tonight = read("llamar a Juan a las 8")
        assertEquals(listOf(TriggerRule(at(8, 27, 20))), tonight.spoken.rules)
        assertEquals(listOf(listOf(TriggerRule(at(8, 28, 8)))), tonight.alternatives.map { it.rules })
        assertEquals("Llamar a Juan", tonight.alternatives.single().text)
        val dinner = read("el jueves a las 9 cena con los amigos")
        assertEquals(Trigger.RelativeDate(RelativeDay.NextWeekday(DayOfWeek.THURSDAY), t(21)), dinner.alternatives.single().rules.single().trigger)
        val pills = read("tomar la pastilla cada día a las 8")
        assertEquals(calendar(today.plusDays(1), RepeatUnit.DAY, t(8)), pills.spoken.recurrence)
        assertEquals(calendar(today, RepeatUnit.DAY, t(20)), pills.alternatives.single().recurrence)
    }

    @Test
    fun `an open hour whose other half is asleep is not offered`() {
        assertEquals(emptyList<Spoken>(), read("llamar a Juan a las 5").alternatives)
        assertEquals(emptyList<Spoken>(), read("llamar a Juan a las 17").alternatives)
        assertEquals(emptyList<Spoken>(), read("comprar pan cuando llegue a casa").alternatives)
    }

    @Test
    fun `every phrase here writes a reminder that can ring`() {
        val sentences = listOf(
            "sacar la ropa dentro de una hora y media", "llamar a Juan a las 9 menos cuarto", "llamar a Luis a medianoche",
            "el lunes 14 dentista", "pagar la tarjeta a final de mes", "apuntarme al gimnasio en septiembre", "llamar a la tienda hoy",
            "reunión de equipo el primer lunes de cada mes", "pagar la comunidad el día 1 de cada mes", "fichar entre semana a las 8",
            "tomar la pastilla todas las noches", "tomar la pastilla al levantarme", "tomar la pastilla antes de acostarme",
            "comprar pan cuando vaya al Mercadona", "echar gasolina cuando salga del trabajo hoy",
        )
        sentences.forEach { sentence ->
            val reminder = read(sentence).spoken.toReminder("r", now, zone, settings)
            assertTrue(reminder != null, "'$sentence' would not be written: ${read(sentence).spoken}")
        }
    }
}
