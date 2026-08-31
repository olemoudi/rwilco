package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertFalse
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
        Trigger.Location(40.4168, -3.7038, 200, Presence.INSIDE, "Casa"),
        Trigger.Random(2, Period.DAY, LocalTime.of(10, 0), LocalTime.of(20, 0), emptySet()),
        Trigger.Random(1, Period.WEEK, LocalTime.of(18, 0), LocalTime.of(21, 0), setOf(DayOfWeek.SATURDAY)),
        Trigger.DayRandom(LocalDate.of(2026, 9, 3)),
        Trigger.Repeat(LocalDate.of(2026, 8, 26), 2, RepeatUnit.WEEK, LocalTime.of(19, 0), setOf(DayOfWeek.WEDNESDAY)),
        Trigger.Repeat(LocalDate.of(2026, 8, 26), 1, RepeatUnit.MONTH, null, monthly = MonthlyOn.Nth(4, DayOfWeek.WEDNESDAY)),
        Trigger.Repeat(LocalDate.of(2026, 8, 26), 1, RepeatUnit.YEAR, LocalTime.of(9, 0), ends = RepeatEnd.After(30)),
        Trigger.Repeat(LocalDate.of(2026, 8, 26), 1, RepeatUnit.DAY, LocalTime.of(9, 0), ends = RepeatEnd.On(LocalDate.of(2027, 1, 1))),
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
    fun `the new discriminators are frozen too`() {
        val rules = listOf(
            TriggerRule(Trigger.DayRandom(LocalDate.of(2026, 9, 3))),
            TriggerRule(Trigger.Repeat(LocalDate.of(2026, 8, 26), 2, RepeatUnit.WEEK, LocalTime.of(19, 0), setOf(DayOfWeek.WEDNESDAY))),
        )
        val expected = """[""" +
            """{"trigger":{"type":"day_random","date":"2026-09-03","window":null},"conditions":[]},""" +
            """{"trigger":{"type":"repeat","startsOn":"2026-08-26","every":2,"unit":"WEEK","time":"19:00",""" +
            """"days":["WEDNESDAY"],"monthly":null,"ends":{"type":"never"},"window":null},"conditions":[]}""" +
            """]"""
        assertEquals(expected, ReminderCodec.encodeRules(rules))
    }

    @Test
    fun `a repeat or a date written before windows existed still reads`() {
        // The additive half of the frozen shape: a new optional field must never stop an older
        // phone's JSON decoding, or an update empties the reminder instead of widening it.
        val before = """[{"trigger":{"type":"day_random","date":"2026-09-03"},"conditions":[]},""" +
            """{"trigger":{"type":"repeat","startsOn":"2026-08-26","every":2,"unit":"WEEK","time":"19:00",""" +
            """"days":["WEDNESDAY"],"monthly":null,"ends":{"type":"never"}},"conditions":[]}]"""
        val read = ReminderCodec.decodeRules(before)
        assertEquals(Trigger.DayRandom(LocalDate.of(2026, 9, 3), window = null), read[0].trigger)
        assertEquals(null, (read[1].trigger as Trigger.Repeat).window)
    }

    @Test
    fun `a window survives the round trip on both of the shapes that can hold one`() {
        val lunch = DayWindow(LocalTime.of(14, 0), LocalTime.of(16, 0))
        val rules = listOf(
            TriggerRule(Trigger.DayRandom(LocalDate.of(2026, 9, 3), lunch)),
            TriggerRule(Trigger.Repeat(LocalDate.of(2026, 8, 26), unit = RepeatUnit.DAY, window = lunch)),
        )
        assertEquals(rules, ReminderCodec.decodeRules(ReminderCodec.encodeRules(rules)))
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
        // No snooze keys at all (a blob from before 0.47.0): the defaults, not a failure.
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
    fun `a snooze offer from another build is dropped, not fatal to every setting`() {
        val blob = """{"theme":"DARK","notificationSnoozes":["TEN_MINUTES","SOMETHING_ELSE"]}"""
        val settings = ReminderCodec.decodeSettings(blob)
        // The whole blob is decoded at once, so a name this build has no member for used to
        // take the theme, the sound, the presets and the saved places down with it.
        assertEquals(ThemeMode.DARK, settings.theme)
        assertEquals(listOf(Snooze.TEN_MINUTES), settings.notificationSnoozeOffers)
        // And nothing recognisable at all falls back rather than leaving a notification with
        // no way to postpone.
        val none = ReminderCodec.decodeSettings("""{"notificationSnoozes":["SOMETHING_ELSE"]}""")
        assertEquals(DEFAULT_NOTIFICATION_SNOOZES, none.notificationSnoozeOffers)
    }

    @Test
    fun `a preset shaped by a newer build costs a rule, never every setting`() {
        // The settings are decoded in one go, so an unreadable trigger inside a preset used to
        // throw in the middle of the object and hand back the defaults — theme, sound, saved
        // places and all. A vault restored on an older build is exactly that path.
        val blob = """{"theme":"DARK","presets":[{"id":"p1","name":"Ma\u00f1ana","createdAt":"2026-08-27T13:00:00Z",""" +
            """"rules":[{"trigger":{"type":"from_the_future","whatever":3}},{"trigger":{"type":"countdown","minutes":30}}],""" +
            """"recurrence":{"type":"a_shape_from_2030"}}]}"""
        val settings = ReminderCodec.decodeSettings(blob)
        assertEquals(ThemeMode.DARK, settings.theme)
        val preset = settings.presets.single()
        assertEquals("Mañana", preset.name)
        assertEquals(listOf(Trigger.Countdown(30)), preset.rules.map { it.trigger })
        assertEquals(Recurrence.None, preset.recurrence)
    }

    @Test
    fun `settings encode every field so a reader can rely on presence`() {
        val encoded = ReminderCodec.encodeSettings(AppSettings())
        assertEquals(
            """{"theme":"SYSTEM","defaultTime":"09:00","haptics":true,"defaultTriggerKind":null,""" +
                """"popularTriggersFirst":false,""" +
                """"weekendDay":"FRIDAY","weekendTime":"20:30","weekendEndDay":"SUNDAY","weekendEndTime":"22:00",""" +
                """"awake":{"wake":"08:00","sleep":"23:30","weekendWake":"10:00","weekendSleep":"01:30"},""" +
                """"lastSeenVersionCode":0,"savedPlaces":[],"savedWindows":[],""" +
                """"defaultActions":["NOTIFICATION","VIBRATE"],"presets":[],"hiddenTexts":[],""" +
                """"dayStart":"09:00","recurrencePresets":[""" +
                """{"id":"builtin-day","recurrence":{"type":"after","amount":1,"unit":"DAYS","from":"DEALT","hour":{"type":"day_start"},"landing":"NEXT"},"name":"","uses":0,"lastUsedAt":null},""" +
                """{"id":"builtin-6h","recurrence":{"type":"after","amount":6,"unit":"HOURS","from":"DEALT","hour":{"type":"day_start"},"landing":"NEXT"},"name":"","uses":0,"lastUsedAt":null},""" +
                """{"id":"builtin-week","recurrence":{"type":"after","amount":1,"unit":"WEEKS","from":"DEALT","hour":{"type":"day_start"},"landing":"NEXT"},"name":"","uses":0,"lastUsedAt":null},""" +
                """{"id":"builtin-month","recurrence":{"type":"after","amount":1,"unit":"MONTHS","from":"DEALT","hour":{"type":"day_start"},"landing":"NEXT"},"name":"","uses":0,"lastUsedAt":null}],""" +
                """"busyWatchNotice":false,"vibration":{"strength":"STRONG","rhythm":"PULSED"},""" +
                """"alertSound":{"type":"system"},"insistentSound":null,"soundPlays":5,"soundGapMinutes":5,"alertStacking":"SEQUENTIAL","updatesWifiOnly":false,"alertToHeadphones":true,"safetyNet":{"afterHours":24,"minCadenceMinutes":60,"fraction":10},""" +
                """"snoozeCustomMinutes":30,"notificationSnoozes":["TEN_MINUTES","TWO_HOURS"],"dismissedAlertProblems":[]}""",
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

    // ---- a place, whose reading changed without its bytes changing ----------------------

    @Test
    fun `a place still writes the word every phone already has on disk`() {
        // Presence.INSIDE is what "al entrar" became, and the key and the value it is stored
        // under are frozen: a build that renamed either would silently drop every place
        // reminder in the world on the next start.
        val rule = TriggerRule(Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa"))
        val json = ReminderCodec.encodeRules(listOf(rule))
        assertTrue(json.contains("\"transition\":\"ENTER\""), json)
        assertTrue(json.contains("\"type\":\"location\""), json)
        assertEquals(listOf(rule), ReminderCodec.decodeRules(json))

        val leaving = TriggerRule(Trigger.Location(40.4169, -3.7035, 200, Presence.OUTSIDE, "Casa"))
        assertTrue(ReminderCodec.encodeRules(listOf(leaving)).contains("\"transition\":\"EXIT\""))
    }

    @Test
    fun `a place written before the doorway was a choice reads as a state`() {
        // The whole of the migration: a row with no `onCrossing` key is "mientras estoy",
        // which is what people meant by "al llegar" almost every time they wrote one.
        val old = """[{"trigger":{"type":"location","lat":40.4169,"lng":-3.7035,"radiusM":200,"transition":"ENTER","label":"Casa"}}]"""
        val rules = ReminderCodec.decodeRules(old)
        val place = rules.single().trigger as Trigger.Location
        assertEquals(Presence.INSIDE, place.presence)
        assertFalse(place.onCrossing, "an old place must not come back asking for a doorway")
    }

    @Test
    fun `asking for the doorway survives the trip`() {
        val rule = TriggerRule(Trigger.Location(40.4169, -3.7035, 200, Presence.OUTSIDE, "Casa", onCrossing = true))
        assertEquals(listOf(rule), ReminderCodec.decodeRules(ReminderCodec.encodeRules(listOf(rule))))
    }
}
