package dev.rwilco.debug

import dev.rwilco.data.ReminderRepository
import dev.rwilco.model.Action
import dev.rwilco.model.Condition
import dev.rwilco.model.DEFAULT_ACTIONS
import dev.rwilco.model.Period
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.RepeatUnit
import dev.rwilco.model.Reminder
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.Status
import dev.rwilco.model.Presence
import dev.rwilco.model.FixTier
import dev.rwilco.model.NoteKind
import dev.rwilco.model.Trigger
import dev.rwilco.model.WatchNote
import dev.rwilco.model.TriggerRule
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * A believable set of reminders, relative to now so it always looks live: one in every family
 * and every Home section, one that rings by a recurrence and nothing else, a paused one, two
 * done ones, and one long enough to stress the alert.
 */
object DemoData {

    /**
     * A morning of the place watch, for the tour's capture of the location log: a rested night,
     * the sensor catching somebody leaving, the walk out with the GPS on for the last stretch,
     * the geofence saying the same thing a moment later, and then the cheap end of the day —
     * an errand across the province answered by the towers alone, and a look that spent nothing
     * because the fix in hand still stood.
     */
    fun watchNotes(clock: Clock): List<WatchNote> {
        val now = clock.instant()
        fun ago(minutes: Long) = now.minusSeconds(minutes * 60)
        // Yesterday evening, so the list has two days in it and the day headers have something to
        // separate: an hour on its own says nothing about which day it belongs to.
        fun yesterday(hour: Int, minute: Int) =
            now.atZone(clock.zone).toLocalDate().minusDays(1).atTime(hour, minute).atZone(clock.zone).toInstant()
        return listOf(
            WatchNote(yesterday(21, 12), NoteKind.FIX, waitS = 900, gapM = 380.0, place = "Casa", speedMps = 1.2, movedM = 640.0, sensed = true, charge = 44),
            WatchNote(yesterday(21, 26), NoteKind.FENCE, place = "Casa", inside = true, reported = true, acted = true),
            WatchNote(ago(184), NoteKind.FIX, waitS = 1800, gapM = 24.0, place = "Casa", inside = true, speedMps = 0.0, movedM = 0.0, sensed = false, stillStreak = 6, charge = 71),
            WatchNote(ago(154), NoteKind.REST, waitS = 1800, gapM = 24.0, place = "Casa", inside = true, speedMps = 0.0, movedM = 0.0, sensed = false, stillStreak = 7, charge = 70),
            WatchNote(ago(124), NoteKind.REST, waitS = 1800, gapM = 24.0, place = "Casa", inside = true, speedMps = 0.0, movedM = 0.0, sensed = false, stillStreak = 8, charge = 69),
            WatchNote(ago(96), NoteKind.STIR, waitS = 300, gapM = 24.0, sensed = true),
            WatchNote(ago(91), NoteKind.FIX, waitS = 300, gapM = 61.0, place = "Casa", inside = true, speedMps = 1.3, movedM = 118.0, sensed = true, charge = 68),
            WatchNote(ago(86), NoteKind.FIX, waitS = 120, gapM = 210.0, place = "Casa", speedMps = 1.4, movedM = 271.0, sensed = true, tier = FixTier.PRECISE, charge = 67),
            // A crossing with no name to be had: the reminder behind it has been dealt with and
            // deleted since, so there is no rule left to read a label off. (An old line whose
            // `place` is a geofence id reads the same way — `WatchNote.placeName` refuses to
            // print one as a name, and a log outlives the build that wrote it.)
            WatchNote(ago(85), NoteKind.FENCE, place = "8f2c1b04-51ad-4e3c-9b77-2ad19c4e77f1#0@40.50074,-3.66413,150,E", inside = true, reported = true, acted = false),
            WatchNote(ago(84), NoteKind.FENCE, place = "Casa", inside = false, reported = false, acted = true),
            // The two silences that wear the same kind: the system saying again what the watch
            // already knew, and a crossing held back because the app never saw the far side of it.
            WatchNote(ago(84), NoteKind.ECHO, place = "Casa", inside = false, reported = false),
            WatchNote(ago(83), NoteKind.ECHO, place = "Oficina", reported = true),
            WatchNote(ago(60), NoteKind.FIX, waitS = 3600, gapM = 4_180.0, place = "Oficina", speedMps = 8.6, movedM = 12_400.0, sensed = true, charge = 64),
            WatchNote(ago(48), NoteKind.FIX, waitS = 2_400, gapM = 61_500.0, place = "Cuenca", speedMps = 24.0, movedM = 41_000.0, sensed = true, tier = FixTier.COARSE, charge = 63),
            WatchNote(ago(30), NoteKind.CACHE, waitS = 2_100, gapM = 48_200.0, place = "Cuenca", speedMps = 21.0, movedM = 13_300.0, sensed = true, tier = FixTier.COARSE, charge = 62),
            WatchNote(ago(12), NoteKind.BLIND, waitS = 600, sensed = false, charge = 61),
        )
    }

    suspend fun seed(repository: ReminderRepository, clock: Clock) {
        val now = clock.instant()
        val local = now.atZone(clock.zone)
        val today = local.toLocalDate()
        val tonight = today.atTime(21, 30).let { if (it.isAfter(local.toLocalDateTime())) it else it.plusDays(1) }
        val nextSaturday = today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))

        // "Todos los lunes a las 7:30" is a calendar in "Vuelve" now, not a trigger.
        fun weekly(at: LocalTime, vararg days: DayOfWeek) = Recurrence.Calendar(
            Trigger.Repeat(startsOn = today, unit = RepeatUnit.WEEK, time = at, days = days.toSet()),
        )

        fun reminder(id: String, text: String, tags: List<String>, vararg triggers: Trigger, actions: Set<Action> = DEFAULT_ACTIONS, status: Status = Status.ACTIVE, ageMinutes: Long = 0, recurrence: Recurrence = Recurrence.None) =
            Reminder(
                id = "demo-$id",
                text = text,
                tags = tags,
                rules = triggers.map { TriggerRule(it) },
                recurrence = recurrence,
                actions = actions,
                status = status,
                createdAt = now.minus(Duration.ofMinutes(ageMinutes + 60)),
                updatedAt = now.minus(Duration.ofMinutes(ageMinutes)),
                doneAt = if (status == Status.DONE) now.minus(Duration.ofMinutes(ageMinutes)) else null,
            )

        val reminders = listOf(
            reminder(
                "hero", "Sacar la lavadora y tender", listOf("casa"),
                Trigger.AtDateTime(local.plusMinutes(42).toLocalDateTime().withSecond(0).withNano(0)),
                actions = setOf(Action.NOTIFICATION, Action.SOUND, Action.VIBRATE),
            ),
            reminder(
                "dentist", "Llamar al dentista para la revisión", listOf("salud"),
                Trigger.AtDateTime(tonight),
                ageMinutes = 30,
            ),
            reminder(
                "shopping", "Pilas AA, papel de horno y café", listOf("lista de la compra"),
                Trigger.OnDate(today.plusDays(1)),
                ageMinutes = 90,
            ),
            reminder(
                "pills", "Pastillas de la tensión", listOf("salud"),
                // The pills are the one that keeps asking: a reminder somebody means to answer.
                actions = setOf(Action.FULL_SCREEN, Action.SOUND_UNTIL_ANSWERED, Action.VIBRATE),
                ageMinutes = 2000,
                recurrence = weekly(LocalTime.of(7, 30), DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            ),
            // Rang this morning and was pushed away for a couple of hours: the state a card has
            // to say out loud, because everything else on it goes on describing next Saturday.
            reminder(
                "plants", "Regar las plantas del balcón", listOf("casa"),
                ageMinutes = 3000,
                recurrence = weekly(LocalTime.of(10, 0), DayOfWeek.SATURDAY),
            ).copy(snoozedUntil = now.plus(Duration.ofHours(2))),
            reminder(
                "long", "Preguntarle a Marta por el presupuesto de la reforma del baño, mirar si el fontanero puede venir el jueves y avisar al seguro antes de que caduque el parte del vecino de arriba", listOf("casa", "papeleo"),
                Trigger.AtDateTime(today.plusDays(1).atTime(18, 0)),
                actions = setOf(Action.FULL_SCREEN, Action.NOTIFICATION),
                ageMinutes = 15,
            ),
            reminder(
                "id", "Renovar el DNI", listOf("papeleo"),
                Trigger.OnDate(nextSaturday.plusWeeks(3)),
                ageMinutes = 5000,
            ),
            // Written in a hurry and never filed, which is what the "sin etiqueta" chip is for.
            reminder(
                "unfiled", "Preguntar en la ferretería por la bombilla del pasillo", emptyList(),
                Trigger.OnDate(nextSaturday),
                ageMinutes = 90,
            ),
            reminder(
                "umbrella", "Coger el paraguas del paragüero", listOf("casa"),
                Trigger.Location(40.4168, -3.7038, 150, Presence.OUTSIDE, "Casa"),
                ageMinutes = 400,
            ).let { umbrella ->
                // The one that shows a rule with a condition on it: only on the way out in the
                // morning, not every time the door closes.
                umbrella.copy(
                    rules = umbrella.rules.map { rule ->
                        rule.copy(conditions = listOf(Condition.TimeWindow(LocalTime.of(7, 0), LocalTime.of(10, 0))))
                    },
                )
            },
            reminder(
                "water", "Beber un vaso de agua", listOf("salud"),
                Trigger.Random(2, Period.DAY, LocalTime.of(10, 0), LocalTime.of(20, 0), emptySet()),
                ageMinutes = 8000,
            ),
            reminder(
                "filter", "Cambiar el filtro de la jarra de agua", listOf("casa"),
                Trigger.AtDateTime(today.plusDays(9).atTime(10, 0)),
                status = Status.PAUSED,
                ageMinutes = 700,
            ),
            reminder(
                "overdue", "Poner una lavadora de blanco", listOf("casa"),
                Trigger.AtDateTime(local.minusHours(3).toLocalDateTime().withSecond(0).withNano(0)),
                ageMinutes = 600,
            ),
            // The two shapes 0.3 added: rules that all have to happen, and a note nothing rings.
            reminder(
                "all", "Llamar a Marta por lo del presupuesto", listOf("trabajo"),
                Trigger.Location(40.4168, -3.7038, 200, Presence.INSIDE, "Casa"),
                Trigger.AtDateTime(local.plusHours(4).toLocalDateTime().withSecond(0).withNano(0)),
                ageMinutes = 20,
            ).copy(ruleMatch = RuleMatch.ALL, firedRules = setOf(0)),
            reminder(
                "note", "Ideas para el regalo de Ana: cerámica, un buen cuchillo, entradas", listOf("regalos"),
                ageMinutes = 45,
            ),
            // The shape 0.7 added and this list was missing: no trigger at all, only a
            // recurrence — a routine counted from the moment you last dealt with it.
            reminder(
                "antibiotic", "Tomar el antibiótico", listOf("salud"),
                ageMinutes = 120,
            ).copy(
                recurrence = Recurrence.After(8, RecurrenceUnit.HOURS),
                lastDealtAt = now.minus(Duration.ofHours(2)),
            ),
            reminder(
                "done1", "Devolver el libro a la biblioteca", listOf("papeleo"),
                Trigger.OnDate(today.minusDays(1)),
                status = Status.DONE,
                ageMinutes = 1500,
            ),
            reminder(
                "done2", "Enviar la factura de julio", listOf("trabajo"),
                Trigger.AtDateTime(today.minusDays(3).atTime(9, 0)),
                status = Status.DONE,
                ageMinutes = 4300,
            ),
        )
        repository.deleteAll()
        reminders.forEach { repository.save(it) }
    }
}
