package dev.rwilco.vault

import dev.rwilco.data.NO_RECURRENCE
import dev.rwilco.data.ReminderEntity
import dev.rwilco.model.AppSettings
import dev.rwilco.model.Preset
import dev.rwilco.model.ReminderCodec
import dev.rwilco.model.SavedPlace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class VaultSnapshotTest {

    private fun row(id: String, text: String = "Water the plants", status: String = "ACTIVE", armedFor: Long? = null) = ReminderEntity(
        id = id,
        text = text,
        tags = """["casa"]""",
        triggers = """[{"trigger":{"type":"on_date","date":"2026-09-01"},"conditions":[]}]""",
        actions = """["NOTIFICATION"]""",
        status = status,
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_000_000,
        doneAt = null,
        armedFor = armedFor,
        armedRule = armedFor?.let { 0 },
        recurrence = NO_RECURRENCE,
    )

    private val settings = ReminderCodec.encodeSettings(
        AppSettings(
            presets = listOf(Preset(id = "p1", name = "Basura", createdAt = Instant.ofEpochMilli(1))),
            savedPlaces = listOf(SavedPlace("Casa", 40.4, -3.7, 150)),
        ),
    )
    private val rows = listOf(row("b"), row("a", status = "DONE"), row("c", status = "PAUSED"))

    @Test
    fun `the fingerprint ignores what the scheduler writes back`() {
        val armed = rows.map { it.copy(armedFor = 1_800_000_000_000, armedRule = 0) }
        assertEquals(fingerprint(rows, settings), fingerprint(armed, settings))
    }

    @Test
    fun `the fingerprint ignores the order the rows came in`() {
        assertEquals(fingerprint(rows, settings), fingerprint(rows.reversed(), settings))
    }

    @Test
    fun `the fingerprint ignores the stamps of one particular export`() {
        val one = buildSnapshot(rows, settings, Instant.ofEpochMilli(1), "device-1", 33, 5)
        val two = buildSnapshot(rows, settings, Instant.ofEpochMilli(2), "device-2", 34, 6)
        assertEquals(one.fingerprint(), two.fingerprint())
    }

    @Test
    fun `the fingerprint changes with the content`() {
        val base = fingerprint(rows, settings)
        assertNotEquals(base, fingerprint(rows.map { if (it.id == "b") it.copy(text = "Feed the cat") else it }, settings))
        assertNotEquals(base, fingerprint(rows.drop(1), settings))
        assertNotEquals(base, fingerprint(rows, ReminderCodec.encodeSettings(AppSettings())))
        assertNotEquals(base, fingerprint(rows.map { if (it.id == "c") it.copy(lastFiredAt = 5) else it }, settings))
    }

    @Test
    fun `a snapshot round-trips byte for byte`() {
        val snapshot = buildSnapshot(rows, settings, Instant.ofEpochMilli(1_700_000_000_000), "device-1", 33, 5)
        val back = decodeSnapshot(encodeSnapshot(snapshot))
        assertEquals(snapshot, back)
        assertEquals(listOf("a", "b", "c"), back.reminders.map { it.id })
    }

    @Test
    fun `a snapshot with things this build does not know still reads`() {
        val text = String(encodeSnapshot(buildSnapshot(rows, settings, Instant.EPOCH, "d", 1, 5)))
            .replaceFirst("{\"schema\"", "{\"future\":true,\"schema\"")
            .replaceFirst("\"id\":\"a\"", "\"id\":\"a\",\"mood\":3")
        val back = decodeSnapshot(text.toByteArray())
        assertEquals(3, back.reminders.size)
    }

    @Test
    fun `a nullable column left out reads as null`() {
        val text = String(encodeSnapshot(buildSnapshot(rows, settings, Instant.EPOCH, "d", 1, 5)))
        assertFalse(text.contains("\"doneAt\""), "explicitNulls is off, so nulls are not written")
        assertTrue(decodeSnapshot(text.toByteArray()).reminders.all { it.doneAt == null })
    }

    @Test
    fun `a snapshot from a newer data version is refused`() {
        val text = String(encodeSnapshot(buildSnapshot(rows, settings, Instant.EPOCH, "d", 1, 5)))
            .replaceFirst("\"schema\":1", "\"schema\":${VAULT_SCHEMA + 1}")
        assertThrows(VaultException.NewerThanThisApp::class.java) { decodeSnapshot(text.toByteArray()) }
    }

    @Test
    fun `what is not a snapshot is corruption`() {
        assertThrows(VaultException.Corrupt::class.java) { decodeSnapshot("[]".toByteArray()) }
        assertThrows(VaultException.Corrupt::class.java) { decodeSnapshot("{}".toByteArray()) }
        assertThrows(VaultException.Corrupt::class.java) { decodeSnapshot("{\"schema\":1,\"reminders\":[{\"id\":1}]}".toByteArray()) }
    }

    @Test
    fun `the summary counts what somebody is about to restore`() {
        val summary = buildSnapshot(rows, settings, Instant.ofEpochMilli(7), "device-1", 40, 5).summary(runningVersionCode = 33)
        assertEquals(2, summary.active)
        assertEquals(1, summary.done)
        assertEquals(1, summary.presets)
        assertEquals(1, summary.places)
        assertEquals("device-1", summary.deviceId)
        assertTrue(summary.newerThanThisApp)
        assertFalse(buildSnapshot(rows, settings, Instant.EPOCH, "d", 33, 5).summary(33).newerThanThisApp)
    }
}
