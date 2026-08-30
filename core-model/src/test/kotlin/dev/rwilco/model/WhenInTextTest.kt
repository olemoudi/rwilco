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
        assertEquals(Trigger.RelativeDate(RelativeDay.In(2, RelativeUnit.DAYS), t(8)), once("pasado mañana a las 8"))
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
    fun `an hour alone is today while it is ahead and tomorrow once it is past`() {
        assertEquals(at(2026, 8, 28, 9), once("a las 9"))
        assertEquals(at(2026, 8, 27, 21), once("a las 21"))
        assertEquals(at(2026, 8, 28, 9, 30), once("a las 9 y media"))
        assertEquals(at(2026, 8, 27, 21), once("a las 9 de la noche"))
        assertEquals(at(2026, 8, 28, 13), once("a la una"))
        assertEquals(at(2026, 8, 28, 9), once("a las nueve"))
        assertEquals(at(2026, 8, 27, 17, 30), once("17:30"))
        assertEquals(at(2026, 8, 28, 12), once("al mediodía"))
        assertEquals(at(2026, 8, 28, 9), once("at 9"))
        assertEquals(at(2026, 8, 28, 9, 30), once("at 9:30"))
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
        assertNull(read("hoy a las 9"))
        assertNull(read("today at 9"))
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
        assertEquals(daily(LocalDate.of(2026, 8, 28), t(7)), comes("a diario a las 7"))
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
        assertNull(read("esta tarde"))
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
        assertEquals(at(2026, 8, 28, 9, 30), once("a las 9.30"))
    }

    @Test
    fun `a length the sheet would refuse is not offered`() {
        assertNull(read("avisar en 200 horas"))
        assertNull(read("en 0 minutos"))
        assertEquals(Trigger.Countdown(60), once("en 1h"))
        assertEquals(Trigger.Countdown(60), once("in 1h"))
        assertEquals(Recurrence.After(6, RecurrenceUnit.HOURS), comes("cada 6h"))
    }

    @Test
    fun `the afternoon has no hour here, so the sentence is left alone`() {
        assertNull(read("mañana por la tarde"))
        assertNull(read("tomorrow afternoon"))
        assertNull(read("todos los días por la tarde"))
        assertEquals(tomorrow(t(17)), once("mañana por la tarde a las 5"), "unless it names one")
    }

    @Test
    fun `a full stop after the day of the month is not a decimal`() {
        assertEquals(Trigger.DayRandom(LocalDate.of(2026, 9, 5)), once("Llamar al fontanero el 5."))
        assertEquals(Trigger.DayRandom(LocalDate.of(2026, 9, 5)), once("el 5, sin falta"))
    }
}
