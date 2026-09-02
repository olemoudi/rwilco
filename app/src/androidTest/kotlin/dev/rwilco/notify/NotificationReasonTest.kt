package dev.rwilco.notify

import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.model.Action
import dev.rwilco.model.Condition
import dev.rwilco.model.FiringPlan
import dev.rwilco.model.Presence
import dev.rwilco.model.Reminder
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalTime

/**
 * What the line under the title says.
 *
 * It used to be the reminder's own text, which the title already carries: the same sentence
 * twice, and the second one saying nothing. It is now **why this arrived** — the sentence the
 * form shows over its save button — and only a real notification can say whether it survived
 * the trip through the builder, the channel and the shade.
 */
@RunWith(AndroidJUnit4::class)
class NotificationReasonTest {

    @get:Rule
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val plan = FiringPlan(fullScreen = false, notification = true, sound = false, vibrate = false)

    @Before
    fun clean() = manager.cancelAll()

    @After
    fun tidy() = manager.cancelAll()

    private fun reminder(vararg rules: TriggerRule, match: RuleMatch = RuleMatch.ANY, tags: List<String> = emptyList()) =
        Reminder(
            id = "reason-test",
            text = "Organizar fotos",
            tags = tags,
            rules = rules.toList(),
            ruleMatch = match,
            actions = setOf(Action.NOTIFICATION),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            lastFiredAt = Instant.now(),
        )

    /** Posting is a binder hop; the shade has it a moment later, not on the next line. */
    private fun postAndRead(reminder: Reminder): Notification {
        manager.cancelAll()
        AlertNotifications.post(context, reminder, plan, late = null, fullScreen = false)
        var posted: Notification? = null
        val deadline = System.currentTimeMillis() + 5_000
        while (posted == null && System.currentTimeMillis() < deadline) {
            posted = manager.activeNotifications
                .firstOrNull { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) == "Organizar fotos" }
                ?.notification
            if (posted == null) Thread.sleep(50)
        }
        assertNotNull("nothing was posted", posted)
        return posted!!
    }

    private fun Notification.line(key: String): String? = extras.getCharSequence(key)?.toString()

    @Test
    fun theLineUnderTheTitleSaysWhyRatherThanRepeatingTheTitle() {
        val notification = postAndRead(reminder(TriggerRule(Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa", onCrossing = true))))
        val text = notification.line(Notification.EXTRA_TEXT)
        assertNotNull("no line under the title", text)
        assertTrue("the title said itself twice: $text", text != "Organizar fotos")
        assertTrue("the reason did not name the place: $text", text!!.contains("Casa"))
    }

    @Test
    fun twoRulesAreJoinedTheWayTheirReadingJoinsThem() {
        val place = TriggerRule(Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa", onCrossing = true))
        val clock = TriggerRule(Trigger.AtTime(LocalTime.of(18, 30), emptySet()))
        val either = postAndRead(reminder(place, clock, match = RuleMatch.ANY)).line(Notification.EXTRA_TEXT)!!
        val both = postAndRead(reminder(place, clock, match = RuleMatch.ALL)).line(Notification.EXTRA_TEXT)!!
        assertTrue("no place in \"$either\"", either.contains("Casa"))
        assertTrue("no hour in \"$either\"", either.contains("18:30") || either.contains("6:30"))
        assertTrue("the two readings read the same: \"$either\" / \"$both\"", either != both)
    }

    @Test
    fun aFenceIsSaidAsTheSentenceSaysIt() {
        val rule = TriggerRule(
            Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa", onCrossing = true),
            conditions = listOf(Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0))),
        )
        val text = postAndRead(reminder(rule)).line(Notification.EXTRA_TEXT)!!
        assertTrue("no window in \"$text\"", text.contains("18:00") || text.contains("6:00"))
    }

    /** Expanded, the card carries the whole words and then the reason: a long reminder is readable from the shade. */
    @Test
    fun expandedTheCardShowsTheWordsWholeAndThenTheReason() {
        val rule = TriggerRule(Trigger.AtDateTime(java.time.LocalDateTime.of(2026, 9, 3, 9, 0)))
        val big = postAndRead(reminder(rule)).line(Notification.EXTRA_BIG_TEXT)!!
        assertTrue("the words come first: $big", big.startsWith("Organizar fotos"))
        assertTrue("and the reason under them: $big", big.contains("\n") && big.contains("9:00"))
    }

    @Test
    fun theTagsMoveOutOfTheWayOfTheReason() {
        val notification = postAndRead(
            reminder(TriggerRule(Trigger.AtTime(LocalTime.of(9, 0), emptySet())), tags = listOf("casa", "fotos")),
        )
        assertEquals("casa · fotos", notification.line(Notification.EXTRA_SUB_TEXT))
        assertTrue("the reason lost its line to the tags", notification.line(Notification.EXTRA_TEXT)!!.contains("9:00"))
    }

    @Test
    fun aReminderWithNoRulesLeavesTheLineOffRatherThanRepeatingItself() {
        val notification = postAndRead(reminder())
        assertEquals(null, notification.line(Notification.EXTRA_TEXT))
    }
}
