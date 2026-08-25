package dev.rwilco.geo

import android.content.Context
import android.os.BatteryManager

/**
 * How much battery there is left to spend on watching places.
 *
 * One synchronous property read from the system's own gauge — no broadcast to register, nothing
 * to keep alive between checks, and cheap enough to ask on every one of them.
 *
 * Charging reads as "nothing to hold back for", which is the honest answer: a phone on a cable
 * at eight per cent is a phone about to be at eighty, and slowing its watch down would be
 * spending the one thing it is not short of. The policy that reads this is
 * [dev.rwilco.model.batteryFloor].
 */
class BatteryGauge(private val context: Context) {

    /** What is left, 0..1 — or null while charging, and when the phone will not say. */
    fun remaining(): Double? {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return null
        if (runCatching { manager.isCharging }.getOrDefault(false)) return null
        val percent = runCatching { manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrNull()
        // The property is Integer.MIN_VALUE on a device that does not support it.
        return if (percent != null && percent in 0..100) percent / 100.0 else null
    }
}
