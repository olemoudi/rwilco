package dev.rwilco.notify

import android.media.AudioDeviceInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What the report says about where a reminder's sound goes and how loud it can be (0.161.0).
 *
 * The alarm slider alone never answered "it rang, but very quietly": with headphones connected
 * and something playing, Android holds an alarm in them under the media volume, so the line has
 * to carry all four — the headphones, the music, the media volume and the alarm's.
 */
class AudioLineTest {

    @Test
    fun `the line a quiet ring into playing earbuds leaves behind`() {
        assertEquals(
            "headset=bt music=y media=4/25 alarm=5/7",
            audioLine(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, musicActive = true, media = "4/25", alarm = "5/7"),
        )
    }

    @Test
    fun `every kind of headphones has a word, and none is said as none`() {
        val words = mapOf(
            null to "none",
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "bt",
            AudioDeviceInfo.TYPE_BLE_HEADSET to "ble",
            AudioDeviceInfo.TYPE_BLE_SPEAKER to "ble",
            AudioDeviceInfo.TYPE_WIRED_HEADSET to "wired",
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES to "wired",
            AudioDeviceInfo.TYPE_USB_HEADSET to "usb",
            AudioDeviceInfo.TYPE_USB_DEVICE to "usb",
            AudioDeviceInfo.TYPE_HEARING_AID to "hearing",
        )
        for ((type, word) in words) {
            assertEquals("headset=$word music=n media=0/25 alarm=7/7", audioLine(type, musicActive = false, media = "0/25", alarm = "7/7"), "$type")
        }
    }
}
