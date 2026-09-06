package dev.rwilco.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Presence
import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.model.presenceAlreadyRang
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Bringing a reminder back from a pause is asking for it again.
 *
 * Reported from the phone: "mientras esté en casa, y sólo los findes" rang on the Thursday and
 * was never answered, so the state had had its say — for good. Paused and resumed on the
 * Sunday, standing in the very place, it rang nothing, and the log said so in as many words
 * ("dropped: state place already rang"). The rule is [Reminder.resumedAt] and it is pinned
 * without a clock in `FiringTest`; what this covers is the half only a database can answer —
 * that the moment is stamped on the way back to ACTIVE, and on that way only.
 */
@RunWith(AndroidJUnit4::class)
class ResumeStampTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as RwilcoApplication
    private val home = Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa")

    @Test
    fun resumingStampsTheMomentAndUnSilencesAStatePlace() = runBlocking {
        val id = UUID.randomUUID().toString()
        val now = app.clock.instant()
        val rang = now.minusSeconds(3 * 86_400)
        app.repository.save(
            Reminder(
                id = id,
                text = "Pause and resume",
                rules = listOf(TriggerRule(home)),
                createdAt = rang.minusSeconds(60),
                updatedAt = rang.minusSeconds(60),
                lastFiredAt = rang,
                lastFiredRule = 0,
            ),
        )
        val owed = app.repository.get(id)
        assertNotNull(owed)
        assertNull("nothing has been paused yet", owed!!.resumedAt)
        assertTrue("a state that rang and was never answered keeps quiet", owed.presenceAlreadyRang(home, 0))

        // Pausing writes no moment: it is not an answer, and it starts nothing.
        app.repository.setStatus(id, Status.PAUSED)
        val paused = app.repository.get(id)!!
        assertEquals(Status.PAUSED, paused.status)
        assertNull("a pause is not a round", paused.resumedAt)

        // Lifting it does, and the old ring stops being the reason anything stays quiet.
        app.repository.setStatus(id, Status.ACTIVE)
        val back = app.repository.get(id)!!
        assertEquals(Status.ACTIVE, back.status)
        assertNotNull("resuming is stamped", back.resumedAt)
        assertTrue(back.resumedAt!! >= rang)
        assertFalse("brought back is asked again", back.presenceAlreadyRang(home, 0))

        app.repository.delete(id)
    }
}
