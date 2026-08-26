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
        val rules = everyKind.asRules()
        assertEquals(rules, ReminderCodec.decodeRules(ReminderCodec.encodeRules(rules)))
    }

    @Test
    fun `the on-disk shape is frozen`() {
        // Golden JSON: renaming a discriminator or a field silently drops every stored trigger of
        // that kind, so the exact text is asserted, not just the round trip.
        val rules = listOf(
            TriggerRule(everyKind[0]),
            TriggerRule(everyKind[2], listOf(Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0), setOf(DayOfWeek.FRIDAY)))),
        )
        val expected = """[""" +
            """{"trigger":{"type":"at_date_time","at":"2026-08-27T21:30"},"conditions":[]},""" +
            """{"trigger":{"type":"at_time","time":"07:30","days":["MONDAY","FRIDAY"]},""" +
            """"conditions":[{"type":"time_window","from":"18:00","to":"22:00","days":["FRIDAY"]}]}""" +
            """]"""
        assertEquals(expected, ReminderCodec.encodeRules(rules))
    }

    @Test
    fun `bare triggers written before conditions existed still read`() {
        // What v0.1.0 wrote. Dropping these would empty every reminder on the phone at update.
        val legacy = """[{"type":"on_date","date":"2026-09-01"},{"type":"at_date_time","at":"2026-08-27T21:30"}]"""
        assertEquals(
            listOf(
                TriggerRule(Trigger.OnDate(LocalDate.of(2026, 9, 1))),
                TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 30))),
            ),
            ReminderCodec.decodeRules(legacy),
        )
    }

    @Test
    fun `a trigger kind from the future is skipped and the rest kept`() {
        val raw = """[{"type":"on_date","date":"2026-09-01"},{"type":"telepathy","strength":3},{"trigger":{"type":"at_date_time","at":"2026-08-27T21:30"}}]"""
        assertEquals(
            listOf(
                TriggerRule(Trigger.OnDate(LocalDate.of(2026, 9, 1))),
                TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 30))),
            ),
            ReminderCodec.decodeRules(raw),
        )
    }

    @Test
    fun `a condition from the future is dropped but its rule still rings`() {
        val raw = """[{"trigger":{"type":"on_date","date":"2026-09-01"},"conditions":[{"type":"weather","is":"rain"}]}]"""
        assertEquals(
            listOf(TriggerRule(Trigger.OnDate(LocalDate.of(2026, 9, 1)))),
            ReminderCodec.decodeRules(raw),
            "a restriction we cannot judge must not turn into a reminder that never arrives",
        )
    }

    @Test
    fun `a corrupt element is skipped and unknown fields ignored`() {
        val raw = """[{"type":"on_date","date":"not a date"},{"type":"on_date","date":"2026-09-01","colour":"red"}]"""
        assertEquals(listOf(TriggerRule(Trigger.OnDate(LocalDate.of(2026, 9, 1)))), ReminderCodec.decodeRules(raw))
    }

    @Test
    fun `garbage decodes to no rules rather than a crash`() {
        assertTrue(ReminderCodec.decodeRules("").isEmpty())
        assertTrue(ReminderCodec.decodeRules("{}").isEmpty())
        assertTrue(ReminderCodec.decodeRules("nope").isEmpty())
    }

    @Test
    fun `random period defaults to DAY when the field is missing`() {
        val raw = """[{"type":"random","timesPer":2,"from":"10:00","to":"20:00"}]"""
        val decoded = ReminderCodec.decodeRules(raw).single().trigger as Trigger.Random
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
        val newer = """{"theme":"DARK","defaultTime":"08:15","haptics":false,"defaultTriggerKind":"COUNTDOWN","lastSeenVersionCode":7,"futureKnob":true}"""
        assertEquals(
            AppSettings(
                theme = ThemeMode.DARK,
                defaultTime = LocalTime.of(8, 15),
                haptics = false,
                defaultTriggerKind = TriggerKind.COUNTDOWN,
                lastSeenVersionCode = 7,
            ),
            ReminderCodec.decodeSettings(newer),
        )
    }

    @Test
    fun `settings encode every field so a reader can rely on presence`() {
        val encoded = ReminderCodec.encodeSettings(AppSettings())
        assertEquals(
            """{"theme":"SYSTEM","defaultTime":"09:00","haptics":true,"defaultTriggerKind":null,""" +
                """"popularTriggersFirst":false,""" +
                """"weekendDay":"FRIDAY","weekendTime":"20:30","lastSeenVersionCode":0,"savedPlaces":[],""" +
                """"defaultActions":["NOTIFICATION","VIBRATE"],"presets":[],"hiddenTexts":[],""" +
                """"dayStart":"09:00","recurrencePresets":[""" +
                """{"id":"builtin-day","recurrence":{"type":"after","amount":1,"unit":"DAYS"},"name":"","uses":0,"lastUsedAt":null},""" +
                """{"id":"builtin-6h","recurrence":{"type":"after","amount":6,"unit":"HOURS"},"name":"","uses":0,"lastUsedAt":null},""" +
                """{"id":"builtin-week","recurrence":{"type":"after","amount":1,"unit":"WEEKS"},"name":"","uses":0,"lastUsedAt":null},""" +
                """{"id":"builtin-month","recurrence":{"type":"after","amount":1,"unit":"MONTHS"},"name":"","uses":0,"lastUsedAt":null}],""" +
                """"busyWatchNotice":false,"vibration":{"strength":"STRONG","rhythm":"PULSED"},""" +
                """"alertSound":{"type":"system"},"soundPlays":5,"soundGapMinutes":5,"alertStacking":"SEQUENTIAL","updatesWifiOnly":false,"alertToHeadphones":true}""",
            encoded,
        )
    }

    @Test
    fun `a recurrence from a newer build reads as none rather than as a guess`() {
        // A shape this build has no words for cannot be honoured, and guessing at it would be
        // guessing about when somebody's reminder comes back. "Not at all" is the answer that
        // leaves the reminder on Home for a person to look at.
        assertEquals(Recurrence.None, ReminderCodec.decodeRecurrence("""{"type":"every_full_moon","nights":3}"""))
        assertEquals(Recurrence.None, ReminderCodec.decodeRecurrence("not json at all"))
        assertEquals(Recurrence.None, ReminderCodec.decodeRecurrence(""))
        // And an "after" carrying a unit from the future is the same story, not a crash.
        assertEquals(Recurrence.None, ReminderCodec.decodeRecurrence("""{"type":"after","amount":2,"unit":"FORTNIGHTS"}"""))
        // What this build does know still round-trips, unknown fields and all.
        assertEquals(
            Recurrence.After(6, RecurrenceUnit.HOURS),
            ReminderCodec.decodeRecurrence("""{"type":"after","amount":6,"unit":"HOURS","comment":"from a later build"}"""),
        )
    }

    @Test
    fun `a rule survives its conditions being unreadable, because silence is the worse failure`() {
        // Erring towards ringing too often is the right way round: the failure somebody notices
        // is the one that never arrives.
        val raw = """[{"trigger":{"type":"at_date_time","at":"2026-08-27T21:30"},""" +
            """"conditions":[{"type":"phase_of_moon","phase":"waxing"},{"type":"time_window","from":"18:00","to":"22:00"}]}]"""
        val rules = ReminderCodec.decodeRules(raw)

        assertEquals(1, rules.size)
        assertEquals(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 30)), rules[0].trigger)
        assertEquals(listOf(Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0))), rules[0].conditions)
    }
}
