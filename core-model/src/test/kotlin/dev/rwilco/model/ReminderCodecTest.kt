package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ReminderCodecTest {

    private val everyKind = listOf(
        Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 30)),
        Trigger.OnDate(LocalDate.of(2026, 9, 1)),
        Trigger.AtTime(LocalTime.of(7, 30), setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)),
        Trigger.Location(40.4168, -3.7038, 200, Transition.ENTER, "Casa"),
        Trigger.Random(2, Period.DAY, LocalTime.of(10, 0), LocalTime.of(20, 0), emptySet()),
        Trigger.Random(1, Period.WEEK, LocalTime.of(18, 0), LocalTime.of(21, 0), setOf(DayOfWeek.SATURDAY)),
    )

    @Test
    fun `every trigger kind survives a round trip`() {
        assertEquals(everyKind, ReminderCodec.decodeTriggers(ReminderCodec.encodeTriggers(everyKind)))
    }

    @Test
    fun `the on-disk shape is frozen`() {
        // Golden JSON: renaming a discriminator or a field silently drops every stored trigger of
        // that kind, so the exact text is asserted, not just the round trip.
        val expected = """[""" +
            """{"type":"at_date_time","at":"2026-08-27T21:30"},""" +
            """{"type":"on_date","date":"2026-09-01"},""" +
            """{"type":"at_time","time":"07:30","days":["MONDAY","FRIDAY"]},""" +
            """{"type":"location","lat":40.4168,"lng":-3.7038,"radiusM":200,"transition":"ENTER","label":"Casa"},""" +
            """{"type":"random","timesPer":2,"period":"DAY","from":"10:00","to":"20:00","days":[]},""" +
            """{"type":"random","timesPer":1,"period":"WEEK","from":"18:00","to":"21:00","days":["SATURDAY"]}""" +
            """]"""
        assertEquals(expected, ReminderCodec.encodeTriggers(everyKind))
    }

    @Test
    fun `a trigger kind from the future is skipped and the rest kept`() {
        val raw = """[{"type":"on_date","date":"2026-09-01"},{"type":"telepathy","strength":3},{"type":"at_date_time","at":"2026-08-27T21:30"}]"""
        assertEquals(
            listOf(Trigger.OnDate(LocalDate.of(2026, 9, 1)), Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 30))),
            ReminderCodec.decodeTriggers(raw),
        )
    }

    @Test
    fun `a corrupt element is skipped and unknown fields ignored`() {
        val raw = """[{"type":"on_date","date":"not a date"},{"type":"on_date","date":"2026-09-01","colour":"red"}]"""
        assertEquals(listOf(Trigger.OnDate(LocalDate.of(2026, 9, 1))), ReminderCodec.decodeTriggers(raw))
    }

    @Test
    fun `garbage decodes to no triggers rather than a crash`() {
        assertTrue(ReminderCodec.decodeTriggers("").isEmpty())
        assertTrue(ReminderCodec.decodeTriggers("{}").isEmpty())
        assertTrue(ReminderCodec.decodeTriggers("nope").isEmpty())
    }

    @Test
    fun `random period defaults to DAY when the field is missing`() {
        val raw = """[{"type":"random","timesPer":2,"from":"10:00","to":"20:00"}]"""
        val decoded = ReminderCodec.decodeTriggers(raw).single() as Trigger.Random
        assertEquals(Period.DAY, decoded.period)
        assertTrue(decoded.days.isEmpty())
    }

    @Test
    fun `actions round trip and unknown names are dropped`() {
        val encoded = ReminderCodec.encodeActions(setOf(Action.FULL_SCREEN, Action.SOUND))
        assertEquals("""["FULL_SCREEN","SOUND"]""", encoded)
        assertEquals(setOf(Action.FULL_SCREEN, Action.SOUND), ReminderCodec.decodeActions(encoded))
        assertEquals(setOf(Action.VIBRATE), ReminderCodec.decodeActions("""["VIBRATE","SMOKE_SIGNAL"]"""))
        assertTrue(ReminderCodec.decodeActions("garbage").isEmpty())
    }

    @Test
    fun `tags round trip and garbage is empty`() {
        val tags = listOf("compra", "casa")
        assertEquals(tags, ReminderCodec.decodeTags(ReminderCodec.encodeTags(tags)))
        assertTrue(ReminderCodec.decodeTags("").isEmpty())
    }

    @Test
    fun `settings decode older and newer blobs`() {
        val defaults = AppSettings()
        assertEquals(defaults, ReminderCodec.decodeSettings("{}"))
        assertEquals(defaults, ReminderCodec.decodeSettings(""))
        assertEquals(defaults, ReminderCodec.decodeSettings("not json"))
        val newer = """{"theme":"DARK","defaultTime":"08:15","haptics":false,"lastSeenVersionCode":7,"futureKnob":true}"""
        assertEquals(
            AppSettings(theme = ThemeMode.DARK, defaultTime = LocalTime.of(8, 15), haptics = false, lastSeenVersionCode = 7),
            ReminderCodec.decodeSettings(newer),
        )
    }

    @Test
    fun `settings encode every field so a reader can rely on presence`() {
        val encoded = ReminderCodec.encodeSettings(AppSettings())
        assertEquals("""{"theme":"SYSTEM","defaultTime":"09:00","haptics":true,"lastSeenVersionCode":0}""", encoded)
    }
}
