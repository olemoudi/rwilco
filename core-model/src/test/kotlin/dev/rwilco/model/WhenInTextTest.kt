package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The "when" read out of the words.
 *
 * Every case is a phrase somebody might actually type into the text of a reminder, and the
 * answer is the shape the editor already knows how to hold — never a new one. The clock is
 * [Fixtures.now]: Thursday 27 August 2026, 15:00 in Madrid, so "a las 9" is tomorrow and
 * "a las 21" is today, "el jueves" is next week's and "el 26" is September's.
 */
class WhenInTextTest {

    private val today: LocalDate = LocalDate.of(2026, 8, 27)

    private fun read(text: String): Understood? = whenInText(text, now, zone)

    private fun once(text: String): Trigger = (read(text) as? Understood.Once)?.trigger
        ?: throw AssertionError("'$text' was not read as a single moment: ${read(text)}")

    private fun comes(text: String): Recurrence = (read(text) as? Understood.Comes)?.recurrence
        ?: throw AssertionError("'$text' was not read as something that comes back: ${read(text)}")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0) =
        Trigger.AtDateTime(LocalDateTime.of(year, month, day, hour, minute))

    private fun t(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)

    private fun tomorrow(time: LocalTime? = null) = Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.DAYS), time)

    private fun weekly(startsOn: LocalDate, day: DayOfWeek, time: LocalTime?) = Recurrence.Calendar(
        Trigger.Repeat(startsOn = startsOn, every = 1, unit = RepeatUnit.WEEK, time = time, days = setOf(day)),
    )

    private fun daily(startsOn: LocalDate, time: LocalTime?, every: Int = 1) = Recurrence.Calendar(
        Trigger.Repeat(startsOn = startsOn, every = every, unit = RepeatUnit.DAY, time = time),
    )

    // --- a length ---

    @Test
    fun `a length in minutes or hours is a countdown`() {
        assertEquals(Trigger.Countdown(20), once("sacar el pan del horno en 20 min"))
        assertEquals(Trigger.Countdown(20), once("en 20 minutos"))
        assertEquals(Trigger.Countdown(120), once("llamar en 2 horas"))
        assertEquals(Trigger.Countdown(120), once("en 2 h"))
        assertEquals(Trigger.Countdown(30), once("en media hora"))
        assertEquals(Trigger.Countdown(60), once("en una hora"))
        assertEquals(Trigger.Countdown(15), once("en un cuarto de hora"))
        assertEquals(Trigger.Countdown(45), once("dentro de 45 minutos"))
        assertEquals(Trigger.Countdown(20), once("in 20 min"))
        assertEquals(Trigger.Countdown(30), once("in half an hour"))
        assertEquals(Trigger.Countdown(60), once("in an hour"))
        assertEquals(Trigger.Countdown(180), once("in 3 hours"))
        assertEquals(Trigger.Countdown(10), once("call back in ten minutes"))
    }

    @Test
    fun `a length ignores an hour written beside it`() {
        assertEquals(Trigger.Countdown(20), once("en 20 min a las 9"))
    }

    // --- a day counted, with or without an hour ---

    @Test
    fun `tomorrow with an hour`() {
        assertEquals(tomorrow(t(9)), once("regar mañana a las 9"))
        assertEquals(tomorrow(t(9)), once("Regar MAÑANA a las 9"))
        assertEquals(tomorrow(t(9, 30)), once("mañana a las 9:30"))
        assertEquals(tomorrow(t(9, 30)), once("mañana a las 9 y media"))
        assertEquals(tomorrow(t(9, 15)), once("mañana a las 9 y cuarto"))
        assertEquals(tomorrow(t(21)), once("mañana a las 9 de la noche"))
        assertEquals(tomorrow(t(16)), once("mañana a las 4 de la tarde"))
        assertEquals(tomorrow(t(9)), once("tomorrow at 9"))
        assertEquals(tomorrow(t(21)), once("tomorrow at 9pm"))
        assertEquals(tomorrow(t(21, 30)), once("tomorrow at 9:30 pm"))
        assertEquals(tomorrow(t(9)), once("tomorrow at nine"))
    }

    @Test
    fun `tomorrow without an hour is the day itself`() {
        assertEquals(tomorrow(), once("comprar pan mañana"))
        assertEquals(tomorrow(), once("para mañana"))
        assertEquals(tomorrow(), once("tomorrow"))
    }

    @Test
    fun `the morning and the night are hours the app already uses`() {
        assertEquals(tomorrow(t(9)), once("mañana por la mañana"))
        assertEquals(tomorrow(t(20)), once("mañana por la noche"))
        assertEquals(tomorrow(t(9)), once("tomorrow morning"))
        assertEquals(tomorrow(t(20)), once("tomorrow evening"))
        assertEquals(tomorrow(t(20)), once("tomorrow night"))
    }

    @Test
    fun `the day after tomorrow and days counted`() {
        // Saturday: the default weekend gets up at ten, so a waking eight that day is the evening's.
        assertEquals(Trigger.RelativeDate(RelativeDay.In(2, RelativeUnit.DAYS), t(20)), once("pasado mañana a las 8"))
        assertEquals(Trigger.RelativeDate(RelativeDay.In(2, RelativeUnit.DAYS), null), once("day after tomorrow"))
        assertEquals(Trigger.RelativeDate(RelativeDay.In(3, RelativeUnit.DAYS), null), once("en 3 días"))
        assertEquals(Trigger.RelativeDate(RelativeDay.In(3, RelativeUnit.DAYS), null), once("in 3 days"))
        assertEquals(Trigger.RelativeDate(RelativeDay.In(2, RelativeUnit.WEEKS), t(12)), once("dentro de 2 semanas a las 12"))
        assertEquals(Trigger.RelativeDate(RelativeDay.In(2, RelativeUnit.WEEKS), null), once("in 2 weeks"))
        assertEquals(Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.MONTHS), null), once("en un mes"))
        assertEquals(Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.MONTHS), null), once("in a month"))
    }

    @Test
    fun `a weekday is the next one, strictly after today`() {
        assertEquals(Trigger.RelativeDate(RelativeDay.NextWeekday(DayOfWeek.THURSDAY), t(17, 30)), once("dentista el jueves 17:30"))
        assertEquals(Trigger.RelativeDate(RelativeDay.NextWeekday(DayOfWeek.MONDAY), null), once("el lunes"))
        assertEquals(Trigger.RelativeDate(RelativeDay.NextWeekday(DayOfWeek.MONDAY), t(10)), once("el próximo lunes a las 10"))
        assertEquals(Trigger.RelativeDate(RelativeDay.NextWeekday(DayOfWeek.WEDNESDAY), null), once("este miércoles"))
        assertEquals(Trigger.RelativeDate(RelativeDay.NextWeekday(DayOfWeek.SATURDAY), t(11)), once("el sábado a las 11"))
        assertEquals(Trigger.RelativeDate(RelativeDay.NextWeekday(DayOfWeek.MONDAY), t(10)), once("next monday at 10"))
        assertEquals(Trigger.RelativeDate(RelativeDay.NextWeekday(DayOfWeek.FRIDAY), null), once("on friday"))
        assertEquals(Trigger.RelativeDate(RelativeDay.NextWeekday(DayOfWeek.SUNDAY), t(18)), once("sunday at 6pm"))
    }

    // --- a date pointed at ---

    @Test
    fun `a day of the month is the next such day`() {
        assertEquals(at(2026, 9, 26, 20), once("el 26 a las 20"))
        assertEquals(Trigger.DayRandom(LocalDate.of(2026, 8, 31)), once("el 31"))
        assertEquals(Trigger.DayRandom(LocalDate.of(2026, 8, 27)), once("el 27"))
        assertEquals(Trigger.DayRandom(LocalDate.of(2026, 9, 26)), once("on the 26th"))
        assertEquals(at(2026, 9, 1, 9), once("on the 1st at 9"))
    }

    @Test
    fun `a day with its month, this year or the next`() {
        assertEquals(Trigger.DayRandom(LocalDate.of(2026, 9, 3)), once("el 3 de septiembre"))
        assertEquals(at(2026, 9, 3, 10), once("el 3 de septiembre a las 10"))
        assertEquals(Trigger.DayRandom(LocalDate.of(2027, 8, 26)), once("el 26 de agosto"))
        assertEquals(Trigger.DayRandom(LocalDate.of(2027, 8, 26)), once("26/8"))
        assertEquals(at(2026, 12, 24, 18), once("el 24/12 a las 18"))
        assertEquals(Trigger.DayRandom(LocalDate.of(2026, 9, 3)), once("september 3"))
        assertEquals(at(2026, 9, 3, 10), once("3rd of september at 10"))
        assertEquals(Trigger.DayRandom(LocalDate.of(2026, 8, 30)), once("aug 30"))
        assertEquals(Trigger.DayRandom(LocalDate.of(2026, 10, 12)), once("12 october"))
    }

    // --- an hour alone, or today ---

    @Test
    fun `an hour alone is the next waking one to come round`() {
        // At 15:00, the next nine o'clock is tonight's: both nines are waking hours, so the one
        // that comes first (the owner's rule, 2026-10-11).
        assertEquals(at(2026, 8, 27, 21), once("a las 9"))
        assertEquals(at(2026, 8, 27, 21), once("a las 21"))
        assertEquals(at(2026, 8, 27, 21, 30), once("a las 9 y media"))
        assertEquals(at(2026, 8, 27, 21), once("a las 9 de la noche"))
        assertEquals(at(2026, 8, 28, 13), once("a la una"))
        assertEquals(at(2026, 8, 27, 21), once("a las nueve"))
        assertEquals(at(2026, 8, 27, 17, 30), once("17:30"))
        assertEquals(at(2026, 8, 28, 12), once("al mediodía"))
        assertEquals(at(2026, 8, 27, 21), once("at 9"))
        assertEquals(at(2026, 8, 27, 21, 30), once("at 9:30"))
        assertEquals(at(2026, 8, 27, 17), once("at 5pm"))
        assertEquals(at(2026, 8, 27, 17), once("5pm"))
        assertEquals(at(2026, 8, 27, 17), once("5 pm"))
        assertEquals(at(2026, 8, 28, 12), once("at noon"))
    }

    @Test
    fun `the morning or the night alone`() {
        assertEquals(at(2026, 8, 28, 9), once("por la mañana"))
        assertEquals(at(2026, 8, 27, 20), once("por la noche"))
        assertEquals(at(2026, 8, 28, 9), once("in the morning"))
    }

    @Test
    fun `tonight is today at eight, or the hour it names`() {
        assertEquals(at(2026, 8, 27, 20), once("esta noche"))
        assertEquals(at(2026, 8, 27, 22), once("esta noche a las 22"))
        assertEquals(at(2026, 8, 27, 20), once("tonight"))
        assertEquals(at(2026, 8, 27, 22), once("tonight at 10pm"))
    }

    @Test
    fun `today needs an hour that is still ahead`() {
        assertEquals(at(2026, 8, 27, 18), once("hoy a las 18"))
        assertEquals(at(2026, 8, 27, 18), once("today at 6pm"))
        assertNull(read("hoy"))
        // Nine this morning has gone; nine tonight has not.
        assertEquals(at(2026, 8, 27, 21), once("hoy a las 9"))
        assertEquals(at(2026, 8, 27, 21), once("today at 9"))
        assertNull(read("hoy a las 9 de la mañana"))
    }

    // --- a bare hour is a waking one (2026-10-11) ---

    @Test
    fun `a bare hour is the one in waking hours, by the person's own hours`() {
        // "A las 5" said at 15:00 is this afternoon, never five tomorrow morning.
        assertEquals(at(2026, 8, 27, 17), once("llamar a mamá a las 5"))
        assertEquals(tomorrow(t(17)), once("mañana a las 5"))
        // With a day, the hour as said when it is a waking one.
        assertEquals(tomorrow(t(9)), once("mañana a las 9"))
        // Somebody up at six reads their seven as the morning's.
        val early = DayShape(hours = AwakeHours(wake = t(6), sleep = t(23)))
        assertEquals(daily(LocalDate.of(2026, 8, 28), t(7)), (whenInText("a diario a las 7", now, zone, shape = early) as Understood.Comes).recurrence)
        // Said with its half of the day, it is never moved.
        assertEquals(tomorrow(t(5)), once("mañana a las 5 de la mañana"))
        assertEquals(at(2026, 8, 28, 5), once("at 5am"))
    }

    // --- something that comes back ---

    @Test
    fun `a weekday that repeats is a weekly calendar, starting on the next such day`() {
        assertEquals(weekly(LocalDate.of(2026, 9, 1), DayOfWeek.TUESDAY, t(8)), comes("pastillas cada martes a las 8"))
        assertEquals(weekly(LocalDate.of(2026, 8, 29), DayOfWeek.SATURDAY, t(10)), comes("los sábados a las 10"))
        assertEquals(weekly(LocalDate.of(2026, 8, 31), DayOfWeek.MONDAY, null), comes("todos los lunes"))
        assertEquals(weekly(LocalDate.of(2026, 8, 31), DayOfWeek.MONDAY, t(9)), comes("every monday at 9"))
        assertEquals(weekly(LocalDate.of(2026, 8, 31), DayOfWeek.MONDAY, null), comes("on mondays"))
    }

    @Test
    fun `a weekly on today's own day starts today only while its hour is ahead`() {
        assertEquals(weekly(today, DayOfWeek.THURSDAY, t(16)), comes("todos los jueves a las 16"))
        assertEquals(weekly(LocalDate.of(2026, 9, 3), DayOfWeek.THURSDAY, t(14)), comes("todos los jueves a las 14"))
        assertEquals(weekly(today, DayOfWeek.THURSDAY, null), comes("todos los jueves"))
    }

    @Test
    fun `two weekdays make one weekly`() {
        val expected = Recurrence.Calendar(
            Trigger.Repeat(startsOn = LocalDate.of(2026, 8, 31), unit = RepeatUnit.WEEK, time = t(9), days = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)),
        )
        assertEquals(expected, comes("los lunes y jueves a las 9"))
    }

    @Test
    fun `every day, starting today or tomorrow by the hour`() {
        assertEquals(daily(LocalDate.of(2026, 8, 28), t(9)), comes("todos los días a las 9"))
        assertEquals(daily(today, t(21)), comes("todos los días a las 21"))
        assertEquals(daily(today, null), comes("cada día"))
        // Seven in the morning is before the default getting-up (8:00): a waking seven is the evening's.
        assertEquals(daily(today, t(19)), comes("a diario a las 7"))
        assertEquals(daily(LocalDate.of(2026, 8, 28), t(9)), comes("every day at 9"))
        assertEquals(daily(today, null), comes("daily"))
        assertEquals(daily(today, null, every = 2), comes("cada dos días"))
        assertEquals(daily(today, null, every = 2), comes("every other day"))
        assertEquals(daily(today, null, every = 3), comes("every 3 days"))
    }

    @Test
    fun `weeks, months and years`() {
        assertEquals(Recurrence.Calendar(Trigger.Repeat(today, 2, RepeatUnit.WEEK)), comes("cada 2 semanas"))
        assertEquals(Recurrence.Calendar(Trigger.Repeat(today, 1, RepeatUnit.WEEK)), comes("cada semana"))
        assertEquals(Recurrence.Calendar(Trigger.Repeat(today, 1, RepeatUnit.WEEK)), comes("weekly"))
        assertEquals(Recurrence.Calendar(Trigger.Repeat(today, 1, RepeatUnit.MONTH)), comes("cada mes"))
        assertEquals(Recurrence.Calendar(Trigger.Repeat(today, 1, RepeatUnit.MONTH)), comes("monthly"))
        assertEquals(Recurrence.Calendar(Trigger.Repeat(today, 1, RepeatUnit.YEAR)), comes("cada año"))
        assertEquals(Recurrence.Calendar(Trigger.Repeat(today, 1, RepeatUnit.YEAR)), comes("every year"))
        assertEquals(Recurrence.Calendar(Trigger.Repeat(today, 2, RepeatUnit.WEEK)), comes("every 2 weeks"))
        assertEquals(Recurrence.Calendar(Trigger.Repeat(today, 6, RepeatUnit.MONTH)), comes("cada 6 meses"))
    }

    @Test
    fun `hours that repeat are a span from the hecho, not a calendar`() {
        assertEquals(Recurrence.After(8, RecurrenceUnit.HOURS), comes("cada 8 horas"))
        assertEquals(Recurrence.After(6, RecurrenceUnit.HOURS), comes("cada 6 h"))
        assertEquals(Recurrence.After(1, RecurrenceUnit.HOURS), comes("cada hora"))
        assertEquals(Recurrence.After(6, RecurrenceUnit.HOURS), comes("every 6 hours"))
        assertEquals(Recurrence.After(1, RecurrenceUnit.HOURS), comes("hourly"))
    }

    @Test
    fun `a repeat outranks a moment in the same sentence`() {
        assertEquals(daily(LocalDate.of(2026, 8, 28), t(9)), comes("a partir de mañana cada día a las 9"))
    }

    // --- what is not a when ---

    @Test
    fun `a number with nothing around it is not an hour`() {
        assertNull(read("las 3 bolsas de basura"))
        assertNull(read("comprar 2 kilos de patatas"))
        assertNull(read("recoger 26 cajas"))
        assertNull(read("26"))
        assertNull(read("piso 3 puerta 4"))
        assertNull(read("2 tickets"))
    }

    @Test
    fun `an hour that does not exist is nothing`() {
        assertNull(read("a las 25"))
        assertNull(read("a las 9:75"))
    }

    @Test
    fun `plain words are nothing`() {
        assertNull(read(""))
        assertNull(read("   "))
        assertNull(read("comprar filtros para la cafetera"))
        assertNull(read("llamar a mi madre"))
    }

    @Test
    fun `the same words read the same twice`() {
        assertEquals(read("regar mañana a las 9"), read("regar mañana a las 9"))
    }

    // --- what the review round found ---

    @Test
    fun `an hour said with the night is the evening's`() {
        assertEquals(at(2026, 8, 27, 21), once("cena esta noche a las 9"))
        assertEquals(at(2026, 8, 27, 21), once("tonight at 9"))
        assertEquals(tomorrow(t(20)), once("mañana por la noche a las 8"))
        assertEquals(at(2026, 8, 27, 22), once("esta noche a las 22"))
    }

    @Test
    fun `twelve at night is midnight, and one is lunchtime however it is spelled`() {
        assertEquals(at(2026, 8, 28, 0), once("sacar la basura a las 12 de la noche"))
        assertEquals(at(2026, 8, 28, 12), once("a las 12 de la mañana"))
        assertEquals(at(2026, 8, 28, 13), once("comer a la 1"))
        assertEquals(at(2026, 8, 28, 13), once("lunch at one"))
        assertEquals(at(2026, 8, 28, 1), once("a la 1 de la mañana"))
    }

    @Test
    fun `an English adverb is not a Spanish eleven`() {
        assertNull(read("call the plumber at once"))
        assertNull(read("do it at once please"))
    }

    @Test
    fun `a price is not a clock, and a bare clock needs its colon`() {
        assertNull(read("pagar 12.50 al vecino"))
        assertNull(read("cortar a 3.20 m"))
        assertEquals(at(2026, 8, 27, 17, 30), once("17:30"))
        assertEquals(at(2026, 8, 27, 21, 30), once("a las 9.30"))
    }

    @Test
    fun `a length the sheet would refuse is not offered`() {
        assertNull(read("avisar en 200 horas"))
        assertNull(read("en 0 minutos"))
        assertEquals(Trigger.Countdown(60), once("en 1h"))
        assertEquals(Trigger.Countdown(60), once("in 1h"))
        assertEquals(Recurrence.After(6, RecurrenceUnit.HOURS), comes("cada 6h"))
    }

    // --- the parts of the day are the person's own (0.136.0) ---

    @Test
    fun `the afternoon has an hour now, and the day beside it is no longer thrown away with it`() {
        // It used to be refused outright — the app had no hour for "la tarde" — and the refusal
        // took the whole sentence: "mañana por la tarde" gave no chip at all, not even "mañana".
        assertEquals(tomorrow(t(17)), once("mañana por la tarde"))
        assertEquals(tomorrow(t(17)), once("tomorrow afternoon"))
        assertEquals(daily(today, t(17)), comes("todos los días por la tarde"))
        assertEquals(tomorrow(t(17)), once("mañana por la tarde a las 5"), "and an hour that is named still wins")
        assertEquals(tomorrow(t(18, 30)), once("mañana por la tarde a las 6 y media"))
    }

    @Test
    fun `this afternoon and this morning are today, while they are still ahead`() {
        // Fixtures.now is 15:00: the afternoon is still coming, the morning has gone.
        assertEquals(at(2026, 8, 27, 17), once("llamar a Marta esta tarde"))
        assertEquals(at(2026, 8, 27, 17), once("call Marta this afternoon"))
        // "Esta mañana" read as *tomorrow*: the word for the morning is the word for the next
        // day, and only "por la", "de la" and "pasado" were kept from it. Gone is gone — never
        // tomorrow, which nobody said.
        assertNull(read("lo dejé hecho esta mañana"))
        assertNull(read("this morning"))
        val early = Fixtures.local(2026, 8, 27, 7, 0)
        assertEquals(at(2026, 8, 27, 9), (whenInText("regar esta mañana", early, zone) as Understood.Once).trigger)
        // And past its hour, this afternoon is not offered as tomorrow's either.
        assertNull(whenInText("esta tarde", Fixtures.local(2026, 8, 27, 18, 0), zone))
    }

    @Test
    fun `a word that merely ends in esta is not the word esta`() {
        // The guard that keeps "esta mañana" from reading as tomorrow had no word boundary, so
        // every word ending in "esta" swallowed it: propuesta, respuesta, encuesta, fiesta,
        // cuesta, siesta, orquesta. "Enviar la propuesta mañana" gave no chip at all — and with
        // a part of the day beside it the chip was not lost but *wrong*, which is worse:
        // "la fiesta mañana por la tarde" landed on today at five.
        assertEquals(tomorrow(), once("enviar la propuesta mañana"))
        assertEquals(tomorrow(), once("contestar la encuesta mañana"))
        assertEquals(tomorrow(t(17)), once("comprar hielo para la fiesta mañana por la tarde"))
        assertEquals(tomorrow(t(9)), once("la siesta mañana por la mañana"))
        // And the real "esta" still is one, with anything in front of it.
        assertNull(read("lo dejé hecho esta mañana"))
    }

    @Test
    fun `morning, afternoon and evening land on the hours the person keeps, not on the app's`() {
        // The quick chip under "Cuándo" has followed the day's start since 0.63.0; the words
        // went on meaning nine o'clock whatever Settings said, so the same three words were two
        // different hours depending on whether they were tapped or typed.
        val mine = DayParts(morning = t(7, 30), afternoon = t(16), evening = t(21, 30))
        fun mineOnce(text: String) = (whenInText(text, now, zone, mine) as Understood.Once).trigger
        assertEquals(tomorrow(t(7, 30)), mineOnce("mañana por la mañana"))
        assertEquals(tomorrow(t(16)), mineOnce("mañana por la tarde"))
        assertEquals(at(2026, 8, 27, 21, 30), mineOnce("esta noche"))
        assertEquals(at(2026, 8, 27, 21, 30), mineOnce("tonight"))
        // The defaults are what the words always meant, so nothing already typed moves.
        assertEquals(DayParts(), DayParts(morning = t(9), afternoon = t(17), evening = t(20)))
    }

    @Test
    fun `a full stop after the day of the month is not a decimal`() {
        assertEquals(Trigger.DayRandom(LocalDate.of(2026, 9, 5)), once("Llamar al fontanero el 5."))
        assertEquals(Trigger.DayRandom(LocalDate.of(2026, 9, 5)), once("el 5, sin falta"))
    }

    // --- a stretch of the calendar (0.175.0) ---

    private fun range(from: LocalDate, to: LocalDate) = Trigger.DateRange(from, to)

    @Test
    fun `this week is what is left of it, up to Sunday`() {
        val week = range(today, LocalDate.of(2026, 8, 30))
        assertEquals(week, once("en algún momento de esta semana"))
        assertEquals(week, once("llamar al banco esta semana"))
        assertEquals(week, once("a lo largo de esta semana"))
        assertEquals(week, once("sometime this week"))
        assertEquals(week, once("later this week"))
        // Said on the Sunday itself, the week is that one day.
        val sunday = Fixtures.local(2026, 8, 30, 10, 0)
        assertEquals(range(LocalDate.of(2026, 8, 30), LocalDate.of(2026, 8, 30)), (whenInText("esta semana", sunday, zone) as Understood.Once).trigger)
    }

    @Test
    fun `next week is Monday to Sunday after this one`() {
        val next = range(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 6))
        assertEquals(next, once("la semana que viene"))
        assertEquals(next, once("renovar el DNI la próxima semana"))
        assertEquals(next, once("la semana próxima"))
        assertEquals(next, once("next week"))
    }

    @Test
    fun `the weekend is Saturday and Sunday, or what is left of the one under way`() {
        val weekend = range(LocalDate.of(2026, 8, 29), LocalDate.of(2026, 8, 30))
        assertEquals(weekend, once("este fin de semana"))
        assertEquals(weekend, once("lavar el coche este finde"))
        assertEquals(weekend, once("el fin de semana"))
        assertEquals(weekend, once("this weekend"))
        val saturday = Fixtures.local(2026, 8, 29, 11, 0)
        assertEquals(weekend, (whenInText("este finde", saturday, zone) as Understood.Once).trigger)
        val sunday = Fixtures.local(2026, 8, 30, 11, 0)
        assertEquals(range(LocalDate.of(2026, 8, 30), LocalDate.of(2026, 8, 30)), (whenInText("este finde", sunday, zone) as Understood.Once).trigger)
        val next = range(LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 6))
        assertEquals(next, once("el finde que viene"))
        assertEquals(next, once("el fin de semana que viene"))
        assertEquals(next, once("el próximo fin de semana"))
        assertEquals(next, once("next weekend"))
    }

    @Test
    fun `this month and next month`() {
        assertEquals(range(today, LocalDate.of(2026, 8, 31)), once("pedir cita este mes"))
        assertEquals(range(today, LocalDate.of(2026, 8, 31)), once("this month"))
        assertEquals(range(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), once("el mes que viene"))
        assertEquals(range(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), once("el próximo mes"))
        assertEquals(range(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), once("next month"))
    }

    @Test
    fun `sometime today is today left to the day, and plain today still needs an hour`() {
        assertEquals(Trigger.DayRandom(today), once("en algún momento de hoy"))
        assertEquals(Trigger.DayRandom(today), once("hoy en algún momento"))
        assertEquals(Trigger.DayRandom(today), once("sometime today"))
        assertNull(read("hoy"))
    }

    @Test
    fun `a stretch with an hour beside it is two answers, and none is offered`() {
        assertNull(read("esta semana a las 9"))
        assertNull(read("este finde por la mañana"))
    }

    @Test
    fun `what comes back every week is not this week`() {
        assertEquals(Recurrence.Calendar(Trigger.Repeat(today, 1, RepeatUnit.WEEK)), comes("cada semana"))
        // Every weekend is a weekly series on Saturday and Sunday (0.181.0), never this weekend.
        val weekends = Recurrence.Calendar(Trigger.Repeat(LocalDate.of(2026, 8, 29), 1, RepeatUnit.WEEK, days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)))
        assertEquals(weekends, comes("todos los fines de semana"))
        assertEquals(weekends, comes("every weekend"))
        assertEquals(Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.WEEKS), null), once("dentro de una semana"))
        assertEquals(Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.MONTHS), null), once("en un mes"))
    }

    // --- what dictation taught the chip (0.181.0) ---

    @Test
    fun `the chip reads the ways people say a when that dictation learned`() {
        assertEquals(Trigger.Countdown(90), once("sacar la ropa en hora y media"))
        assertEquals(at(2026, 8, 27, 20, 45), once("llamar a las 9 menos cuarto"))
        assertEquals(at(2026, 8, 27, 21), once("llamar a las 21 horas"))
        assertEquals(at(2026, 8, 27, 17), once("llamar sobre las 5"))
        assertEquals(at(2026, 8, 28, 0), once("llamar a medianoche"))
        assertEquals(at(2026, 8, 27, 19, 15), once("call at quarter past 7"))
        assertEquals(tomorrow(t(9)), once("llamar mañana a primera hora"))
        assertEquals(Trigger.DayRandom(LocalDate.of(2026, 9, 14)), once("dentista el lunes 14"))
        assertEquals(Trigger.DayRandom(LocalDate.of(2026, 8, 31)), once("pagar a fin de mes"))
        assertEquals(Trigger.DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), once("apuntarme en septiembre"))
        assertEquals(at(2026, 8, 28, 8), once("pastilla al levantarme"))
        assertEquals(at(2026, 8, 27, 23), once("pastilla antes de acostarme"))
        val workdays = DayOfWeek.entries.toSet() - DayOfWeek.SATURDAY - DayOfWeek.SUNDAY
        assertEquals(Recurrence.Calendar(Trigger.Repeat(LocalDate.of(2026, 8, 28), 1, RepeatUnit.WEEK, time = t(8), days = workdays)), comes("fichar de lunes a viernes a las 8"))
        assertEquals(
            Recurrence.Calendar(Trigger.Repeat(LocalDate.of(2026, 9, 7), 1, RepeatUnit.MONTH, monthly = MonthlyOn.Nth(1, DayOfWeek.MONDAY))),
            comes("reunión el primer lunes de cada mes"),
        )
        assertEquals(daily(today, t(20)), comes("pastilla todas las noches"))
    }

    @Test
    fun `the chip keeps a weekly with its part of the day, and next month's end`() {
        assertEquals(weekly(LocalDate.of(2026, 8, 31), DayOfWeek.MONDAY, t(9)), comes("ir al gimnasio los lunes por las mañanas"))
        assertEquals(Trigger.DayRandom(LocalDate.of(2026, 9, 30)), once("pagar la tarjeta a final del mes que viene"))
    }

    @Test
    fun `what the chip still will not guess at`() {
        // How, not when; somebody's plans; a weekday and a number that never meet soon.
        assertNull(read("salir temprano del trabajo"))
        assertNull(read("planes para los fines de semana"))
        assertNull(read("el domingo 14"))
        // A number "to" an hour is a stretch, never minutes before it.
        assertNull(read("from 5 to 7"))
    }

    // --- a place somebody has saved (0.175.0) ---

    private val casa = SavedPlace("Casa", 40.40, -3.70, 120, id = "p-casa")
    private val oficina = SavedPlace("La Oficina", 40.41, -3.69, 150, id = "p-oficina")
    private val padres = SavedPlace("Casa de mis padres", 40.50, -3.60, 100, id = "p-padres")
    private val gimnasio = SavedPlace("Gimnasio", 40.42, -3.68, 80, id = "p-gimnasio")
    private val cafe = SavedPlace("Café Central", 40.43, -3.67, 60)
    private val home = SavedPlace("Home", 51.50, -0.12, 100, id = "p-home")
    private val places = listOf(casa, oficina, padres, gimnasio, cafe, home)

    private fun where(text: String): Trigger.Location =
        (whenInText(text, now, zone, places = places) as? Understood.Once)?.trigger as? Trigger.Location
            ?: throw AssertionError("'$text' was not read as a place: ${whenInText(text, now, zone, places = places)}")

    private fun readWithPlaces(text: String): Understood? = whenInText(text, now, zone, places = places)

    private fun SavedPlace.rule(presence: Presence, onCrossing: Boolean) =
        Trigger.Location(lat, lng, radiusM, presence, label, onCrossing = onCrossing, placeId = id.ifBlank { null })

    @Test
    fun `arriving somewhere is the doorway in`() {
        val arrive = casa.rule(Presence.INSIDE, onCrossing = true)
        assertEquals(arrive, where("comprar pan cuando llegue a casa"))
        assertEquals(arrive, where("al llegar a casa, regar"))
        assertEquals(arrive, where("en cuanto llegue a casa"))
        assertEquals(arrive, where("cuando vuelva a casa"))
        assertEquals(arrive, where("nada más llegar a casa"))
        assertEquals(home.rule(Presence.INSIDE, onCrossing = true), where("call mum when I get home"))
        assertEquals(home.rule(Presence.INSIDE, onCrossing = true), where("as soon as I get back home"))
        assertEquals(gimnasio.rule(Presence.INSIDE, onCrossing = true), where("cuando llegue al gimnasio"))
    }

    @Test
    fun `leaving somewhere is the doorway out`() {
        val leave = casa.rule(Presence.OUTSIDE, onCrossing = true)
        assertEquals(leave, where("cuando me vaya de casa"))
        assertEquals(leave, where("al salir de casa, la basura"))
        assertEquals(leave, where("cuando salga de casa"))
        assertEquals(gimnasio.rule(Presence.OUTSIDE, onCrossing = true), where("cuando salga del gimnasio"))
        assertEquals(home.rule(Presence.OUTSIDE, onCrossing = true), where("when I leave home"))
    }

    @Test
    fun `being somewhere is the state, not the doorway`() {
        val inside = casa.rule(Presence.INSIDE, onCrossing = false)
        assertEquals(inside, where("mientras estoy en casa"))
        assertEquals(inside, where("mientras esté en casa"))
        assertEquals(inside, where("cuando esté en casa"))
        assertEquals(inside, where("estando en casa"))
        assertEquals(home.rule(Presence.INSIDE, onCrossing = false), where("while I'm at home"))
        assertEquals(home.rule(Presence.INSIDE, onCrossing = false), where("when I am at home"))
    }

    @Test
    fun `being away from somewhere is the other side, as a state`() {
        val outside = casa.rule(Presence.OUTSIDE, onCrossing = false)
        assertEquals(outside, where("mientras no esté en casa"))
        assertEquals(outside, where("cuando no estoy en casa"))
        assertEquals(outside, where("cuando esté fuera de casa"))
        assertEquals(outside, where("estando fuera de casa"))
        assertEquals(home.rule(Presence.OUTSIDE, onCrossing = false), where("while I'm away from home"))
        assertEquals(home.rule(Presence.OUTSIDE, onCrossing = false), where("when I'm not at home"))
    }

    @Test
    fun `an article may stand before the name, and the name may carry its own`() {
        assertEquals(oficina.rule(Presence.INSIDE, onCrossing = true), where("al llegar a la oficina"))
        assertEquals(oficina.rule(Presence.OUTSIDE, onCrossing = true), where("cuando salga de la oficina"))
        assertEquals(casa.rule(Presence.OUTSIDE, onCrossing = true), where("cuando salga de mi casa"))
        assertEquals(gimnasio.rule(Presence.INSIDE, onCrossing = false), where("mientras estoy en el gimnasio"))
    }

    @Test
    fun `the longest name wins, and accents and case are nobody's business`() {
        assertEquals(padres.rule(Presence.INSIDE, onCrossing = true), where("cuando llegue a casa de mis padres"))
        assertEquals(casa.rule(Presence.INSIDE, onCrossing = true), where("CUANDO LLEGUE A CASA"))
        // A place kept before keys existed has none to give the rule.
        assertEquals(cafe.rule(Presence.OUTSIDE, onCrossing = true), where("al salir del cafe central"))
        assertEquals(null, where("al salir del Café Central").placeId)
    }

    @Test
    fun `a place nobody has saved, or a word that is not a place, is nothing`() {
        assertNull(readWithPlaces("limpiar la casa"))
        assertNull(readWithPlaces("cuando llegue a la playa"))
        assertNull(readWithPlaces("cuando vuelva a llamar"))
        assertNull(readWithPlaces("comprar pan cuando llegue a casablanca"))
        assertNull(whenInText("cuando llegue a casa", now, zone), "with no places saved there is nothing to name")
    }

    @Test
    fun `the clock outranks the place in the same sentence`() {
        assertEquals(tomorrow(), (readWithPlaces("mañana cuando llegue a casa") as Understood.Once).trigger)
        assertEquals(Trigger.Countdown(20), (readWithPlaces("en 20 minutos al salir de casa") as Understood.Once).trigger)
    }
}
