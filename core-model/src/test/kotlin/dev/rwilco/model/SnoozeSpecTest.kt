package dev.rwilco.model

import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * A snooze of the person's own: a length, or a day and a moment of it.
 *
 * [Fixtures.now] is Thursday 27 August 2026 at 15:00 in Madrid. The weekend runs from Friday
 * 20:30 to Sunday 22:00 and the parts of the day are 09:00, 17:00 and 20:00 — the defaults.
 */
class SnoozeSpecTest {

    private val now = Fixtures.now
    private val terms = AppSettings().snoozeTerms
    private fun t(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)
    private fun part(part: SnoozePart) = SnoozeHour.Part(part)
    private fun SnoozeSpec.at() = until(now, zone, terms)

    @Test
    fun `a length is counted from the moment it is put off, and whole days keep the clock`() {
        assertEquals(now.plusSeconds(45 * 60), SnoozeSpec.After(45).at())
        assertEquals(local(2026, 8, 29, 15, 0), SnoozeSpec.After(2 * 24 * 60).at(), "two days on, at the same hour")
        assertEquals(local(2026, 8, 28, 16, 30), SnoozeSpec.After(24 * 60 + 90).at())
        // Across the clocks going back (25 October 2026): two days is two days on the wall.
        val beforeTheChange = local(2026, 10, 24, 15, 0)
        assertEquals(local(2026, 10, 26, 15, 0), SnoozeSpec.After(2 * 24 * 60).until(beforeTheChange, zone, terms))
    }

    @Test
    fun `a part of today is an answer only while it is still ahead`() {
        assertEquals(local(2026, 8, 27, 17, 0), SnoozeSpec.On(SnoozeDay.Today, part(SnoozePart.AFTERNOON)).at())
        assertEquals(local(2026, 8, 27, 20, 0), SnoozeSpec.On(SnoozeDay.Today, part(SnoozePart.EVENING)).at())
        assertNull(SnoozeSpec.On(SnoozeDay.Today, part(SnoozePart.MORNING)).at(), "this morning has gone")
        assertNull(SnoozeSpec.On(SnoozeDay.Today, SnoozeHour.At(t(15))).at(), "and so has this very minute")
        assertEquals(local(2026, 8, 27, 21, 30), SnoozeSpec.On(SnoozeDay.Today, SnoozeHour.At(t(21, 30))).at())
    }

    @Test
    fun `tomorrow and a day of the week are always ahead, and follow the person's own hours`() {
        assertEquals(local(2026, 8, 28, 20, 0), SnoozeSpec.On(SnoozeDay.Tomorrow, part(SnoozePart.EVENING)).at())
        assertEquals(local(2026, 8, 31, 8, 0), SnoozeSpec.On(SnoozeDay.Weekday(DayOfWeek.MONDAY), SnoozeHour.At(t(8))).at())
        // Said on a Thursday, "el jueves" is next week's: today's is "hoy".
        assertEquals(local(2026, 9, 3, 9, 0), SnoozeSpec.On(SnoozeDay.Weekday(DayOfWeek.THURSDAY), part(SnoozePart.MORNING)).at())
        val mine = AppSettings(dayStart = t(7, 30), afternoon = t(16), evening = t(21, 30)).snoozeTerms
        assertEquals(local(2026, 8, 28, 7, 30), SnoozeSpec.On(SnoozeDay.Tomorrow, part(SnoozePart.MORNING)).until(now, zone, mine))
        assertEquals(local(2026, 8, 27, 21, 30), SnoozeSpec.On(SnoozeDay.Today, part(SnoozePart.EVENING)).until(now, zone, mine))
    }

    @Test
    fun `the weekend's part of the day is the first one inside the weekend`() {
        // Friday's 20:00 is half an hour before the weekend starts, so "el finde por la noche"
        // is Saturday's; its morning is Saturday's too.
        assertEquals(local(2026, 8, 29, 20, 0), SnoozeSpec.On(SnoozeDay.Weekend, part(SnoozePart.EVENING)).at())
        assertEquals(local(2026, 8, 29, 9, 0), SnoozeSpec.On(SnoozeDay.Weekend, part(SnoozePart.MORNING)).at())
        // Asked from inside it, what is left of it: Saturday at ten, the morning is Sunday's.
        val saturday = local(2026, 8, 29, 10, 0)
        assertEquals(local(2026, 8, 30, 9, 0), SnoozeSpec.On(SnoozeDay.Weekend, part(SnoozePart.MORNING)).until(saturday, zone, terms))
        // And past its last one, next weekend's.
        val sundayNight = local(2026, 8, 30, 21, 0)
        assertEquals(local(2026, 9, 5, 20, 0), SnoozeSpec.On(SnoozeDay.Weekend, part(SnoozePart.EVENING)).until(sundayNight, zone, terms))
        // Somebody whose weekend starts on Friday at six has a Friday evening in it.
        val early = AppSettings(weekendTime = t(18)).snoozeTerms
        assertEquals(local(2026, 8, 28, 20, 0), SnoozeSpec.On(SnoozeDay.Weekend, part(SnoozePart.EVENING)).until(now, zone, early))
    }

    @Test
    fun `a spec travels as a key that says what it is, and anything else is nothing`() {
        val specs = listOf(
            SnoozeSpec.After(45) to "after:45",
            SnoozeSpec.On(SnoozeDay.Today, part(SnoozePart.AFTERNOON)) to "on:today:afternoon",
            SnoozeSpec.On(SnoozeDay.Tomorrow, part(SnoozePart.EVENING)) to "on:tomorrow:evening",
            SnoozeSpec.On(SnoozeDay.Weekend, part(SnoozePart.EVENING)) to "on:weekend:evening",
            SnoozeSpec.On(SnoozeDay.Weekday(DayOfWeek.MONDAY), SnoozeHour.At(t(8))) to "on:mon:08:00",
            SnoozeSpec.On(SnoozeDay.Tomorrow, SnoozeHour.At(t(20, 15))) to "on:tomorrow:20:15",
        )
        for ((spec, key) in specs) {
            assertEquals(key, spec.key)
            assertEquals(spec, snoozeSpecOf(key), key)
        }
        for (garbage in listOf("", "after", "after:", "after:x", "after:0", "after:999999", "on:someday:evening", "on:tomorrow:teatime", "on:tomorrow:25:00", "TEN_MINUTES", "places")) {
            assertNull(snoozeSpecOf(garbage), garbage)
        }
    }

    @Test
    fun `an offer is one of the app's or one of the person's, by the same key`() {
        assertEquals(SnoozeOffer.BuiltIn(Snooze.TWO_HOURS), snoozeOfferOf("TWO_HOURS"))
        assertEquals(SnoozeOffer.Custom(SnoozeSpec.After(45)), snoozeOfferOf("after:45"))
        assertNull(snoozeOfferOf("FROM_A_NEWER_BUILD"))
        assertEquals(Snooze.TWO_HOURS.until(now, zone, terms), SnoozeOffer.BuiltIn(Snooze.TWO_HOURS).until(now, zone, terms))
        assertTrue(SnoozeOffer.Custom(SnoozeSpec.On(SnoozeDay.Today, part(SnoozePart.EVENING))).expires)
        assertFalse(SnoozeOffer.Custom(SnoozeSpec.On(SnoozeDay.Tomorrow, part(SnoozePart.EVENING))).expires)
        assertFalse(SnoozeOffer.BuiltIn(Snooze.WEEKEND).expires)
    }

    @Test
    fun `the board keeps one order whatever the hour, with the person's own among the app's`() {
        val settings = AppSettings(customSnoozes = listOf("on:weekend:evening", "after:45", "on:today:evening", "on:tomorrow:evening", "on:mon:08:00", "after:4320"))
        val keys = snoozeBoard(settings).shown.map { it.key }
        assertEquals(
            listOf(
                "TEN_MINUTES", "CUSTOM", "after:45", "TWO_HOURS", "after:4320", // lengths, shortest first
                "on:today:evening",
                "TOMORROW_MORNING", "on:tomorrow:evening", "TOMORROW",
                "WEEKEND", "on:weekend:evening",
                "on:mon:08:00",
                "NEXT_WEEK",
            ),
            keys,
        )
        // Hidden ones go behind the door, the most used first; a key that reads as nothing is not an offer.
        val hidden = settings.copy(hiddenSnoozes = setOf("after:4320", "NEXT_WEEK", "on:mon:08:00"), snoozeUses = mapOf("on:mon:08:00" to 3, "NEXT_WEEK" to 7), customSnoozes = settings.customSnoozes + "on:someday:never")
        assertEquals(listOf("NEXT_WEEK", "on:mon:08:00", "after:4320"), snoozeBoard(hidden).more.map { it.key })
        assertFalse("on:someday:never" in snoozeBoard(hidden).shown.map { it.key })
    }

    @Test
    fun `one of the person's own is added once, never as a copy of the app's, and leaves nothing behind when it goes`() {
        val evening = SnoozeSpec.On(SnoozeDay.Today, part(SnoozePart.EVENING))
        val added = AppSettings().withCustomSnooze(evening)
        assertEquals(listOf("on:today:evening"), added.customSnoozes)
        assertEquals(added, added.withCustomSnooze(evening), "once")
        // What the app already offers is not the person's to add again: ten minutes, two hours,
        // their own length, tomorrow morning.
        for (copy in listOf(SnoozeSpec.After(10), SnoozeSpec.After(120), SnoozeSpec.After(30), SnoozeSpec.On(SnoozeDay.Tomorrow, part(SnoozePart.MORNING)))) {
            assertEquals(AppSettings(), AppSettings().withCustomSnooze(copy), copy.key)
            assertEquals(CustomSnoozeRefusal.ALREADY_OFFERED, AppSettings().customSnoozeRefusal(copy))
        }
        assertEquals(CustomSnoozeRefusal.ALREADY_YOURS, added.customSnoozeRefusal(evening))
        assertEquals(CustomSnoozeRefusal.OUT_OF_RANGE, AppSettings().customSnoozeRefusal(SnoozeSpec.After(2)))
        assertNull(AppSettings().customSnoozeRefusal(SnoozeSpec.After(45)))

        // Gone from everywhere it was named, and the notification is left with two again.
        val used = AppSettings(customSnoozes = listOf("after:45"), hiddenSnoozes = setOf("after:45"), snoozeUses = mapOf("after:45" to 4), notificationSnoozes = listOf("after:45", "TWO_HOURS"))
        val gone = used.withoutCustomSnooze("after:45")
        assertEquals(emptyList<String>(), gone.customSnoozes)
        assertEquals(emptySet<String>(), gone.hiddenSnoozes)
        assertEquals(emptyMap<String, Int>(), gone.snoozeUses)
        assertEquals(listOf("TWO_HOURS", "TEN_MINUTES"), gone.notificationOffers.map { it.key })
    }

    @Test
    fun `the notification carries two that cannot go stale, the person's own among them`() {
        val settings = AppSettings(customSnoozes = listOf("after:45", "on:today:evening", "on:tomorrow:evening"))
        assertEquals(listOf("TEN_MINUTES", "TWO_HOURS"), settings.notificationOffers.map { it.key })
        val picked = settings.withNotificationSnooze("after:45")
        assertEquals(listOf("TWO_HOURS", "after:45"), picked.notificationOffers.map { it.key })
        assertEquals(picked, picked.withNotificationSnooze("after:45"), "one already there changes nothing")
        // A card can sit in the shade for hours: "esta noche" on it would be a button about a
        // night already gone. Not pickable, and dropped if it ever got there.
        assertEquals(picked, picked.withNotificationSnooze("on:today:evening"))
        assertEquals(listOf("TWO_HOURS", "TEN_MINUTES"), picked.copy(notificationSnoozes = listOf("on:today:evening", "TWO_HOURS")).notificationOffers.map { it.key })
        // And one from a newer build, or one since deleted, reads as not there.
        assertEquals(listOf("TEN_MINUTES", "TWO_HOURS"), settings.copy(notificationSnoozes = listOf("FROM_THE_FUTURE", "after:99")).notificationOffers.map { it.key })
        assertEquals(settings, settings.withNotificationSnooze("after:99"))
    }

    @Test
    fun `deleting one of the person's own can be undone whole`() {
        val settings = AppSettings(
            customSnoozes = listOf("after:45", "on:today:evening", "on:weekend:evening"),
            hiddenSnoozes = setOf("on:today:evening", "WEEKEND"),
            snoozeUses = mapOf("on:today:evening" to 6, "after:45" to 1),
            notificationSnoozes = listOf("after:45", "TWO_HOURS"),
        )
        for (key in settings.customSnoozes) {
            val removed = settings.removedCustomSnooze(key)!!
            assertEquals(settings, settings.withoutCustomSnooze(key).withCustomSnoozeBack(removed), key)
        }
        assertNull(settings.removedCustomSnooze("after:50"), "not one of theirs")
        assertEquals(settings, settings.withoutCustomSnooze("after:50"))
        // Undone twice, or after it was added again by hand, it is there once.
        val removed = settings.removedCustomSnooze("after:45")!!
        assertEquals(settings, settings.withCustomSnoozeBack(removed))
    }

    @Test
    fun `the person's own are capped, because the alert is one screen`() {
        val full = (1..MAX_CUSTOM_SNOOZES).fold(AppSettings()) { settings, n -> settings.withCustomSnooze(SnoozeSpec.After(200 + n)) }
        assertEquals(MAX_CUSTOM_SNOOZES, full.customSnoozes.size)
        assertEquals(CustomSnoozeRefusal.TOO_MANY, full.customSnoozeRefusal(SnoozeSpec.After(300)))
        assertEquals(full, full.withCustomSnooze(SnoozeSpec.After(300)))
    }

    @Test
    fun `a part of today that has gone is not held out, and nothing else ever leaves`() {
        val settings = AppSettings(customSnoozes = listOf("on:today:morning", "on:today:evening", "on:tomorrow:evening"), hiddenSnoozes = setOf("on:today:morning", "NEXT_WEEK"))
        val board = snoozeBoard(settings)
        val standing = board.standingAt(now, zone, settings.snoozeTerms)
        assertEquals(board.shown, standing.shown, "at three in the afternoon the evening is still ahead")
        assertEquals(listOf("NEXT_WEEK"), standing.more.map { it.key }, "the morning is not")
        val night = board.standingAt(local(2026, 8, 27, 22, 0), zone, settings.snoozeTerms)
        assertFalse("on:today:evening" in night.shown.map { it.key })
        assertTrue("on:tomorrow:evening" in night.shown.map { it.key })
        // With none of those, the very same board.
        val plain = snoozeBoard(AppSettings())
        assertTrue(plain === plain.standingAt(now, zone, terms))
    }

    @Test
    fun `every offer there can be has a number of its own, and the app's keep the one they had`() {
        val days = listOf(SnoozeDay.Today, SnoozeDay.Tomorrow, SnoozeDay.Weekend) + DayOfWeek.entries.map(SnoozeDay::Weekday)
        val hours = SnoozePart.entries.map(SnoozeHour::Part) + (0 until 24 * 60).map { SnoozeHour.At(LocalTime.of(it / 60, it % 60)) }
        val offers = Snooze.entries.map(SnoozeOffer::BuiltIn) +
            SnoozeLimits.AFTER_MINUTES.map { SnoozeOffer.Custom(SnoozeSpec.After(it)) } +
            days.flatMap { day -> hours.map { hour -> SnoozeOffer.Custom(SnoozeSpec.On(day, hour)) } }
        assertEquals(offers.size, offers.map { it.code }.toSet().size, "no two alike")
        assertTrue(offers.none { it.code == 0 }, "zero is the button that is not a snooze")
        // A card posted by the build before this one carries `ordinal + 1`, and still means it.
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), Snooze.entries.map { SnoozeOffer.BuiltIn(it).code })
        // And every one of them survives the trip as a key.
        assertEquals(offers, offers.map { snoozeOfferOf(it.key) })
    }

    @Test
    fun `a use is counted for any offer there is, the person's own included`() {
        val settings = AppSettings(customSnoozes = listOf("after:45"))
        assertEquals(mapOf("after:45" to 1), settings.withSnoozeUsed("after:45").snoozeUses)
        assertEquals(emptyMap<String, Int>(), settings.withSnoozeUsed("after:46").snoozeUses, "not one of theirs")
        assertEquals(setOf("after:45"), settings.withSnoozeShown("after:45", shown = false).hiddenSnoozes)
    }
}
