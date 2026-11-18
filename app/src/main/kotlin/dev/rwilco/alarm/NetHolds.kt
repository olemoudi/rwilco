package dev.rwilco.alarm

import android.content.Context
import java.time.Instant

/**
 * The safety net's words held back because a card about their reminder was still showing in the
 * shade, and when to look again (owner, 2026-11-18: the ICYMI waits while a notification about the
 * same reminder is *visible in the drawer* — not swiped away, not lost to a reboot). A held word is
 * not a spent one: it goes out at the first look that finds the shade clear of it.
 *
 * On this phone only, in its own small file: it is about this phone's shade, so it is not in the
 * vault and not in the row. An entry goes when its word is said, when the net has nothing to say
 * about its reminder any more, or when the reminder is gone.
 */
class NetHolds(context: Context) {
    private val prefs = context.getSharedPreferences("net_holds", Context.MODE_PRIVATE)

    fun until(id: String): Instant? = prefs.getLong(id, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)

    fun hold(id: String, until: Instant) = prefs.edit().putLong(id, until.toEpochMilli()).apply()

    fun clear(id: String) {
        if (prefs.contains(id)) prefs.edit().remove(id).apply()
    }

    /** Drops every entry whose reminder is not in [ids]: a deleted or finished one holds nothing. */
    fun keepOnly(ids: Set<String>) {
        val stale = prefs.all.keys.filter { it !in ids }
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach(::remove) }.apply()
    }
}
