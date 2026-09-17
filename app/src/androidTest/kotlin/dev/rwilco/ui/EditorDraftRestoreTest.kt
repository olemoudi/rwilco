package dev.rwilco.ui

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Reminder
import dev.rwilco.model.Trigger
import dev.rwilco.ui.editor.EditorViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * A half-written form outlives the process (0.133.0).
 *
 * Process death cannot be staged from inside the process, but what it *is* can: the system asks
 * the form's saved state for a bundle, the process goes, and a new ViewModel is built over that
 * bundle. So this does exactly that — one ViewModel's saved state handed to a second — against
 * the real repository and settings, which is the half `SavedEditorTest` cannot reach on a JVM.
 */
@RunWith(AndroidJUnit4::class)
class EditorDraftRestoreTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as RwilcoApplication

    @Before
    fun anEmptyList() = runBlocking { app.repository.deleteAll() }

    private fun editor(handle: SavedStateHandle, reminderId: String? = null): EditorViewModel {
        lateinit var viewModel: EditorViewModel
        instrumentation.runOnMainSync {
            viewModel = EditorViewModel(
                reminderId, null, null, null, false, null, false, null, null,
                app.repository, app.settingsStore, app.settings, {}, app.clock, handle,
            )
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (!viewModel.state.value.loaded) {
            check(System.currentTimeMillis() < deadline) { "the form never loaded" }
            Thread.sleep(50)
        }
        return viewModel
    }

    /** What the system is handed as the process is put away, and the handle a new process builds over it. */
    @Suppress("RestrictedApi")
    private fun afterProcessDeath(handle: SavedStateHandle): SavedStateHandle {
        lateinit var saved: Bundle
        instrumentation.runOnMainSync { saved = handle.savedStateProvider().saveState() }
        return SavedStateHandle.createHandle(saved, null)
    }

    @Test
    fun aNewReminderHalfWrittenComesBackAsItWas() {
        val handle = SavedStateHandle()
        val first = editor(handle)
        instrumentation.runOnMainSync {
            first.setText("Comprar pan y leche ")
            first.commitTrigger(null, Trigger.Countdown(30))
        }

        val second = editor(afterProcessDeath(handle)).state.value
        assertEquals("Comprar pan y leche ", second.draft.text)
        val countdown = second.draft.rules.single().trigger as Trigger.Countdown
        assertEquals(30, countdown.minutes)
        assertNull("put away, not saved: the half hour has not started", countdown.startedAt)
        assertTrue("so Back asks before throwing the words away", second.dirty)
        assertFalse(second.focusText)
    }

    @Test
    fun anExistingReminderComesBackEditedOverTheRowItWasOpenedOn() {
        val id = UUID.randomUUID().toString()
        runBlocking {
            val now = app.clock.instant()
            app.repository.save(Reminder(id = id, text = "Llamar al fontanero", createdAt = now, updatedAt = now))
        }
        val handle = SavedStateHandle()
        val first = editor(handle, id)
        instrumentation.runOnMainSync { first.setText("Llamar al fontanero y al seguro") }

        val second = editor(afterProcessDeath(handle), id).state.value
        assertEquals("Llamar al fontanero y al seguro", second.draft.text)
        assertEquals("the yardstick is still the row", "Llamar al fontanero", second.initial.text)
        assertTrue(second.dirty)
        assertFalse(second.isNew)
    }

    @Test
    fun aFormNobodyTouchedPutsNothingAway() {
        val handle = SavedStateHandle()
        editor(handle)
        val second = editor(afterProcessDeath(handle)).state.value
        assertEquals("", second.draft.text)
        assertFalse(second.dirty)
    }
}
