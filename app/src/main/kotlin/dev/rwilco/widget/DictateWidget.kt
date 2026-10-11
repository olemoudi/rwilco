package dev.rwilco.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.rwilco.Destinations
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.ui.theme.RwilcoDarkColors

/**
 * A mic on the home screen (0.176.0): one tap and the app opens listening, the way the "Dictar"
 * launcher shortcut does. Two cells by one by default, the mic and the word; shrunk to one cell
 * it is the mic alone.
 *
 * A tap and not a hold, unlike the door on Home: a launcher cannot tell a widget about a held
 * finger, and nothing is written by opening — the sheet still has to hear a sentence first.
 * Always the dark scheme, like the other widget ([NextWidget]); neutral, since amber is what
 * fires next.
 */
class DictateWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Content() }
    }

    private companion object {
        val SMALL = DpSize(48.dp, 48.dp)
        val WIDE = DpSize(110.dp, 48.dp)
    }
}

class DictateWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DictateWidget()
}

@Composable
private fun Content() {
    val context = androidx.glance.LocalContext.current
    val wide = LocalSize.current.width >= 110.dp
    val ink = ColorProvider(RwilcoDarkColors.onSurface)
    val dictate = actionStartActivity(Intent(context, MainActivity::class.java).setAction(Destinations.ACTION_DICTATE))
    Box(
        contentAlignment = Alignment.Center,
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(RwilcoDarkColors.surfaceContainer))
            .cornerRadius(24.dp)
            .clickable(dictate),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_mic),
                contentDescription = context.getString(R.string.home_dictate),
                colorFilter = ColorFilter.tint(ink),
                modifier = GlanceModifier.size(24.dp),
            )
            if (wide) {
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    text = context.getString(R.string.shortcut_dictate_short),
                    style = TextStyle(color = ink, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
        }
    }
}
