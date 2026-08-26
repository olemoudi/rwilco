package dev.rwilco.vault

import dev.rwilco.data.NO_RECURRENCE
import dev.rwilco.data.ReminderEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/** What the badge on Home counts, and the two ways it must not lie. */
class VaultPendingTest {

    private val copiedAt = Instant.parse("2026-08-26T10:00:00Z")
    private val settings = """{"theme":"SYSTEM"}"""

    private fun row(id: String, updatedAt: Instant, text: String = "Regar las plantas") = ReminderEntity(
        id = id,
        text = text,
        tags = "[]",
        triggers = "[]",
        actions = "[]",
        status = "ACTIVE",
        createdAt = updatedAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
        doneAt = null,
        recurrence = NO_RECURRENCE,
    )

    private val rows = listOf(row("a", copiedAt.minusSeconds(3600)), row("b", copiedAt.minusSeconds(60)))

    /** A vault that has just copied exactly these rows. */
    private fun copied(rows: List<ReminderEntity> = this.rows, settings: String = this.settings) = VaultState(
        enabled = true,
        owner = "ole",
        repo = "vault",
        key = "a2V5",
        salt = "c2FsdA==",
        lastUploadedFingerprint = fingerprint(rows, settings),
        lastUploadedAt = copiedAt,
        lastUploadedSettingsHash = settingsHash(settings),
    )

    @Test
    fun `nothing to copy is no badge at all`() {
        assertEquals(0, pendingChanges(rows, settings, copied()))
    }

    @Test
    fun `the backup off is no badge, however much has changed`() {
        assertEquals(0, pendingChanges(rows + row("c", copiedAt.plusSeconds(60)), settings, VaultState()))
    }

    @Test
    fun `a reminder written or edited since the copy counts`() {
        val written = rows + row("c", copiedAt.plusSeconds(60))
        assertEquals(1, pendingChanges(written, settings, copied()))
        val alsoEdited = written.map { if (it.id == "a") it.copy(updatedAt = copiedAt.plusSeconds(120).toEpochMilli(), text = "Otra cosa") else it }
        assertEquals(2, pendingChanges(alsoEdited, settings, copied()))
    }

    @Test
    fun `a setting somebody changed counts as one`() {
        assertEquals(1, pendingChanges(rows, """{"theme":"DARK"}""", copied()))
    }

    @Test
    fun `a deletion has nothing to count and still says one`() {
        // Nothing left carries a stamp newer than the copy, but the copy is out of date all the
        // same — and a badge that says nothing while a copy is owed is the one thing it cannot do.
        assertEquals(1, pendingChanges(rows.drop(1), settings, copied()))
    }

    @Test
    fun `a vault that has never copied counts everything`() {
        val fresh = copied().copy(lastUploadedFingerprint = null, lastUploadedAt = null, lastUploadedSettingsHash = null)
        assertEquals(2, pendingChanges(rows, settings, fresh))
        assertEquals(1, pendingChanges(emptyList(), settings, fresh), "an empty phone still owes its first copy")
    }
}
