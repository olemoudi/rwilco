package dev.rwilco.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.CONTACT_MONTHS
import dev.rwilco.model.ContactLoad
import dev.rwilco.model.ContactSchedule
import dev.rwilco.model.ContactWarning
import dev.rwilco.ui.components.DayToggles
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.Stepper
import dev.rwilco.ui.components.TimeField
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.format.rememberIs24h
import dev.rwilco.ui.theme.Tokens
import java.time.format.TextStyle
import java.time.temporal.WeekFields

/**
 * How one kind of contact is told about: the days, the stretch of them, and how often somebody
 * close and somebody sporadic comes round (`Contacts.kt`). **Every contact of the kind follows
 * this**, the ones already written included — except what was changed by hand on one of them.
 *
 * No day at all is a kind never told about, which is a legitimate thing to want and needs no
 * switch of its own.
 */
@Composable
fun ContactScheduleCard(
    schedule: ContactSchedule,
    onChange: (ContactSchedule) -> Unit,
    modifier: Modifier = Modifier,
    /** What is worth saying about this schedule, if anything ([contactWarning]). */
    warning: ContactWarning = ContactWarning.NONE,
    /** The numbers behind an over-capacity warning, which is where its way out is worked out. */
    load: ContactLoad? = null,
) {
    val spacing = Tokens.spacing
    RwilcoCard(modifier = modifier) {
        Column(Modifier.padding(spacing.lg)) {
            Text(
                text = stringResource(R.string.settings_contacts_days),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.sm))
            DayToggles(
                selected = schedule.days,
                onToggle = { day -> onChange(schedule.copy(days = if (day in schedule.days) schedule.days - day else schedule.days + day)) },
            )
            ContactScheduleWarning(warning, load)
            Spacer(Modifier.height(spacing.md))
            // A window with no length has no moment in it, so an end set on its start is refused
            // rather than saved as a kind that can never be told about.
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                TimeField(
                    time = schedule.window.from,
                    onChange = { from -> if (from != schedule.window.to) onChange(schedule.copy(window = schedule.window.copy(from = from))) },
                    label = stringResource(R.string.settings_contacts_from),
                    modifier = Modifier.weight(1f),
                )
                TimeField(
                    time = schedule.window.to,
                    onChange = { to -> if (to != schedule.window.from) onChange(schedule.copy(window = schedule.window.copy(to = to))) },
                    label = stringResource(R.string.settings_contacts_to),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(spacing.lg))
            MonthsRow(stringResource(R.string.settings_contacts_close), schedule.closeMonths) { onChange(schedule.copy(closeMonths = it)) }
            Spacer(Modifier.height(spacing.sm))
            MonthsRow(stringResource(R.string.settings_contacts_distant), schedule.distantMonths) { onChange(schedule.copy(distantMonths = it)) }
        }
    }
}

/**
 * "Cercanos / cada 3 meses · − 3 +". The words go under the label and the stepper carries the
 * number alone: "3 meses" in the stepper's mono ran into its own + button.
 */
@Composable
private fun MonthsRow(label: String, months: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = pluralStringResource(R.plurals.trigger_repeat_months, months, months),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Stepper(
            valueLabel = months.toString(),
            onDecrement = { onChange((months - 1).coerceIn(CONTACT_MONTHS)) },
            onIncrement = { onChange((months + 1).coerceIn(CONTACT_MONTHS)) },
            decrementEnabled = months > CONTACT_MONTHS.first,
            incrementEnabled = months < CONTACT_MONTHS.last,
        )
    }
}

/**
 * The two things this card can be wrong about in a way nobody would notice.
 *
 * A kind with no day at all goes silent for ever, and one with more people than its days can
 * carry never reaches the back of its own queue — the only symptom being a row, on another
 * screen, reading "sin turno en el próximo año", which names the fact and not the cause. Neither
 * is forbidden (the first is how a kind is turned off), so both are said, in the error ink, right
 * under the days that decide them.
 */
@Composable
private fun ContactScheduleWarning(warning: ContactWarning, load: ContactLoad?) {
    val words = when (warning) {
        ContactWarning.NONE -> return
        ContactWarning.NEVER -> stringResource(R.string.settings_contacts_never_warning)
        ContactWarning.OVER_CAPACITY ->
            stringResource(R.string.settings_contacts_over_body, load?.people ?: 0, load?.monthsNeeded ?: 0)
    }
    val scheme = MaterialTheme.colorScheme
    val spacing = Tokens.spacing
    Spacer(Modifier.height(spacing.md))
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            // The sentence beside it says the whole of it; a reader hearing "warning" first
            // would be hearing the same thing twice.
            contentDescription = null,
            tint = scheme.error,
            modifier = Modifier.size(Tokens.sizes.glyphSmall),
        )
        Spacer(Modifier.width(spacing.sm))
        Text(text = words, style = MaterialTheme.typography.bodySmall, color = scheme.error)
    }
}

/** "mié 9:00–12:00", or "nunca": what the folded group says about one kind. */
@Composable
fun contactScheduleSummary(schedule: ContactSchedule): String {
    val locale = currentLocale()
    val is24h = rememberIs24h()
    val never = stringResource(R.string.settings_contacts_never)
    if (schedule.days.isEmpty()) return never
    val first = WeekFields.of(locale).firstDayOfWeek
    val days = schedule.days
        .sortedBy { Math.floorMod(it.value - first.value, 7) }
        .joinToString(" ") { it.getDisplayName(TextStyle.SHORT, locale) }
    return days + " " + TimeText.window(schedule.window.from, schedule.window.to, is24h, locale)
}
