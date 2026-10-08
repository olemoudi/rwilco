package dev.rwilco.alarm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Action
import dev.rwilco.model.Reminder
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime

/**
 * The half of ringing before the first unlock (0.172.0) a device can answer without rebooting:
 * that every re-arm leaves the next moment where a locked phone can read it — device-protected
 * storage — and that the words never go there. The reboot itself is proved by hand (`adb reboot`
 * with a PIN set, before unlocking); see TODO.md.
 */
@RunWith(AndroidJUnit4::class)
class LockedMirrorDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as RwilcoApplication
    private val words = "Pastillas de la tarde (prueba de arranque bloqueado)"

    @Before
    fun empty() = runBlocking { app.repository.deleteAll() }

    @After
    fun tidy() = runBlocking { app.repository.deleteAll() }

    @Test
    fun theNextMomentIsMirroredWhereALockedPhoneCanReadItAndTheWordsAreNot() = runBlocking {
        val at = LocalDateTime.now().plusHours(3).withSecond(0).withNano(0)
        val loud = Reminder(
            id = "locked-mirror",
            text = words,
            rules = listOf(TriggerRule(Trigger.AtDateTime(at))),
            actions = setOf(Action.FULL_SCREEN, Action.NOTIFICATION, Action.SOUND),
            createdAt = app.clock.instant(),
            updatedAt = app.clock.instant(),
        )
        app.repository.save(loud)
        app.scheduler.rearmAll()

        val wake = LockedMirror.read(context).wakes.singleOrNull { it.id == loud.id }
        assertNotNull("the next moment was not mirrored", wake)
        assertEquals(at.atZone(app.clock.zone).toInstant().toEpochMilli(), wake!!.at)

        val file = File(context.createDeviceProtectedStorageContext().filesDir, "locked-wakes.json")
        assertTrue("the mirror is not in device-protected storage", file.isFile)
        assertFalse("the words went into storage a locked phone can read", file.readText().contains("Pastillas"))
    }

    @Test
    fun aDoneReminderLeavesTheMirror() = runBlocking {
        val loud = Reminder(
            id = "locked-gone",
            text = words,
            rules = listOf(TriggerRule(Trigger.AtDateTime(LocalDateTime.now().plusHours(3)))),
            createdAt = app.clock.instant(),
            updatedAt = app.clock.instant(),
        )
        app.repository.save(loud)
        app.scheduler.rearmAll()
        app.repository.delete(loud.id)
        app.scheduler.rearmAll()
        assertTrue(LockedMirror.read(context).wakes.none { it.id == loud.id })
    }
}
