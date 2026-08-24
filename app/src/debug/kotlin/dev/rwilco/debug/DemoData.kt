package dev.rwilco.debug

import dev.rwilco.data.ReminderRepository
import dev.rwilco.model.Action
import dev.rwilco.model.Condition
import dev.rwilco.model.DEFAULT_ACTIONS
import dev.rwilco.model.Period
import dev.rwilco.model.Reminder
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.Status
import dev.rwilco.model.Transition
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * A believable set of reminders, relative to now so it always looks live: one in every family and
 * every Home section, a paused one, two done ones, and one long enough to stress the alert.
 */
object DemoData {

    suspend fun seed(repository: ReminderRepository, clock: Clock) {
        val now = clock.instant()
        val local = now.atZone(clock.zone)
        val today = local.toLocalDate()
        val tonight = today.atTime(21, 30).let { if (it.isAfter(local.toLocalDateTime())) it else it.plusDays(1) }
        val nextSaturday = today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))

        fun reminder(id: String, text: String, tags: List<String>, vararg triggers: Trigger, actions: Set<Action> = DEFAULT_ACTIONS, status: Status = Status.ACTIVE, ageMinutes: Long = 0) =
            Reminder(
                id = "demo-$id",
                text = text,
                tags = tags,
                rules = triggers.map { TriggerRule(it) },
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
                Trigger.AtTime(LocalTime.of(7, 30), setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)),
                actions = setOf(Action.FULL_SCREEN, Action.SOUND, Action.VIBRATE),
                ageMinutes = 2000,
            ),
            reminder(
                "plants", "Regar las plantas del balcón", listOf("casa"),
                Trigger.AtTime(LocalTime.of(10, 0), setOf(DayOfWeek.SATURDAY)),
                ageMinutes = 3000,
            ),
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
            reminder(
                "umbrella", "Coger el paraguas del paragüero", listOf("casa"),
                Trigger.Location(40.4168, -3.7038, 150, Transition.EXIT, "Casa"),
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
                Trigger.Location(40.4168, -3.7038, 200, Transition.ENTER, "Casa"),
                Trigger.AtDateTime(local.plusHours(4).toLocalDateTime().withSecond(0).withNano(0)),
                ageMinutes = 20,
            ).copy(ruleMatch = RuleMatch.ALL, firedRules = setOf(0)),
            reminder(
                "note", "Ideas para el regalo de Ana: cerámica, un buen cuchillo, entradas", listOf("regalos"),
                ageMinutes = 45,
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
