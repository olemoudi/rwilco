package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalTime

class PresetTest {

    private fun preset(
        id: String,
        colorIndex: Int = 0,
        uses: Int = 0,
        lastUsedAt: java.time.Instant? = null,
        createdAt: java.time.Instant = now,
    ) = Preset(id = id, name = id, colorIndex = colorIndex, uses = uses, lastUsedAt = lastUsedAt, createdAt = createdAt)

    @Test
    fun `a new preset takes the least spoken-for colour`() {
        assertEquals(0, nextPresetColor(emptyList()))
        assertEquals(1, nextPresetColor(listOf(preset("a", colorIndex = 0))))
        val oneOfEach = List(PRESET_COLORS) { preset("p$it", colorIndex = it) }
        assertEquals(0, nextPresetColor(oneOfEach), "with every colour taken once it starts over")
        assertEquals(1, nextPresetColor(oneOfEach + preset("extra", colorIndex = 0)))
    }

    @Test
    fun `a colour from a build with a bigger palette does not break the count`() {
        assertEquals(0, nextPresetColor(listOf(preset("a", colorIndex = 99))))
    }

    @Test
    fun `most used first, and among equals the most recently used`() {
        val old = preset("old", uses = 3, lastUsedAt = now.minusSeconds(90_000))
        val hot = preset("hot", uses = 3, lastUsedAt = now)
        val once = preset("once", uses = 1, lastUsedAt = now)
        val never = preset("never", createdAt = now.plusSeconds(10))
        assertEquals(
            listOf("hot", "old", "once", "never"),
            presetsByPopularity(listOf(once, old, never, hot)).map { it.id },
        )
    }

    @Test
    fun `a preset never used falls back to when it was made`() {
        val older = preset("older", createdAt = now.minusSeconds(1_000))
        val newer = preset("newer", createdAt = now)
        assertEquals(listOf("newer", "older"), presetsByPopularity(listOf(older, newer)).map { it.id })
    }

    @Test
    fun `using one counts it and stamps the moment`() {
        val used = preset("p").used(now)
        assertEquals(1, used.uses)
        assertEquals(now, used.lastUsedAt)
        assertEquals(2, used.used(now).uses)
    }

    @Test
    fun `a preset becomes a reminder with everything it was keeping`() {
        val source = Preset(
            id = "p1",
            name = "Pastillas",
            tags = listOf("salud"),
            rules = listOf(TriggerRule(Trigger.AtTime(LocalTime.of(9, 0), setOf(java.time.DayOfWeek.MONDAY)))),
            ruleMatch = RuleMatch.ALL,
            actions = setOf(Action.SOUND),
            createdAt = now,
        )
        val reminder = source.toReminder(id = "r1", now = now, words = "Pastillas de la tensión")
        assertEquals("Pastillas de la tensión", reminder.text)
        assertEquals("", source.toReminder(id = "r2", now = now).text, "no default words, and the name is not the words")
        assertEquals(
            "Las de la tensión",
            source.copy(text = "Las de la tensión").toReminder(id = "r3", now = now).text,
            "a preset with default words hands them over",
        )
        assertEquals(listOf("salud"), reminder.tags)
        assertEquals(source.rules, reminder.rules)
        assertEquals(RuleMatch.ALL, reminder.ruleMatch)
        assertEquals(setOf(Action.SOUND), reminder.actions)
        assertEquals(Status.ACTIVE, reminder.status)
        // Nothing of the preset's own bookkeeping comes along.
        assertTrue(reminder.firedRules.isEmpty())
        assertEquals(null, reminder.lastFiredAt)
    }

    @Test
    fun `one use can say what it does without changing the shape`() {
        val source = Preset(
            id = "p1",
            name = "Pastillas",
            actions = setOf(Action.NOTIFICATION, Action.VIBRATE),
            createdAt = now,
        )
        val louder = source.toReminder(
            id = "r1",
            now = now,
            words = "Las de la tensión",
            actions = setOf(Action.NOTIFICATION, Action.VIBRATE, Action.FULL_SCREEN),
        )
        assertEquals(setOf(Action.NOTIFICATION, Action.VIBRATE, Action.FULL_SCREEN), louder.actions)
        // The shape itself is untouched, and the next use of it is what it always was.
        assertEquals(setOf(Action.NOTIFICATION, Action.VIBRATE), source.actions)
        assertEquals(setOf(Action.NOTIFICATION, Action.VIBRATE), source.toReminder(id = "r2", now = now).actions)
    }

    @Test
    fun `the two sound tiles are one choice, wherever they are offered`() {
        val none = emptySet<Action>()
        assertEquals(setOf(Action.SOUND), none.toggling(Action.SOUND))
        // Asking for the other puts the first away rather than asking for both.
        assertEquals(setOf(Action.SOUND_UNTIL_ANSWERED), setOf(Action.SOUND).toggling(Action.SOUND_UNTIL_ANSWERED))
        assertEquals(setOf(Action.SOUND), setOf(Action.SOUND_UNTIL_ANSWERED).toggling(Action.SOUND))
        // Everything else is a plain on and off, and nothing else is disturbed.
        assertEquals(
            setOf(Action.SOUND, Action.VIBRATE),
            setOf(Action.SOUND).toggling(Action.VIBRATE),
        )
        assertEquals(setOf(Action.SOUND), setOf(Action.SOUND, Action.VIBRATE).toggling(Action.VIBRATE))
        assertEquals(none, setOf(Action.SOUND).toggling(Action.SOUND))
    }

    @Test
    fun `presets and the new settings survive a round trip through json`() {
        val settings = AppSettings(
            defaultActions = setOf(Action.FULL_SCREEN),
            hiddenTexts = listOf("Comprar pan"),
            presets = listOf(
                Preset(
                    id = "p1",
                    name = "Sacar la basura",
                    tags = listOf("casa"),
                    rules = listOf(TriggerRule(Trigger.AtTime(LocalTime.of(21, 30), emptySet()))),
                    actions = setOf(Action.NOTIFICATION),
                    colorIndex = 3,
                    uses = 5,
                    lastUsedAt = now,
                    createdAt = now.minusSeconds(500),
                ),
            ),
        )
        assertEquals(settings, ReminderCodec.decodeSettings(ReminderCodec.encodeSettings(settings)))
    }
}
