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
    fun `the shape release yml publishes is the shape this parses`() {
        // Byte for byte what the workflow prints. v0.1.0 and v0.2.0 shipped a version.json with
        // a stray line in versionName — valid-looking, unparseable, and silent for weeks.
        val published = """{"versionCode": 3, "versionName": "0.2.1-alpha", "apk": "https://github.com/olemoudi/rwilco/releases/latest/download/rwilco.apk"}"""
        val info = UpdateInfo.parse(published)!!
        assertEquals(3, info.versionCode)
        assertEquals("0.2.1-alpha", info.versionName)
        assertTrue(info.isNewerThan(2))
    }

    @Test
    fun `a version name with a stray line in it still parses, which is why the producer has to be right`() {
        // Exactly what v0.1.0 and v0.2.0 published: the release workflow's grep matched a
        // comment before the real line. Nothing failed loudly — the parser tolerates the raw
        // newline — so the only symptom was an update card offering a version nobody could have
        // typed. Broken output that parses is the kind that survives; the fix belongs in
        // release.yml, and that is where it is.
        val info = UpdateInfo.parse("{\"versionCode\": 2, \"versionName\": \"X\n0.2.0-alpha\", \"apk\": \"https://x/a.apk\"}")!!
        assertEquals(2, info.versionCode, "the version code was never wrong, which is why updates kept working")
        assertTrue(info.versionName.contains('\n'), "and this is what nobody saw")
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
