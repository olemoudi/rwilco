package dev.rwilco.vault

import dev.rwilco.data.ReminderEntity
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the mistake that would make an old backup unreadable: changing what a row is without
 * saying so. The column list is frozen here per data version; touching [ReminderEntity] fails
 * this test until somebody decides, out loud, whether the change is additive (extend the list)
 * or not (bump [VAULT_SCHEMA], add a step, add a fixture). The fixtures are the other half: a
 * vault written by every data version there has been, restored through the chain as it is now.
 */
@OptIn(ExperimentalSerializationApi::class)
class VaultSchemaTest {

    @Test
    fun `the columns of a row are the ones data version 1 froze`() {
        assertEquals(FROZEN_COLUMNS, ReminderEntity.serializer().descriptor.elementNames.toList())
    }

    @Test
    fun `there is a migration step for every data version but the current one`() {
        assertEquals(VAULT_SCHEMA - 1, VAULT_MIGRATIONS.size)
    }

    @Test
    fun `a vault from every data version there has been restores`() {
        for (version in 1..VAULT_SCHEMA) {
            val vault = resource("/vault/v$version.vault")
            val expected = resource("/vault/v$version.snapshot.json")
            val header = VaultCrypto.header(vault)
            val key = VaultCrypto.deriveKey(TEST_PASSPHRASE, header.salt, header.iterations)
            val restored = decodeSnapshot(VaultCrypto.open(vault, key))
            assertEquals(decodeSnapshot(expected), restored, "v$version.vault does not open to v$version.snapshot.json")
            assertEquals(VAULT_SCHEMA, restored.schema)
            assertTrue(restored.reminders.isNotEmpty(), "a fixture with no reminders guards nothing")
        }
    }

    private fun resource(path: String): ByteArray {
        val stream = VaultSchemaTest::class.java.getResourceAsStream(path)
        assertNotNull(stream, "missing fixture $path — every data version needs one (see the class comment)")
        return stream!!.use { it.readBytes() }
    }

    companion object {
        const val TEST_PASSPHRASE = "rwilco-test-passphrase"

        /** Data version 1: the columns of Room schema 5, in declaration order. */
        val FROZEN_COLUMNS = listOf(
            "id", "text", "tags", "triggers", "actions", "status", "createdAt", "updatedAt", "doneAt",
            "snoozedUntil", "lastFiredAt", "armedFor", "ruleMatch", "armedRule", "firedRules", "recurrence", "lastDealtAt",
        )
    }
}
