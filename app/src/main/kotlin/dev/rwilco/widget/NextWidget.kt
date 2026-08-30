package dev.rwilco.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.rwilco.Destinations
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.AppSettings
import dev.rwilco.model.NextFire
import dev.rwilco.model.dayShape
import dev.rwilco.model.groupForHome
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.dayWord
import dev.rwilco.ui.format.words
import dev.rwilco.ui.theme.RwilcoDarkColors
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId

/**
 * What fires next, on the home screen: the words, when, how many are due today, and "Nuevo".
 *
 * The one Home answers first, said without opening the app. No live countdown — Glance
 * draws with RemoteViews and redraws when it is told to, so the widget says *a las 18:30*
 * rather than a number that would be stale within the minute. It is redrawn on every change
 * to a reminder (`NextWidget.refresh`, from the Application's own collectors) and every half
 * hour by the system, which is how "hoy" turns into "mañana" at midnight.
 *
 * **Always the dark scheme.** Glance reads none of the Compose theme, so the widget carries
 * the dark tokens' values itself — the same `RwilcoDarkColors` the app draws with, read
 * directly rather than copied. Dark-first is the app's own rule and a widget is a small dark
 * card on whatever the launcher is; amber, as everywhere, is for the thing that fires next.
 * The typefaces are the launcher's: a widget cannot carry a font.
 */
class NextWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(NARROW, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = widgetState(context)
        provideContent { Content(state) }
    }

    companion object {
        /** Two cells wide: the words and the moment. Four: the count and "Nuevo" beside them. */
        private val NARROW = DpSize(140.dp, 60.dp)
        private val WIDE = DpSize(250.dp, 60.dp)

        /** Every instance redrawn. Cheap when there are none, which is most phones. */
        suspend fun refresh(context: Context) {
            runCatching { NextWidget().updateAll(context) }
        }
    }
}

class NextWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextWidget()
}

/** What the widget says: the next reminder, when, and how many are due today. */
data class WidgetState(
    val nextId: String?,
    val nextText: String?,
    /** When, in words: "hoy 18:30", "mañana 09:00"; null when the next is a place or a chance. */
    val nextWhen: String?,
    val today: Int,
)

private suspend fun widgetState(context: Context): WidgetState {
    val app = context.applicationContext as RwilcoApplication
    val settings = app.settings.filterNotNull().first()
    val reminders = app.repository.openNow()
    return widgetStateOf(context, reminders, settings, app.clock.instant(), app.clock.zone)
}

/** Pure but for the words: the same grouping Home does, read for its first answer. */
internal fun widgetStateOf(
    context: Context,
    reminders: List<dev.rwilco.model.Reminder>,
    settings: AppSettings,
    now: Instant,
    zone: ZoneId,
): WidgetState {
    val groups = groupForHome(reminders, now, zone, settings.defaultTime, null, settings.dayStart, settings.dayShape)
    val hero = groups.hero
    val today = now.atZone(zone).toLocalDate()
    val words = context.words()
    val dueToday = (listOfNotNull(hero?.entry) + groups.sections.values.flatten())
        .count { entry -> (entry.next as? NextFire.Scheduled)?.at?.atZone(zone)?.toLocalDate() == today }
    val at = hero?.entry?.wake?.at?.atZone(zone)
    return WidgetState(
        nextId = hero?.entry?.reminder?.id,
        nextText = hero?.entry?.reminder?.text,
        nextWhen = at?.let { dayWord(words, it.toLocalDate(), today) + " " + TimeText.time(it.toLocalTime(), words.is24h, words.locale) },
        today = dueToday,
    )
}

@Composable
private fun Content(state: WidgetState) {
    val size = LocalSize.current
    val wide = size.width >= 250.dp
    val background = ColorProvider(RwilcoDarkColors.surfaceContainer)
    val ink = ColorProvider(RwilcoDarkColors.onSurface)
    val quiet = ColorProvider(RwilcoDarkColors.onSurfaceVariant)
    val amber = ColorProvider(RwilcoDarkColors.primary)
    val context = androidx.glance.LocalContext.current
    val openNext = state.nextId?.let { id ->
        actionStartActivity(
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.reminderDestination(id)),
        )
    }
    val newOne = actionStartActivity(Intent(context, MainActivity::class.java).setAction(Destinations.ACTION_NEW))

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(background)
            .cornerRadius(24.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = GlanceModifier.defaultWeight().then(if (openNext != null) GlanceModifier.clickable(openNext) else GlanceModifier.clickable(newOne)),
            ) {
                Text(
                    text = state.nextWhen ?: context.getString(if (state.nextText == null) R.string.widget_nothing else R.string.home_next_up),
                    style = TextStyle(color = amber, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    text = state.nextText ?: context.getString(R.string.widget_nothing_hint),
                    style = TextStyle(color = ink, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                    maxLines = 2,
                )
            }
            if (wide) {
                Spacer(GlanceModifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = context.resources.getQuantityString(R.plurals.widget_today, state.today, state.today),
                        style = TextStyle(color = quiet, fontSize = 12.sp),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.height(6.dp))
                    Box(
                        modifier = GlanceModifier
                            .background(ColorProvider(RwilcoDarkColors.onSurface))
                            .cornerRadius(14.dp)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .clickable(newOne),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = context.getString(R.string.home_new),
                            style = TextStyle(color = ColorProvider(RwilcoDarkColors.surface), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
