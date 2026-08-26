package dev.rwilco.vault

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.data.NO_RECURRENCE
import dev.rwilco.data.ReminderEntity
import dev.rwilco.data.RwilcoDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two things about the vault only the phone can answer: that its crypto primitives are the
 * same ones the JVM tests pinned (the HMAC and the AES-GCM come from the phone's own providers),
 * and that a restore lands in real SQLite as one transaction.
 */
@RunWith(AndroidJUnit4::class)
class VaultDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: RwilcoDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, RwilcoDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun theDerivationOnThePhoneMatchesTheRfcVector() {
        val derived = VaultCrypto.pbkdf2HmacSha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64)
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            derived.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun aVaultSealedHereOpensHere() {
        val salt = ByteArray(16) { it.toByte() }
        val key = VaultCrypto.deriveKey("contraseña muy larga", salt, 1_000)
        val plain = "hola, vault".toByteArray()
        assertArrayEquals(plain, VaultCrypto.open(VaultCrypto.seal(plain, key, salt, 1_000), key))
    }

    @Test
    fun aRestoreReplacesTheTableWhole() = runBlocking {
        val dao = db.reminders()
        dao.upsert(row("old-1"))
        dao.upsert(row("old-2"))

        dao.replaceAll(listOf(row("new-1"), row("new-2"), row("new-3")))

        assertEquals(listOf("new-1", "new-2", "new-3"), dao.getAll().map { it.id })
    }

    private fun row(id: String) = ReminderEntity(
        id = id, text = "Water the plants", tags = "[]", triggers = "[]", actions = "[]", status = "ACTIVE",
        createdAt = 1, updatedAt = 1, doneAt = null, recurrence = NO_RECURRENCE,
    )
}
