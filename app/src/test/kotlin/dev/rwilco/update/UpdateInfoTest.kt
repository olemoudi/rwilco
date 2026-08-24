package dev.rwilco.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateInfoTest {

    @Test
    fun `parses what CI publishes and tolerates fields it does not know`() {
        val info = UpdateInfo.parse("""{"versionCode": 7, "versionName": "0.3.0", "apk": "https://x/rwilco.apk"}""")
        assertEquals(UpdateInfo(7, "0.3.0", "https://x/rwilco.apk"), info)
        assertEquals(2, UpdateInfo.parse("""{"versionCode": 2, "future": true}""")!!.versionCode)
    }

    @Test
    fun `garbage is null rather than a crash`() {
        assertNull(UpdateInfo.parse("not json"))
        assertNull(UpdateInfo.parse(""))
        assertNull(UpdateInfo.parse("<html>Sign in to use this network</html>"))
    }

    @Test
    fun `newer means a higher code and an apk to fetch`() {
        assertTrue(UpdateInfo(3, apk = "https://x/a.apk").isNewerThan(2))
        assertFalse(UpdateInfo(3, apk = "").isNewerThan(2))
        assertFalse(UpdateInfo(2, apk = "https://x/a.apk").isNewerThan(2))
        assertFalse(UpdateInfo(1, apk = "https://x/a.apk").isNewerThan(2))
    }
}
