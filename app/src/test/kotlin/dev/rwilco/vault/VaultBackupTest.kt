package dev.rwilco.vault

import dev.rwilco.data.NO_RECURRENCE
import dev.rwilco.data.ReminderEntity
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The whole run against memory: what is sent, what is written down, and what each refusal does. */
class VaultBackupTest {

    private val now = Instant.parse("2026-08-26T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val salt = ByteArray(16) { it.toByte() }
    private val key = VaultCrypto.deriveKey("correct horse battery staple", salt, 100)
    private val enabled = VaultState(
        enabled = true, owner = "ole", repo = "vault", pat = "pat",
        key = VaultState.encode(key), salt = VaultState.encode(salt), iterations = 100, deviceId = "phone-1",
    )
    private val row = ReminderEntity(
        id = "r1", text = "Water the plants", tags = "[]", triggers = "[]", actions = "[]", status = "ACTIVE",
        createdAt = 1, updatedAt = 1, doneAt = null, recurrence = NO_RECURRENCE,
    )
    private val settings = """{"theme":"SYSTEM"}"""

    private class MemoryStore(var state: VaultState) : VaultStateStore {
        override suspend fun read(): VaultState = state
        override suspend fun update(transform: (VaultState) -> VaultState) {
            state = transform(state)
        }
    }

    private class FakeTransport(
        var onWrite: (ByteArray, String?) -> String = { _, _ -> error("no write expected") },
        var onRead: () -> RemoteVault? = { error("no read expected") },
    ) : VaultTransport {
        val writes = mutableListOf<Pair<ByteArray, String?>>()
        var reads = 0
        override suspend fun read(): RemoteVault? {
            reads++
            return onRead()
        }
        override suspend fun write(bytes: ByteArray, replacingSha: String?): String {
            writes += bytes to replacingSha
            return onWrite(bytes, replacingSha)
        }
        override suspend fun probe(): RepoInfo = RepoInfo(isPrivate = true, canPush = true)
    }

    private val attention = mutableListOf<VaultOutcome>()
    private var resolved = 0

    private fun backup(store: MemoryStore, transport: FakeTransport) = VaultBackup(
        store = store,
        rows = { listOf(row) },
        settingsJson = { settings },
        transportFor = { transport },
        clock = clock,
        appVersionCode = 33,
        dbVersion = 5,
        onAttention = { attention += it },
        onResolved = { resolved++ },
    )

    private fun refusal(failure: TransportFailure) = VaultTransportException(failure, failure.name)

    @Test
    fun `off does nothing`() = runBlocking {
        val store = MemoryStore(VaultState())
        val transport = FakeTransport()
        assertEquals(VaultRunResult.DONE, backup(store, transport).run())
        assertTrue(transport.writes.isEmpty())
        assertEquals(VaultState(), store.state)
    }

    @Test
    fun `unchanged content makes no call`() = runBlocking {
        val store = MemoryStore(enabled.copy(lastUploadedFingerprint = fingerprint(listOf(row), settings)))
        val transport = FakeTransport()
        assertEquals(VaultRunResult.DONE, backup(store, transport).run())
        assertTrue(transport.writes.isEmpty())
        assertEquals(VaultOutcome.UP_TO_DATE, store.state.lastOutcome)
    }

    @Test
    fun `changed content is sealed, sent, and written down`() = runBlocking {
        val store = MemoryStore(enabled.copy(remoteSha = "0ld"))
        val transport = FakeTransport(onWrite = { bytes, _ -> VaultCrypto.gitBlobSha(bytes) })

        assertEquals(VaultRunResult.DONE, backup(store, transport).run())

        val (sent, replacing) = transport.writes.single()
        assertEquals("0ld", replacing)
        val snapshot = decodeSnapshot(VaultCrypto.open(sent, key))
        assertEquals(listOf(row), snapshot.reminders)
        assertEquals(settings, snapshot.settingsJson)
        assertEquals("phone-1", snapshot.deviceId)
        assertEquals(now, snapshot.exportedAt)
        val state = store.state
        assertEquals(fingerprint(listOf(row), settings), state.lastUploadedFingerprint)
        assertEquals(VaultCrypto.gitBlobSha(sent), state.remoteSha)
        assertEquals(VaultCrypto.gitBlobSha(sent), state.lastAttemptSha)
        assertEquals(now, state.lastUploadedAt)
        assertEquals(VaultOutcome.UPLOADED, state.lastOutcome)
        assertEquals(1, resolved)
        assertTrue(attention.isEmpty())
    }

    @Test
    fun `a conflict that is our own earlier upload is adopted`() = runBlocking {
        val store = MemoryStore(enabled)
        val transport = FakeTransport(onWrite = { _, _ -> throw refusal(TransportFailure.CONFLICT) })
        transport.onRead = { RemoteVault(ByteArray(0), VaultCrypto.gitBlobSha(transport.writes.single().first)) }

        assertEquals(VaultRunResult.DONE, backup(store, transport).run())

        assertEquals(1, transport.reads)
        assertEquals(VaultOutcome.UPLOADED, store.state.lastOutcome)
        assertEquals(store.state.lastAttemptSha, store.state.remoteSha)
        assertTrue(attention.isEmpty())
    }

    @Test
    fun `a conflict with another writer stops and says so`() = runBlocking {
        val store = MemoryStore(enabled.copy(remoteSha = "0ld"))
        val transport = FakeTransport(
            onWrite = { _, _ -> throw refusal(TransportFailure.CONFLICT) },
            onRead = { RemoteVault(ByteArray(0), "s0me0ne3lse") },
        )

        assertEquals(VaultRunResult.FAILED, backup(store, transport).run())

        assertEquals(VaultOutcome.CONFLICT, store.state.lastOutcome)
        assertNull(store.state.lastUploadedFingerprint)
        assertEquals("0ld", store.state.remoteSha, "the remote sha is not adopted from a stranger")
        assertEquals(listOf(VaultOutcome.CONFLICT), attention)
    }

    @Test
    fun `a refused token stops and says so`() = runBlocking {
        val store = MemoryStore(enabled)
        val transport = FakeTransport(onWrite = { _, _ -> throw refusal(TransportFailure.AUTH) })
        assertEquals(VaultRunResult.FAILED, backup(store, transport).run())
        assertEquals(VaultOutcome.AUTH, store.state.lastOutcome)
        assertEquals(listOf(VaultOutcome.AUTH), attention)
    }

    @Test
    fun `a missing repository stops and says so`() = runBlocking {
        val store = MemoryStore(enabled)
        val transport = FakeTransport(onWrite = { _, _ -> throw refusal(TransportFailure.REPO_MISSING) })
        assertEquals(VaultRunResult.FAILED, backup(store, transport).run())
        assertEquals(listOf(VaultOutcome.REPO_MISSING), attention)
    }

    @Test
    fun `network trouble is retried, quietly`() = runBlocking {
        val store = MemoryStore(enabled)
        val transport = FakeTransport(onWrite = { _, _ -> throw refusal(TransportFailure.TRANSIENT) })
        assertEquals(VaultRunResult.RETRY, backup(store, transport).run())
        assertEquals(VaultOutcome.TRANSIENT, store.state.lastOutcome)
        assertNotNull(store.state.lastAttemptSha, "the attempt is on record for the next run")
        assertTrue(attention.isEmpty())
    }

    @Test
    fun `a stored sha that is not the sent one is not built on`() = runBlocking {
        val store = MemoryStore(enabled)
        val transport = FakeTransport(onWrite = { _, _ -> "n0tth4t" })
        assertEquals(VaultRunResult.RETRY, backup(store, transport).run())
        assertNull(store.state.remoteSha)
        assertNull(store.state.lastUploadedFingerprint)
    }

    @Test
    fun `a run finishing after off writes nothing into the empty store`() = runBlocking {
        val store = MemoryStore(enabled)
        val transport = FakeTransport(onWrite = { bytes, _ ->
            store.state = VaultState()
            VaultCrypto.gitBlobSha(bytes)
        })
        assertEquals(VaultRunResult.DONE, backup(store, transport).run())
        assertEquals(VaultState(), store.state)
    }

    @Test
    fun `a PUT whose reply was lost is recognised on the next run and written over`() = runBlocking {
        // Run 1: GitHub commits the bytes, the reply never arrives. Every seal is fresh bytes,
        // so run 2 cannot match the remote against its own attempt — only against run 1's.
        val store = MemoryStore(enabled.copy(remoteSha = "0ld"))
        val landed = mutableListOf<String>()
        val transport = FakeTransport(onWrite = { bytes, _ ->
            landed += VaultCrypto.gitBlobSha(bytes)
            throw refusal(TransportFailure.TRANSIENT)
        })
        assertEquals(VaultRunResult.RETRY, backup(store, transport).run())
        val firstAttempt = landed.single()
        assertEquals(firstAttempt, store.state.lastAttemptSha)
        assertEquals("0ld", store.state.remoteSha, "nothing adopted from a reply that never came")

        // Run 2: the PUT against "0ld" is refused, the remote turns out to be run 1's bytes,
        // and the copy goes up over them with the sha the file actually has.
        transport.onWrite = { bytes, replacing ->
            if (replacing == firstAttempt) VaultCrypto.gitBlobSha(bytes) else throw refusal(TransportFailure.CONFLICT)
        }
        transport.onRead = { RemoteVault(ByteArray(0), firstAttempt) }
        assertEquals(VaultRunResult.DONE, backup(store, transport).run())

        assertEquals(1, transport.reads)
        assertEquals(listOf("0ld", "0ld", firstAttempt), transport.writes.map { it.second })
        assertEquals(VaultOutcome.UPLOADED, store.state.lastOutcome)
        assertEquals(VaultCrypto.gitBlobSha(transport.writes.last().first), store.state.remoteSha)
        assertTrue(attention.isEmpty(), "not somebody else's write")
    }
}
