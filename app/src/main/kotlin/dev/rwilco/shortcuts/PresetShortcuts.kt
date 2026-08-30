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
import dev.rwilco.ui.theme.presetColorArgb

/**
 * The pinned presets, held on the launcher icon.
 *
 * A pinned preset is one tap on Home; this makes it one tap from the home screen, without the
 * app in between: hold the icon, "Comprar pan", and the reminder is written (or its words are
 * asked for, when the shape left them open — the same two doors Home has). The static "Nuevo"
 * keeps its slot; the pinned ones take what the launcher leaves, in the order Home shows them.
 */
object PresetShortcuts {
    const val ACTION_PRESET = "dev.rwilco.action.PRESET"
    const val EXTRA_PRESET_ID = "presetId"

    /** What a shortcut is made of: enough to draw it, and nothing that would republish for no reason. */
    data class Face(val id: String, val name: String, val colorIndex: Int)

    fun facesOf(presets: List<Preset>): List<Face> = presets.filter { it.pinned }.map { Face(it.id, it.name, it.colorIndex) }

    /** Replaces the dynamic set wholesale: the launcher shows what Home pins, and nothing else. */
    fun publish(context: Context, faces: List<Face>) {
        // One slot is the static "Nuevo".
        val room = (ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) - 1).coerceAtLeast(0)
        val shortcuts = faces.take(room).map { face ->
            ShortcutInfoCompat.Builder(context, shortcutId(face.id))
                .setShortLabel(face.name)
                .setLongLabel(face.name)
                .setIcon(icon(context, face))
                .setIntent(
                    Intent(context, MainActivity::class.java)
                        .setAction(ACTION_PRESET)
                        .putExtra(EXTRA_PRESET_ID, face.id),
                )
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    /** The launcher ranks by use; a preset used from the app counts for its shortcut too. */
    fun used(context: Context, presetId: String) {
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, shortcutId(presetId)) }
    }

    private fun shortcutId(presetId: String) = "preset-$presetId"

    /**
     * The preset's disc with its initial on it — the same colour the button on Home wears,
     * from the palette that reads on a light launcher, which is where most of them are.
     */
    private fun icon(context: Context, face: Face): IconCompat {
        val size = (ICON_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(presetColorArgb(face.colorIndex, dark = false))
        val initial = face.name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "·"
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
