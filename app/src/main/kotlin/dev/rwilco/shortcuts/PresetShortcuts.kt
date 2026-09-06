package dev.rwilco.shortcuts

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.rwilco.MainActivity
import dev.rwilco.model.Preset
import androidx.compose.ui.graphics.toArgb
import dev.rwilco.ui.theme.presetColorArgb
import dev.rwilco.ui.theme.routineColor

/**
 * What the launcher icon holds: **the overdue routines first, then the pinned presets.**
 *
 * A pinned preset is one tap on Home; this makes it one tap from the home screen, without the
 * app in between: hold the icon, "Comprar pan", and the reminder is written (or its words are
 * asked for, when the shape left them open — the same two doors Home has). The static "Nuevo"
 * keeps its slot; the rest take what the launcher leaves.
 *
 * **An overdue routine outranks a preset there** (the owner's call): the slots are few, a preset
 * is a thing you go looking for, and a routine whose span has run out is a thing that has to
 * find *you*. A routine's shortcut opens the routines with that one in view rather than saying
 * "hecho" outright — a mis-tap on a launcher would otherwise move a three-week count, and the
 * undo for it would be a snackbar nobody is looking at.
 */
object PresetShortcuts {
    const val ACTION_PRESET = "dev.rwilco.action.PRESET"
    const val EXTRA_PRESET_ID = "presetId"

    /** What a shortcut is made of: enough to draw it, and nothing that would republish for no reason. */
    data class Face(val id: String, val name: String, val colorIndex: Int)

    /** An overdue routine's, which needs no colour of its own: they all wear the routines'. */
    data class RoutineFace(val id: String, val text: String)

    fun facesOf(presets: List<Preset>): List<Face> = presets.filter { it.pinned }.map { Face(it.id, it.name, it.colorIndex) }

    /**
     * Replaces the dynamic set wholesale: what is owed first, then what Home pins, and nothing
     * else. [question] is the routine's line for the long label ("¿He hecho «X»?"), passed in
     * rather than read here so this stays a drawing job with no resources of its own.
     */
    fun publish(context: Context, routines: List<RoutineFace>, faces: List<Face>, question: (String) -> String = { it }) {
        // One slot is the static "Nuevo".
        val room = (ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) - 1).coerceAtLeast(0)
        val overdue = routines.take(room).map { routine ->
            ShortcutInfoCompat.Builder(context, routineShortcutId(routine.id))
                .setShortLabel(routine.text)
                .setLongLabel(question(routine.text))
                .setIcon(disc(context, routineColor(dark = false).toArgb(), routine.text))
                .setIntent(
                    // The action is not read anywhere — the destination extra is what MainActivity
                    // lands on — but a shortcut whose intent has none throws on publication, and
                    // the throw would leave the launcher with no dynamic shortcuts at all.
                    Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.routineDestination(routine.id)),
                )
                .build()
        }
        val presets = faces.take((room - overdue.size).coerceAtLeast(0)).map { face ->
            ShortcutInfoCompat.Builder(context, shortcutId(face.id))
                .setShortLabel(face.name)
                .setLongLabel(face.name)
                .setIcon(disc(context, presetColorArgb(face.colorIndex, dark = false), face.name))
                .setIntent(
                    Intent(context, MainActivity::class.java)
                        .setAction(ACTION_PRESET)
                        .putExtra(EXTRA_PRESET_ID, face.id),
                )
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(context, overdue + presets)
    }

    /** The launcher ranks by use; a preset used from the app counts for its shortcut too. */
    fun used(context: Context, presetId: String) {
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, shortcutId(presetId)) }
    }

    private fun shortcutId(presetId: String) = "preset-$presetId"

    private fun routineShortcutId(routineId: String) = "routine-$routineId"

    /**
     * A disc with an initial on it — the preset's own colour, or the routines' rose, from the
     * palette that reads on a light launcher, which is where most of them are.
     */
    private fun disc(context: Context, colorArgb: Int, label: String): IconCompat {
        val size = (ICON_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(colorArgb)
        val initial = label.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "·"
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = size * INITIAL_SHARE
            textAlign = Paint.Align.CENTER
        }
        val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initial, size / 2f, baseline, paint)
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    /** An adaptive icon's full canvas; the launcher masks the outer 18dp on each side. */
    private const val ICON_DP = 108f

    /** The initial, sized to sit inside the safe zone the mask leaves. */
    private const val INITIAL_SHARE = 0.42f
}
